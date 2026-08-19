package com.smartfirehub.settings;

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
    String platformValueBefore = rawSystemSettingValue("ai.model");

    TenantContext.set(testTenant);
    settingsService.updateSettings(Map.of("ai.model", "tenant-only-model"), null);

    // tenant_settings 에 들어갔다.
    assertThat(
            runInTenantTransaction(
                transactionTemplate, testTenant, () -> tenantSettingsRepository.findValue("ai.model")))
        .contains("tenant-only-model");

    // system_settings 는 이 쓰기 전후로 완전히 그대로다 — 이 밴드가 고치는 결함의 핵심.
    assertThat(rawSystemSettingValue("ai.model")).isEqualTo(platformValueBefore);
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

    // 컨텍스트가 없으면(플랫폼 평면) 여전히 통과해야 한다 — 회귀 가드.
    TenantContext.clear();
    assertDoesNotThrow(() -> settingsService.updateSmtpSettings(Map.of(), null));
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

  private String rawSystemSettingValue(String key) {
    var row = dsl.fetchOne("select value from system_settings where key = ?", key);
    return row == null ? null : row.get(0, String.class);
  }
}
