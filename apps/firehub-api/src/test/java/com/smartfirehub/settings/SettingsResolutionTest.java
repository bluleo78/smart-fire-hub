package com.smartfirehub.settings;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 해석 규칙 테스트(Task 4). {@code SettingsService.getValue}/{@code getAsMap} 가 {@code
 * tenant_settings} 오버라이드 → {@code system_settings} 폴백을 올바르게 해석하는지 검증한다.
 *
 * <p><b>픽스처와 검증 대상의 트랜잭션 취급이 다르다.</b> 오버라이드 행을 만드는 {@code
 * tenantSettingsRepository.upsert} 는 {@code runInTenantTransaction(transactionTemplate, tenantId,
 * ...)} 으로 감싸야 GUC 가 주입되고 정책의 WITH CHECK 를 통과한다. 반대로 검증 대상인 {@code
 * settingsService.getValue} 는 자기 {@code @Transactional} 로 트랜잭션을 열고 그 시점의 {@code
 * TenantContext} 로 GUC 를 받으므로 <b>감싸지 않고 그대로 부른다</b> — 감싸면 실제 배선을 우회해
 * 버린다. 픽스처를 감싸지 않으면 0행이 들어가 "폴백이 잘 되네" 하며 통과하는, 정확히 검증하려던
 * 것을 놓치는 테스트가 된다.
 */
class SettingsResolutionTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @AfterEach
  void cleanup() {
    if (testTenant != null) {
      deleteTenants(dsl, testTenant);
      testTenant = null;
    }
    // IntegrationTestBase#clearTenantContext 가 뒤이어 돌지만, 일부 테스트가 명시적으로 컨텍스트를
    // 지우거나 다른 테넌트로 바꾸므로 여기서도 원복해 다음 테스트에 영향이 새지 않게 한다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void 오버라이드가_있으면_테넌트_값을_돌려준다() {
    testTenant = createActiveTenant(dsl, "sr-override");
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.model", "tenant-model", null));

    // 검증 대상은 트랜잭션으로 감싸지 않고 그대로 부른다 — 자기 @Transactional 이 이 시점의
    // TenantContext 로 GUC 를 받는다.
    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains("tenant-model");
  }

  @Test
  void 오버라이드가_없으면_플랫폼_값으로_폴백한다() {
    testTenant = createActiveTenant(dsl, "sr-fallback");
    // 오버라이드 행을 만들지 않은 채로 조회한다.
    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
  }

  @Test
  void 플랫폼_잠금_키는_오버라이드_행이_있어도_무시한다() {
    // 방어적 계약. 화이트리스트가 나중에 좁아지거나 누가 직접 SQL 로 행을 넣어도, 읽기는
    // 화이트리스트를 다시 확인하므로 잠긴 키가 테넌트 값으로 해석되지 않는다.
    testTenant = createActiveTenant(dsl, "sr-locked");
    // ai.api_key 는 SettingsOverridePolicy 화이트리스트에 없는 플랫폼 잠금 키다. 정상 upsert 경로로는
    // 만들 수 없는 상태(직접 SQL 로 밀어넣은 것)를 재현하기 위해 저장소 자체가 아니라 직접 DML 을 쓴다.
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () ->
            dsl.execute(
                "insert into tenant_settings (tenant_id, key, value) values (?, 'ai.api_key', 'sneaky-key')",
                testTenant));

    TenantContext.set(testTenant);
    // 플랫폼 값(비어있거나 seed 값)으로 폴백해야 한다 — 절대 'sneaky-key' 가 아니다.
    assertThat(settingsService.getValue("ai.api_key")).isNotEqualTo(java.util.Optional.of("sneaky-key"));
  }

  @Test
  void 컨텍스트가_없으면_예외가_아니라_플랫폼_값이다() {
    // 이 단언이 이 밴드의 핵심 회귀 가드다. TenantContext 없이 getValue 를 부르는 것은 JobRunr
    // @Job·@Async·@Scheduled 배경 경로에서 정상적으로 일어난다. require() 를 쓰면 임베딩 백필과
    // 문서 인제스션이 영구 무동작이 된다(P3-a·P2-g 전례).
    TenantContext.clear();
    assertThat(settingsService.getValue("ai.model")).isPresent();
    assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
  }

  @Test
  void getAsMap_은_오버라이드를_덮어쓴_결과를_준다() {
    // AI 채팅·프로액티브 잡이 실제로 읽는 경로다. getValue 만 고치고 getAsMap 을 빠뜨리면 화면에는
    // 오버라이드가 보이지만 실제 호출은 플랫폼 값으로 나간다.
    testTenant = createActiveTenant(dsl, "sr-asmap");
    runInTenantTransaction(
        transactionTemplate, testTenant, () -> tenantSettingsRepository.upsert("ai.model", "tenant-model", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getAsMap("ai");
    assertThat(resolved).containsEntry("ai.model", "tenant-model");
    // 오버라이드 대상이 아닌 다른 ai.* 키는 여전히 플랫폼 값이어야 한다.
    assertThat(resolved).containsKey("ai.max_turns");
  }

  @Test
  void 다른_테넌트로_전환하면_각자의_오버라이드만_보인다() {
    // 세 상태 전이가 실제로 컨텍스트에 반응하는지 — 캐싱/고정 등으로 값이 굳어 있지 않은지 확인한다.
    long tenantA = createActiveTenant(dsl, "sr-a");
    long tenantB = createActiveTenant(dsl, "sr-b");
    try {
      runInTenantTransaction(
          transactionTemplate, tenantA, () -> tenantSettingsRepository.upsert("ai.model", "model-a", null));
      // tenantB 는 오버라이드를 만들지 않는다 — 플랫폼 값이어야 한다.

      TenantContext.set(tenantA);
      assertThat(settingsService.getValue("ai.model")).contains("model-a");

      TenantContext.set(tenantB);
      assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");
    } finally {
      deleteTenants(dsl, tenantA, tenantB);
    }
  }
}
