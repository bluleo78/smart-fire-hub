package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettingsService SMTP 관련 메서드 커버리지 보강 테스트. updatePlatformSettings / getSmtpConfig /
 * 유효성 검증을 검증한다.
 *
 * <p><b>이 클래스가 덮는 것은 플랫폼 평면이다.</b> 쓰기 호출은 전부
 * {@code updatePlatformSettings}(→ {@code system_settings})를 쓴다.
 *
 * <p>P7-c1(2026-08-22) 이전 이 자리에는 "SMTP 6키는 발신 도메인 신뢰도를 전 테넌트가 공유하므로
 * 완전한 플랫폼 잠금이고, 따라서 검증 대상 로직(화이트리스트·포트 범위·마스킹·암호화)에 도달하는
 * <b>유일한</b> 경로는 {@code updatePlatformSettings}"라고 적혀 있었다. <b>Task 1 이 6키를 테넌트
 * 오버라이드로 재분류하면서 그 '유일한 경로'가 둘이 됐다</b> — 테넌트 평면
 * ({@code updateSettings} → {@code tenant_settings})이 같은 검증·센티널·암호화 로직
 * ({@code normalizeSmtpWrite})을 공유한다. 그쪽 계약은
 * {@code SettingsWritePlaneTest} 가 덮는다(암호화·마스킹·센티널·포트 3건). 여기의 녹색은
 * "공유 추출이 플랫폼 동작을 바꾸지 않았다"는 증거로 읽으면 된다.
 *
 * <p>기본 테넌트 컨텍스트를 지우는 것({@link #clearTenantContextForPlatformSmtpCalls})은 이제
 * <b>허용을 얻기 위한 조건이 아니다</b> — 운영자 요청이 테넌트 컨텍스트 없이 도착한다는 사실을
 * 재현하는 것뿐이고, 거부 판정이 컨텍스트에 다시 의존하기 시작하면 그 회귀는
 * 그 회귀는 이제 SettingsControllerTest 의 라우트 부재(405) 단언이 대신 잡는다.
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

  // getSmtpSettings_returnsSmtpKeys 는 삭제했다 — 그 메서드(와 GET /settings/smtp 라우트)가
  // P7-c1 에서 사라졌다. "smtp 프리픽스로 조회하면 smtp 키만 온다"는 주장은 {@code getByPrefix}
  // 의 성질이고 SettingsServiceTest 가 ai/embedding 프리픽스로 이미 지킨다.

  @Test
  void updateSmtpSettings_validKeys_updatesSuccessfully() {
    Map<String, String> update =
        Map.of(
            "smtp.host", "smtp.example.com",
            "smtp.port", "587",
            "smtp.starttls", "true",
            "smtp.from_address", "noreply@example.com");

    // should not throw
    settingsService.updatePlatformSettings(update, null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.host", "smtp.example.com");
    assertThat(config).containsEntry("smtp.port", "587");
    assertThat(config).containsEntry("smtp.from_address", "noreply@example.com");
  }

  /**
   * {@code getAll} 도 {@code smtp.password} 를 마스킹한다.
   *
   * <p>운영자 평면의 {@code GET /api/platform/settings} 가 쓰는 경로다. 마스킹 판정이
   * SMTP 전용 읽기 메서드 안에만 있었을 때 이 경로는 <b>AES 암호문을 그대로</b> 내보냈다 —
   * 비밀 키 목록을 두 곳에서 관리한 결과다. 이제 두 메서드가 같은 함수를 지난다.
   */
  @Test
  void getAll_masksSmtpPassword() {
    settingsService.updatePlatformSettings(Map.of("smtp.password", "secret-smtp-pass"), null);

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

  /**
   * 알 수 없는 SMTP 키는 거부된다.
   *
   * <p>P7-b 이전에는 {@code ai.model} 을 SMTP 경로에 넣어 "SMTP 키가 아니다"를 확인했다. 지금은
   * 두 그룹이 {@link SettingsService#updatePlatformSettings} 한 입구를 공유하므로 {@code ai.model} 은
   * <b>정당한 플랫폼 키</b>다 — 그 값으로는 아무것도 검증되지 않는다. 그래서 어느 그룹에도 속하지
   * 않는 키로 바꿨다.
   */
  @Test
  void updatePlatformSettings_unknownKey_throwsIllegalArgument() {
    Map<String, String> update = Map.of("smtp.bogus_key", "x");

    assertThatThrownBy(() -> settingsService.updatePlatformSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않는 설정 키");
  }

  // 테넌트 평면 SMTP 쓰기 진입점 테스트는 삭제했다 — 그 진입점(서비스 메서드 + PUT /smtp 라우트)이
  // 이제 존재하지 않는다. 거부가 런타임 예외에서 구조로 바뀌었으므로 "거부되는가"를 물을 대상 자체가
  // 없다. 라우트 부재는 SettingsControllerTest 가 405 로 지킨다.



  // updateSmtpSettings_password_encryptsBeforeStore 는 삭제했다. 이 테스트가 하던 주장은
  // "플랫폼에 저장한 비밀번호가 읽기 응답에서 마스킹된다" 하나였고, 관측 창구가 지금 없는
  // getSmtpSettings 였다. 같은 주장을 <b>더 엄격하게</b>(평문도 암호문도 아님까지) 하는
  // getAll_masksSmtpPassword 가 바로 위에 있으므로 창구만 바꿔 옮기면 중복이 된다.

  @Test
  void updateSmtpSettings_maskedPassword_skipsUpdate() {
    // Store a real password first
    settingsService.updatePlatformSettings(Map.of("smtp.password", "real-smtp-password"), null);

    // Read encrypted value
    Map<String, String> config1 = settingsService.getSmtpConfig();
    // Password should be decrypted in getSmtpConfig
    assertThat(config1.get("smtp.password")).isEqualTo("real-smtp-password");

    // Now send a masked password (frontend behavior when unchanged)
    settingsService.updatePlatformSettings(Map.of("smtp.password", "****masked"), null);

    // Password should remain unchanged
    Map<String, String> config2 = settingsService.getSmtpConfig();
    assertThat(config2.get("smtp.password")).isEqualTo("real-smtp-password");
  }

  @Test
  void getSmtpConfig_noPassword_returnsEmptyString() {
    // Clear password by storing empty string
    settingsService.updatePlatformSettings(Map.of("smtp.password", ""), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    // Password entry exists but value is empty
    assertThat(config.get("smtp.password")).isIn("", null);
  }

  @Test
  void updateSmtpSettings_username_updatesSuccessfully() {
    settingsService.updatePlatformSettings(Map.of("smtp.username", "smtp-user@example.com"), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.username", "smtp-user@example.com");
  }

  // --- smtp.port 범위 검증 테스트 ---

  @Test
  void updateSmtpSettings_portAbove65535_throwsIllegalArgument() {
    // 99999는 유효 범위(1-65535) 초과 — 서버가 거부해야 한다
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of("smtp.port", "99999"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portZero_throwsIllegalArgument() {
    // 0은 포트 하한(1) 미만
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of("smtp.port", "0"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portNegative_throwsIllegalArgument() {
    // 음수 포트 번호
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of("smtp.port", "-1"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("65535");
  }

  @Test
  void updateSmtpSettings_portNonNumeric_throwsIllegalArgument() {
    // 숫자가 아닌 포트 값
    assertThatThrownBy(() -> settingsService.updatePlatformSettings(Map.of("smtp.port", "abc"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효하지 않습니다");
  }

  @Test
  void updateSmtpSettings_portBoundary_acceptsValidPorts() {
    // 경계값 1과 65535는 유효해야 한다
    settingsService.updatePlatformSettings(Map.of("smtp.port", "1"), null);
    settingsService.updatePlatformSettings(Map.of("smtp.port", "65535"), null);
    settingsService.updatePlatformSettings(Map.of("smtp.port", "587"), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.port", "587");
  }
}
