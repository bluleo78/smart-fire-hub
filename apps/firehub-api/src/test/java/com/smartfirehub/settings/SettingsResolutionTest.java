package com.smartfirehub.settings;

import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.resolvedSetting;
import static com.smartfirehub.support.SettingsTestSupport.restoreSystemSettingValue;
import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
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
  void AI_키는_테넌트_값이_없으면_코드_기본값이다() {
    // AI 설정은 테넌트 전용 — 플랫폼 행을 심어 둔 채 무시되는지는 AiSettingsTenantOnlyTest 가 본다.
    testTenant = createActiveTenant(dsl, "sr-fallback");
    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains(AiBehaviorDefaults.MODEL);
  }

  @Test
  void 플랫폼_잠금_키는_오버라이드_행이_있어도_무시한다() {
    // 방어적 계약. 화이트리스트가 나중에 좁아지거나 누가 직접 SQL 로 행을 넣어도, 읽기는
    // 화이트리스트를 다시 확인하므로 잠긴 키가 테넌트 값으로 해석되지 않는다.
    //
    // 검증 키로 ai.api_key 대신 embedding.model 을 쓴다(Task 2). ai.api_key 는 타입형 전환
    // (2026-09)으로 테넌트 오버라이드 허용 키에서 빠졌다 — 이 테스트가 지키려는 "단일 키
    // 경로가 화이트리스트를 다시 확인한다"는 성질을 더 이상 그 키로는 관측할 수 없다(오버라이드
    // 행 자체를 만들 수 없다). embedding.model 은 화이트리스트 밖(플랫폼 잠금)이면서 번들도
    // 아니라 getValue 로 안전하게 조회되므로 같은 불변식을 계속 지킨다.
    testTenant = createActiveTenant(dsl, "sr-locked");
    // embedding.model 은 SettingsOverridePolicy 화이트리스트에 없는 플랫폼 잠금 키다. 정상 upsert
    // 경로로는 만들 수 없는 상태(직접 SQL 로 밀어넣은 것)를 재현하기 위해 저장소 자체가 아니라
    // 직접 DML 을 쓴다.
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () ->
            dsl.execute(
                "insert into tenant_settings (tenant_id, key, value) values (?, 'embedding.model', 'sneaky-model')",
                testTenant));

    TenantContext.set(testTenant);
    // 플랫폼 값(seed 값)으로 폴백해야 한다 — 절대 'sneaky-model' 이 아니다.
    assertThat(settingsService.getValue("embedding.model"))
        .isNotEqualTo(java.util.Optional.of("sneaky-model"));
  }

  @Test
  void 컨텍스트가_없으면_예외가_아니라_기본값이다() {
    // 이 단언이 이 밴드의 핵심 회귀 가드다. TenantContext 없이 getValue 를 부르는 것은 JobRunr
    // @Job·@Async·@Scheduled 배경 경로에서 정상적으로 일어난다. require() 를 쓰면 임베딩 백필과
    // 문서 인제스션이 영구 무동작이 된다(P3-a·P2-g 전례).
    TenantContext.clear();
    // 플랫폼 키는 플랫폼 값, AI 키는 코드 기본값.
    assertThat(settingsService.getValue("embedding.model")).isPresent();
    assertThat(settingsService.getValue("ai.model")).contains(AiBehaviorDefaults.MODEL);
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
    // 테넌트가 저장하지 않은 다른 ai.* 키는 코드 기본값이다.
    assertThat(resolved)
        .containsEntry("ai.max_turns", String.valueOf(AiBehaviorDefaults.MAX_TURNS));
  }

  @Test
  void getAsMap_은_플랫폼_행이_없는_오버라이드_전용_키도_보여준다() {
    // Fix round 1: ai.session_max_tokens 는 어떤 마이그레이션도 system_settings 에 시드하지 않았다
    // (V15/V31/V40/V41 이 다른 ai.* 키는 시드해도 이 키는 빠져 있다). 화이트리스트에는 있으므로
    // 테넌트가 오버라이드할 수 있는데, platform.keySet() 만 오버라이드 조회에 넘기던 이전 구현은
    // 플랫폼 행이 없는 이 키의 오버라이드를 절대 드러내지 못했다(교집합). 합집합이어야 통과한다.
    testTenant = createActiveTenant(dsl, "sr-asmap-only");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.session_max_tokens", "12345", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getAsMap("ai");
    assertThat(resolved).containsEntry("ai.session_max_tokens", "12345");
  }

  @Test
  void getResolvedByPrefix_은_플랫폼_행이_없는_오버라이드_전용_키를_overridden으로_보여준다() {
    testTenant = createActiveTenant(dsl, "sr-resolved-only");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.session_max_tokens", "54321", null));

    TenantContext.set(testTenant);
    var resolved = settingsService.getResolvedByPrefix("ai");
    var entry =
        resolved.stream()
            .filter(r -> r.key().equals("ai.session_max_tokens"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("ai.session_max_tokens 가 목록에 없다 — 합집합이 아니라 교집합이다"));

    assertThat(entry.value()).isEqualTo("54321");
    assertThat(entry.overridden()).isTrue();
    assertThat(entry.tenantEditable()).isTrue();
  }

  @Test
  void 프리픽스_경로도_잠긴_키_오버라이드는_무시한다() {
    // 단일 키 조회(findValue)에서 프리픽스 조회(findByPrefix)로 바꾸면서 화이트리스트 필터가
    // 새어나가면 안 된다 — 합집합으로 바뀐 것이 "잠긴 키까지 전부 노출"로 변질되지 않았는지 확인.
    //
    // 검증 키로 ai.api_key 대신 embedding.model 을 쓴다. ai.api_key 는 타입형 전환(2026-09)으로
    // 다시 테넌트 오버라이드 허용 키에서 빠졌지만(플랫폼 기본값 전용), "잠긴 키 오버라이드가
    // 새지 않는다"는 이 불변식은 애초에 단일 키 조회(getValue) 로는 그 키에서 관측할 수 없다 —
    // rejectBundleKey 이전에도 ai.api_key 는 재분류를 여러 번 거쳐 왔다. embedding.model 은
    // 화이트리스트 밖(플랫폼 잠금)으로 계속 남아 있어 이 재분류들과 무관하게 같은 불변식을
    // 안정적으로 지킨다.
    testTenant = createActiveTenant(dsl, "sr-locked-prefix");
    // 저장소는 화이트리스트를 모른다(서비스만 안다) — upsert 로 직접 잠긴 키를 심어 재현한다.
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("embedding.model", "sneaky-model", null));
    try {
      TenantContext.set(testTenant);

      var asMap = settingsService.getAsMap("embedding");
      assertThat(asMap.get("embedding.model")).isNotEqualTo("sneaky-model");

      var resolved = settingsService.getResolvedByPrefix("embedding");
      var modelEntry =
          resolved.stream().filter(r -> r.key().equals("embedding.model")).findFirst().orElseThrow();
      assertThat(modelEntry.overridden()).isFalse();
      assertThat(modelEntry.value()).isNotEqualTo("sneaky-model");
    } finally {
      // upsert 로 심은 오버라이드 행을 명시적으로 정리한다(deleteTenants 가 tenant_settings 를
      // cascade 로 지우긴 하지만, 여기서 직접 지워 이 테스트의 의도를 코드로 남긴다).
      runInTenantTransaction(
          transactionTemplate,
          testTenant,
          () -> tenantSettingsRepository.delete("embedding.model"));
    }
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

  /**
   * {@code getResolvedByPrefix} 도 비밀 키를 마스킹한다.
   *
   * <p>이 경로는 web 설정 화면이 실제로 부르는 경로다({@code GET /api/v1/settings?prefix=...}).
   * 마스킹을 빠뜨리면 비밀 키(여기서는 {@code embedding.api_key})의 <b>AES 암호문이 그대로</b>
   * 응답에 실린다. (예전 검증 키 {@code ai.api_key} 는 #706 으로 사라졌다.) 이 프로젝트는
   * 정확히 그 사고를 이미 한 번 냈다 — SMTP 전용 읽기 메서드만 마스킹하고 {@code getAll} 은
   * 빠뜨려서 암호문이 나갔다. 읽기 경로를 새로 만들 때마다 같은 실수가 가능하므로 경로별로 단언한다.
   *
   * <p><b>여기서는</b> 오버라이드 값 쪽을 검사하지 않는다. 예전에는 그 근거가 "비밀 키는 전부
   * 플랫폼 잠금이라 오버라이드 행으로 존재할 수 없다"였고, 그 사실이 곧 "이 누락이 어떤 기존
   * 테스트에도 걸리지 않은" 이유였다. <b>P7-c1 이 그 전제를 죽였다</b> — {@code smtp.*} 6키가
   * 테넌트에 열리면서(#712 이후 테넌트 전용) {@code SECRET_KEYS} 의 원소인
   * {@code smtp.password} 가 실제로 테넌트 행으로 존재한다. 그 경로의 마스킹은
   * {@code SettingsWritePlaneTest.테넌트_SMTP_비밀번호는_암호화_저장되고_마스킹되어_읽힌다} 가
   * 조건 없이 단언한다(평문도 암호문도 아님까지). 이 문단을 "존재할 수 없다"로 되돌리지 마라 —
   * 노출 안전성에 대한 거짓 안심이 되고, 그것을 믿고 어떤 읽기 경로의 오버라이드 마스킹을
   * 생략하면 테넌트 비밀번호 암호문이 나간다.
   *
   * <p><b>먼저 진짜 키를 저장한다.</b> 테스트 DB 의 {@code embedding.api_key} 시드 값은 빈 문자열이라,
   * 값이 있을 때만 단언하는 형태로 두면 <b>단언이 한 줄도 실행되지 않는</b> 공허한 테스트가 된다
   * (실제로 그렇게 쓰여 있었다). 그 상태에서는 {@code getResolvedByPrefix} 의 {@code maskSecret}
   * 을 통째로 지워도 이 테스트가 녹색으로 남는다 — 즉 막으려던 유출을 전혀 막지 못한다.
   * 조건부 가드 대신 값을 만들어 두고 <b>무조건</b> 단언한다.
   *
   * <p>공유 테스트 DB 이므로 원래 값을 저장했다가 {@code finally} 에서 그대로 되돌린다.
   */
  @Test
  void getResolvedByPrefix_는_비밀_키를_마스킹한다() {
    String original = rawSystemSettingValue(dsl, "embedding.api_key");
    try {
      // 평문을 넣으면 서비스가 암호화해 저장한다 — 마스킹이 없으면 이 암호문이 그대로 응답에 실린다.
      settingsService.updatePlatformSettings(
          java.util.Map.of("embedding.api_key", "sk-real-secret"), null);
      // 전제 확인: 저장된 원문이 실제로 암호문("iv:ciphertext")이어야 이 테스트가 의미를 갖는다.
      assertThat(rawSystemSettingValue(dsl, "embedding.api_key")).contains(":");

      var apiKey = resolvedSetting(settingsService, "embedding", "embedding.api_key");

      assertThat(apiKey.value()).startsWith("****");
      // 암호문은 "iv:ciphertext" 형태이므로 콜론이 없다는 것이 곧 암호문이 아니라는 뜻이다.
      assertThat(apiKey.value()).doesNotContain(":");
      assertThat(apiKey.value()).doesNotContain("sk-real-secret");
    } finally {
      restoreSystemSettingValue(dsl, "embedding.api_key", original);
    }
  }


  /**
   * 프리픽스에 마침표를 붙이면 아무것도 매칭하지 않는다 — {@code ProactiveJobAsyncRunner} 가 빠졌던
   * 함정의 회귀 가드(Task 8).
   *
   * <p>{@code findByPrefix} 가 스스로 {@code prefix + ".%"} 를 만들기 때문에 {@code "ai."} 는
   * {@code "ai..%"} 가 되어 <b>예외 없이 빈 맵</b>을 돌려준다. 조용한 빈 결과는 호출부에서 폴백
   * 기본값으로 흡수되므로(그 잡은 {@code agent_type} 을 항상 {@code "sdk"} 로 읽었다) 로그에도
   * 흔적이 남지 않는다. 두 형태를 같은 테스트에서 대조해 두면, 누군가 다시 마침표를 붙였을 때
   * "왜 설정이 안 먹지"를 런타임에서 추적하지 않아도 된다.
   */
  @Test
  void 프리픽스에_마침표를_붙이면_조용히_빈_맵이_된다() {
    assertThat(settingsService.getAsMap("ai"))
        .as("마침표 없는 형태가 올바르다")
        .containsKey("ai.model");

    assertThat(settingsService.getAsMap("ai."))
        .as("마침표를 붙이면 ai..%% 패턴이 되어 0행 — 이 형태를 쓰면 안 된다")
        .isEmpty();
  }

  // 이 자리에 있던 SMTP 해석 테스트 9개(테넌트 값의 발송 반영, 연결 5키 번들 원자 해석,
  // 발신자 주소 키 단위 상속, 번들 채움의 STARTTLS 기본값, 컨텍스트 없음 → 플랫폼 SMTP 폴백,
  // getValue 의 번들 키 거부 등)는 #712 로 지웠다 — SMTP 가 테넌트 전용이 되어 플랫폼 평면과
  // 번들 규칙이 통째로 사라졌다. 컨텍스트 없음 → 플랫폼 폴백은 정반대("미설정")로 뒤집혔다.
  // 남는 성질(테넌트 값이 복호화되어 발송에 실림, 빈 비밀번호는 복호화하지 않음, 플랫폼 행은
  // 어느 경로에서도 읽히지 않음, 컨텍스트 없음 = 미설정)은 SmtpSettingsTenantOnlyTest 가 고정한다.

  // 이 자리에 있던 AI 자격증명 번들 테스트 5개(AI_번들키는_단일_조회를_거부한다,
  // 테넌트가_실행_형태만_재정의하면_플랫폼_AI_자격증명은_새지_않는다,
  // 테넌트가_API_키만_재정의하면_실행_형태는_sdk로_채워진다,
  // AI_자격증명_3키를_아무것도_재정의하지_않으면_전부_플랫폼_값을_그대로_상속한다,
  // getAiCredentials_는_번들_해석을_따른다)는 지웠다 — 검증 대상이던
  // SettingsService.AI_CREDENTIAL_KEYS 번들과 getAiCredentials()/getAiCredentials 자체가
  // 타입형 전환(2026-09)으로 사라졌고, 옛 3키 자체도 #706 에서 코드·데이터(V126) 모두에서
  // 지워졌다. 그 자리를 대체하는 성질(테넌트의 AI 호출에 플랫폼 자격증명이 섞이지 않는다)은 이제
  // "AI 자격증명은 테넌트 전용 문서 하나(ai.credential)"라는 구조 자체가 지키고,
  // AiCredentialLeakGuardTest(범용 경로 차단)와 AiCredentialServiceTest(플랫폼 행을 읽지 않음)가
  // 각자의 각도에서 고정한다.

  @Test
  void AI_번들이_아닌_키는_단일_조회가_그대로_동작한다() {
    // ai.model 은 번들이 아니라 키 단위 해석이므로 getValue 가 옳은 답을 준다.
    assertThat(settingsService.getValue("ai.model")).isPresent();
  }
}
