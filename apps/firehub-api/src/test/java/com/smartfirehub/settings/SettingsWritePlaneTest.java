package com.smartfirehub.settings;

import static com.smartfirehub.support.SettingsTestSupport.deleteSystemSetting;
import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static com.smartfirehub.support.SettingsTestSupport.restoreSystemSettingValue;
import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테넌트 평면 쓰기 좁히기 + 오버라이드 해제(P7-b Task 5).
 *
 * <p>{@link #테넌트_쓰기는_tenant_settings_에_들어가고_system_settings_는_그대로다} 가 이 밴드
 * 전체의 존재 이유다 — 오늘의 결함은 한 테넌트의 저장이 {@code system_settings}(전역 18행)를 바꿔
 * 전 테넌트에 적용되는 것이었다. 이 테스트가 깨지면 그 결함이 되돌아왔다는 뜻이다.
 */
class SettingsWritePlaneTest extends IntegrationTestBase {

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
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  /** 이 단언이 이 밴드의 존재 이유다 — 저장 후 system_settings.ai.model 이 변하지 않아야 한다. */
  @Test
  void 테넌트_쓰기는_tenant_settings_에_들어가고_system_settings_는_그대로다() {
    testTenant = createActiveTenant(dsl, "swp-write");
    String platformValueBefore = rawSystemSettingValue(dsl, "ai.model");

    TenantContext.set(testTenant);
    settingsService.updateSettings(Map.of("ai.model", "tenant-only-model"), null);

    // tenant_settings 에 들어갔다.
    assertThat(
            runInTenantTransaction(
                transactionTemplate, testTenant, () -> tenantSettingsRepository.findValue("ai.model")))
        .contains("tenant-only-model");

    // system_settings 는 이 쓰기 전후로 완전히 그대로다 — 이 밴드가 고치는 결함의 핵심.
    assertThat(rawSystemSettingValue(dsl, "ai.model")).isEqualTo(platformValueBefore);
  }

  @Test
  void 플랫폼_잠금_키를_쓰면_거부된다() {
    testTenant = createActiveTenant(dsl, "swp-locked");
    TenantContext.set(testTenant);

    // 메시지에 키 이름이 들어가야 web 이 어느 필드가 잠겼는지 보여줄 수 있다.
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.api_key", "sneaky"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("플랫폼 관리자만 변경할 수 있는 설정입니다: ai.api_key");

    assertThatThrownBy(
            () -> settingsService.updateSettings(Map.of("embedding.model", "sneaky-model"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("플랫폼 관리자만 변경할 수 있는 설정입니다: embedding.model");
  }

  @Test
  void SMTP_쓰기는_테넌트_평면에서_거부된다() {
    testTenant = createActiveTenant(dsl, "swp-smtp");
    TenantContext.set(testTenant);

    // 컨트롤러가 아니라 서비스에서 막는다 — 호출 경로가 하나 더 생겨도 함께 막힌다.
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.host", "evil.example"), null))
        .isInstanceOf(AccessDeniedException.class);

    // 컨텍스트가 없어도 거부한다 — 이것이 핵심 단언이다.
    // "컨텍스트 없음 = 플랫폼이므로 허용"으로 판정하면 배경 경로(JobRunr·@Async·@Scheduled·스레드
    // 홉 이후)가 전부 플랫폼으로 오인되어 공유 SMTP 자격증명 쓰기가 열린다. 플랫폼 관리자는
    // updatePlatformSettings 를 쓰므로 이 경로는 무조건 닫혀 있어야 한다.
    TenantContext.clear();
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.host", "evil.example"), null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void 오버라이드_삭제는_상속으로_되돌린다() {
    testTenant = createActiveTenant(dsl, "swp-clear");
    runInTenantTransaction(
        transactionTemplate,
        testTenant,
        () -> tenantSettingsRepository.upsert("ai.model", "tenant-override", null));

    TenantContext.set(testTenant);
    assertThat(settingsService.getValue("ai.model")).contains("tenant-override");

    settingsService.clearOverride("ai.model");

    // 삭제 후에는 플랫폼 값으로 폴백한다.
    assertThat(settingsService.getValue("ai.model")).contains("claude-sonnet-5");

    // 없는 오버라이드를 지워도 예외가 없다 — "이미 상속 중"은 오류가 아니라 멱등한 성공이다
    // (컨트롤러는 이 경우에도 204 를 돌려준다).
    assertDoesNotThrow(() -> settingsService.clearOverride("ai.model"));
  }

  /**
   * 플랫폼 쓰기는 <b>시드 행이 없는 키도</b> 실제로 저장해야 한다.
   *
   * <p>이 테스트를 쓰기 전 저장소의 {@code updateSettings} 는 {@code UPDATE ... WHERE key = ?} 였다.
   * 행이 없으면 0행이 갱신되고 <b>예외 없이 성공으로 끝난다</b> — 운영자는 204 를 받고 저장됐다고
   * 믿지만 아무 일도 일어나지 않는다. {@code ai.session_max_tokens} 가 정확히 그 상태였다:
   * {@code ALLOWED_AI_KEYS} 에 있고 값 검증(1000~200000)도 통과하는데 어떤 마이그레이션도 시드하지
   * 않아, <b>플랫폼 운영자가 영원히 설정할 수 없는 키</b>였다. 이 밴드가 테넌트에게만 재정의를
   * 허용한 6키 중 하나라서, 플랫폼 기본값을 못 정하는 것은 2단 상속의 윗단이 비어 있다는 뜻이다.
   *
   * <p>정리는 <b>내가 만든 행만</b> 지운다 — 공유 테스트 DB 이므로 시드 행에는 손대지 않는다.
   */
  @Test
  void 플랫폼_쓰기는_시드_행이_없는_키도_저장한다() {
    String key = "ai.session_max_tokens";
    // 전제 확인: 이 키는 시드되어 있지 않다. 언젠가 시드되면 이 테스트의 의미가 달라지므로
    // 조용히 통과시키지 않고 전제 자체를 단언한다.
    assertThat(rawSystemSettingValue(dsl, key)).isNull();

    try {
      settingsService.updatePlatformSettings(Map.of(key, "50000"), null);

      // UPDATE-only 였다면 여기서 여전히 null 이고, 그 사이 예외는 하나도 나지 않았다.
      assertThat(rawSystemSettingValue(dsl, key)).isEqualTo("50000");
      // 운영자 목록(getAll)에도 나타나야 한다 — 보이지 않으면 고칠 수도 없다.
      assertThat(settingsService.getAll().stream().map(s -> s.key())).contains(key);
    } finally {
      deleteSystemSetting(dsl, key);
    }
  }

  /**
   * {@code ai.session_max_tokens} 의 유효 범위는 <b>web 의 검증과 같아야 한다</b>(10,000~200,000).
   *
   * <p>백엔드 하한이 1000 이던 시절에는 이 어긋남이 도달 불가였다 — 시드 행이 없고 저장소가
   * UPDATE-only 라 이 키를 <b>아무도 저장할 수 없었기 때문</b>이다. 저장 경로를 upsert 로 연
   * 순간 그 잠재 결함이 함께 깨어난다: 운영자가 5000 을 저장하면 테넌트 설정 화면이 그 값으로
   * 시드되고, web 의 하한 10000 에 걸려 <b>사용자가 그 필드를 건드리지도 않았는데 temperature
   * 하나 고치려던 저장이 통째로 막힌다</b>. 잠긴 경로를 여는 수정은 그 끝에 있던 결함을 같이
   * 깨운다 — 그래서 두 하한이 같다는 것을 여기서 못 박는다.
   */
  @Test
  void 세션_최대_토큰_하한은_web_과_같은_10000_이다() {
    // web 이 거부하는 값(5000)은 백엔드도 거부해야 한다 — 저장돼 버리면 화면이 잠긴다.
    assertThatThrownBy(
            () ->
                settingsService.updatePlatformSettings(
                    Map.of("ai.session_max_tokens", "5000"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10000");

    // 경계값은 통과해야 한다. 정리는 내가 만든 행만 지운다(공유 test DB).
    try {
      assertDoesNotThrow(
          () ->
              settingsService.updatePlatformSettings(
                  Map.of("ai.session_max_tokens", "10000"), null));
      assertThat(rawSystemSettingValue(dsl, "ai.session_max_tokens")).isEqualTo("10000");
    } finally {
      deleteSystemSetting(dsl, "ai.session_max_tokens");
    }
  }

  /**
   * 플랫폼 쓰기는 <b>서비스 레벨에서도</b> 평면을 확인한다.
   *
   * <p>이 메서드는 전 테넌트가 공유하는 {@code system_settings} 18행을 쓴다 — 이 밴드에서 가장
   * 위험한 쓰기다. 그런데 보호가 컨트롤러 애노테이션과 {@code PlatformPlaneFilter} 에만 있었다:
   * {@code /api/v1/**} 경로에 이 메서드를 부르는 호출자가 하나 생기면 필터는 그 경로를 보지 않고
   * 메서드는 아무것도 묻지 않는다. {@code updateSmtpSettings} 는 이미 서비스에서 막고 있었으므로
   * 원칙은 있었고 적용만 빠져 있었다.
   *
   * <p>판정은 인증 <b>타입</b>으로 한다 — 테넌트 인증이 놓여 있으면 거부. "인증 없음"은 거부하지
   * 않는다(배경 잡·직접 호출이 그 상태이고, 테넌트 HTTP 요청은 반드시 인증이 채워진다).
   */
  @Test
  void 플랫폼_쓰기는_테넌트_인증이_놓여_있으면_거부된다() {
    // 원복 준비: 이 테스트가 성공하면 아무것도 쓰이지 않는다. 그러나 **가드를 제거하는 변이
    // 테스트를 돌리면 쓰기가 실제로 커밋된 뒤 단언이 실패한다** — 공유 test DB 에서는 그 순간
    // ai.model 이 "hijacked" 로 남아 무관한 테스트들이 줄줄이 깨진다(실제로 한 번 겪었다).
    // 변이 실험까지 안전하도록 값을 미리 붙잡아 두고 finally 에서 되돌린다.
    String original = rawSystemSettingValue(dsl, "ai.model");
    var tenantAuth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            1L, null, java.util.List.of());
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(tenantAuth);
    try {
      assertThatThrownBy(
              () -> settingsService.updatePlatformSettings(Map.of("ai.model", "hijacked"), null))
          .isInstanceOf(AccessDeniedException.class);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
      restoreSystemSettingValue(dsl, "ai.model", original);
    }
  }

  /**
   * 값이 {@code null} 이면 500 이 아니라 400 계열(IllegalArgumentException)이어야 한다.
   *
   * <p>{@code validateValues} 가 {@code ai.model} 을 free-form 으로 두므로 {@code null} 이 저장
   * 계층까지 내려가 NPE 가 되고, 클라이언트 오류가 <b>서버 오류로 보고</b>됐다. 두 쓰기 경로가
   * 서로 다른 지점에서 터지던 것(테넌트는 {@code upsert} 의 requireNonNull, 플랫폼은
   * {@code encryptIfSecret} 의 isBlank)을 진입부 한 곳으로 모았다.
   */
  @Test
  void null_값은_저장_계층까지_가지_않고_거부된다() {
    testTenant = createActiveTenant(dsl, "swp-null");
    TenantContext.set(testTenant);

    var withNull = new java.util.HashMap<String, String>();
    withNull.put("ai.model", null);
    assertThatThrownBy(() -> settingsService.updateSettings(withNull, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ai.model");
  }

}
