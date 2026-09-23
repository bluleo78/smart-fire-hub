package com.smartfirehub.settings.service;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SettingsService SMTP 쓰기·발송 설정 커버리지 테스트. {@code updateSettings}(테넌트 쓰기) →
 * {@code getSmtpConfig}(발송 설정) 왕복과 저장 시 검증(포트 범위·마스크 센티널·빈 비밀번호)을 본다.
 *
 * <p><b>#712 이후 이 클래스가 덮는 것은 테넌트 평면뿐이다.</b> 예전에는 전부
 * {@code updatePlatformSettings}(→ {@code system_settings})로 썼지만, SMTP 가 워크스페이스 전용이
 * 되어 플랫폼 쓰기는 400 으로 거부된다(그 거부는 {@code SmtpSettingsTenantOnlyTest} 가 본다). 검증
 * 규칙 자체는 바뀌지 않았으므로 같은 단언을 테넌트 경로로 옮겼다.
 *
 * <p>매 테스트마다 새 테넌트를 만들고 지운다 — 공유 test DB 의 기본 테넌트에 SMTP 행을 남기면
 * 다른 테스트의 "미설정" 전제가 깨진다.
 */
class SmtpSettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private DSLContext dsl;

  private Long testTenant;

  @BeforeEach
  void useFreshTenant() {
    testTenant = createActiveTenant(dsl, "smtp-svc");
    TenantContext.set(testTenant);
  }

  @AfterEach
  void cleanup() {
    deleteTenants(dsl, testTenant);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
  }

  @Test
  void updateSettings_validKeys_areUsedForSending() {
    settingsService.updateSettings(
        Map.of(
            "smtp.host", "smtp.example.com",
            "smtp.port", "587",
            "smtp.starttls", "true",
            "smtp.from_address", "noreply@example.com"),
        null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.host", "smtp.example.com");
    assertThat(config).containsEntry("smtp.port", "587");
    assertThat(config).containsEntry("smtp.from_address", "noreply@example.com");
  }

  /** 알 수 없는 {@code smtp.*} 키는 테넌트 쓰기 화이트리스트에 없어 거부된다(fail-closed). */
  @Test
  void updateSettings_unknownSmtpKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.bogus_key", "x"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("smtp.bogus_key");
  }

  @Test
  void updateSettings_maskedPassword_skipsUpdate() {
    settingsService.updateSettings(Map.of("smtp.password", "real-smtp-password"), null);
    // 발송 설정에서는 복호화된 평문이다.
    assertThat(settingsService.getSmtpConfig().get("smtp.password")).isEqualTo("real-smtp-password");

    // 화면이 안 고친 비밀번호의 마스크(= maskValue("real-smtp-password") == "****word")를 되돌려 보낸다.
    settingsService.updateSettings(Map.of("smtp.password", "****word"), null);

    assertThat(settingsService.getSmtpConfig().get("smtp.password")).isEqualTo("real-smtp-password");
  }

  @Test
  void getSmtpConfig_emptyPassword_returnsEmptyString() {
    // 인증 없는 릴레이: 빈 비밀번호는 암호화하지 않고, 읽을 때도 복호화하지 않는다.
    settingsService.updateSettings(Map.of("smtp.host", "relay.example.com", "smtp.password", ""), null);

    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.password", "");
  }

  @Test
  void updateSettings_username_updatesSuccessfully() {
    settingsService.updateSettings(Map.of("smtp.username", "smtp-user@example.com"), null);

    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.username", "smtp-user@example.com");
  }

  // --- smtp.port 범위 검증 ---

  @Test
  void updateSettings_portAbove65535_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.port", "99999"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSettings_portZero_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.port", "0"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSettings_portNegative_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.port", "-1"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSettings_portNonNumeric_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.port", "abc"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효하지 않습니다");
  }

  @Test
  void updateSettings_portBoundary_acceptsValidPorts() {
    // 경계값 1과 65535는 유효해야 한다.
    settingsService.updateSettings(Map.of("smtp.port", "1"), null);
    settingsService.updateSettings(Map.of("smtp.port", "65535"), null);
    settingsService.updateSettings(Map.of("smtp.port", "587"), null);

    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.port", "587");
  }
}
