package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettingsService SMTP 관련 메서드 커버리지 보강 테스트. getSmtpSettings / updateSmtpSettings / getSmtpConfig /
 * 유효성 검증을 검증한다.
 *
 * <p><b>P7-b Task 5 이후 이 클래스 전체가 플랫폼 평면 호출을 재현한다.</b> {@code updateSmtpSettings}
 * 는 이제 {@code TenantContext} 가 있으면(테넌트 평면) 즉시 거부한다 — SMTP 는 발신 도메인 신뢰도를
 * 전 테넌트가 공유하는 완전한 플랫폼 잠금이기 때문이다. 이 파일이 검증하는 것(화이트리스트·포트
 * 범위·마스킹·암호화)은 여전히 유효한 로직이지만, 이제는 플랫폼 관리자가 부르는 경로에서만
 * 발생하므로 {@link #clearTenantContextForPlatformSmtpCalls} 로 기본 테넌트 컨텍스트를 지운다.
 */
@Transactional
class SmtpSettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;

  /**
   * {@link IntegrationTestBase} 가 세운 기본 테넌트 컨텍스트를 지운다. 서브클래스 {@code @BeforeEach}
   * 는 JUnit5 규약상 상위 클래스 것보다 나중에 실행되므로, 여기서 지우는 것이 마지막 상태가 된다.
   */
  @BeforeEach
  void clearTenantContextForPlatformSmtpCalls() {
    TenantContext.clear();
  }

  @Test
  void getSmtpSettings_returnsSmtpKeys() {
    List<SettingResponse> settings = settingsService.getSmtpSettings();

    assertThat(settings).isNotEmpty();
    assertThat(settings).allSatisfy(s -> assertThat(s.key()).startsWith("smtp."));
  }

  @Test
  void updateSmtpSettings_validKeys_updatesSuccessfully() {
    Map<String, String> update =
        Map.of(
            "smtp.host", "smtp.example.com",
            "smtp.port", "587",
            "smtp.starttls", "true",
            "smtp.from_address", "noreply@example.com");

    // should not throw
    settingsService.updateSmtpSettings(update, null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.host", "smtp.example.com");
    assertThat(config).containsEntry("smtp.port", "587");
    assertThat(config).containsEntry("smtp.from_address", "noreply@example.com");
  }

  /**
   * {@code getAll} 도 {@code smtp.password} 를 마스킹한다.
   *
   * <p>운영자 평면의 {@code GET /api/platform/settings} 가 쓰는 경로다. 마스킹 판정이
   * {@code getSmtpSettings} 안에만 있었을 때 이 경로는 <b>AES 암호문을 그대로</b> 내보냈다 —
   * 비밀 키 목록을 두 곳에서 관리한 결과다. 이제 두 메서드가 같은 함수를 지난다.
   */
  @Test
  void getAll_masksSmtpPassword() {
    settingsService.updateSmtpSettings(Map.of("smtp.password", "secret-smtp-pass"), null);

    assertThat(settingsService.getAll())
        .filteredOn(s -> "smtp.password".equals(s.key()))
        .hasSize(1)
        .first()
        .satisfies(
            s -> {
              // 마스킹 형태이고, 평문도 암호문(iv:ciphertext Base64)도 아니다.
              assertThat(s.value()).startsWith("****");
              assertThat(s.value()).doesNotContain("secret-smtp-pass");
              assertThat(s.value()).doesNotContain(":");
            });
  }

  @Test
  void updateSmtpSettings_invalidKey_throwsIllegalArgument() {
    Map<String, String> update = Map.of("ai.model", "gpt-4");

    assertThatThrownBy(() -> settingsService.updateSmtpSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않는 SMTP 설정 키");
  }

  @Test
  void updateSmtpSettings_password_encryptsBeforeStore() {
    Map<String, String> update = Map.of("smtp.password", "secret-smtp-pass");

    settingsService.updateSmtpSettings(update, null);

    // getSmtpSettings should mask the password
    List<SettingResponse> settings = settingsService.getSmtpSettings();
    assertThat(settings)
        .filteredOn(s -> "smtp.password".equals(s.key()))
        .hasSize(1)
        .first()
        .satisfies(s -> assertThat(s.value()).startsWith("****"));
  }

  @Test
  void updateSmtpSettings_maskedPassword_skipsUpdate() {
    // Store a real password first
    settingsService.updateSmtpSettings(Map.of("smtp.password", "real-smtp-password"), null);

    // Read encrypted value
    Map<String, String> config1 = settingsService.getSmtpConfig();
    // Password should be decrypted in getSmtpConfig
    assertThat(config1.get("smtp.password")).isEqualTo("real-smtp-password");

    // Now send a masked password (frontend behavior when unchanged)
    settingsService.updateSmtpSettings(Map.of("smtp.password", "****masked"), null);

    // Password should remain unchanged
    Map<String, String> config2 = settingsService.getSmtpConfig();
    assertThat(config2.get("smtp.password")).isEqualTo("real-smtp-password");
  }

  @Test
  void getSmtpConfig_noPassword_returnsEmptyString() {
    // Clear password by storing empty string
    settingsService.updateSmtpSettings(Map.of("smtp.password", ""), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    // Password entry exists but value is empty
    assertThat(config.get("smtp.password")).isIn("", null);
  }

  @Test
  void updateSmtpSettings_username_updatesSuccessfully() {
    settingsService.updateSmtpSettings(Map.of("smtp.username", "smtp-user@example.com"), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.username", "smtp-user@example.com");
  }

  // --- smtp.port 범위 검증 테스트 ---

  @Test
  void updateSmtpSettings_portAbove65535_throwsIllegalArgument() {
    // 99999는 유효 범위(1-65535) 초과 — 서버가 거부해야 한다
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.port", "99999"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portZero_throwsIllegalArgument() {
    // 0은 포트 하한(1) 미만
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.port", "0"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portNegative_throwsIllegalArgument() {
    // 음수 포트 번호
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.port", "-1"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portNonNumeric_throwsIllegalArgument() {
    // 숫자가 아닌 포트 값
    assertThatThrownBy(() -> settingsService.updateSmtpSettings(Map.of("smtp.port", "abc"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효하지 않습니다");
  }

  @Test
  void updateSmtpSettings_portBoundary_acceptsValidPorts() {
    // 경계값 1과 65535는 유효해야 한다
    settingsService.updateSmtpSettings(Map.of("smtp.port", "1"), null);
    settingsService.updateSmtpSettings(Map.of("smtp.port", "65535"), null);
    settingsService.updateSmtpSettings(Map.of("smtp.port", "587"), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.port", "587");
  }
}
