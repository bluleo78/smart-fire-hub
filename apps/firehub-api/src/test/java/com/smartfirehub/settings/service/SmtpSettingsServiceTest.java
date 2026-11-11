package com.smartfirehub.settings.service;

import static com.smartfirehub.support.TenantRlsTestSupport.createActiveTenant;
import static com.smartfirehub.support.TenantRlsTestSupport.deleteTenants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SettingsService SMTP 쓰기·발송 설정 커버리지 테스트. {@code updateSettings}(테넌트 쓰기) → {@code getSmtpConfig}(발송
 * 설정) 왕복과 저장 시 검증(포트 범위·마스크 센티널·빈 비밀번호)을 본다.
 *
 * <p><b>#712 이후 이 클래스가 덮는 것은 테넌트 평면뿐이다.</b> 예전에는 전부 {@code updatePlatformSettings}(→ {@code
 * system_settings})로 썼지만, SMTP 가 워크스페이스 전용이 되어 플랫폼 쓰기는 400 으로 거부된다(그 거부는 {@code
 * SmtpSettingsTenantOnlyTest} 가 본다). 검증 규칙 자체는 바뀌지 않았으므로 같은 단언을 테넌트 경로로 옮겼다.
 *
 * <p>매 테스트마다 새 테넌트를 만들고 지운다 — 공유 test DB 의 기본 테넌트에 SMTP 행을 남기면 다른 테스트의 "미설정" 전제가 깨진다.
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
    assertThat(settingsService.getSmtpConfig().get("smtp.password"))
        .isEqualTo("real-smtp-password");

    // 서버가 내려준 마스크(#725 이후 고정 표식 "****")를 그대로 되돌려 보낸다 — 기존 값 유지.
    settingsService.updateSettings(Map.of("smtp.password", "****"), null);

    assertThat(settingsService.getSmtpConfig().get("smtp.password"))
        .isEqualTo("real-smtp-password");
  }

  /**
   * 인증 없는 릴레이: 빈 비밀번호는 행으로 저장하지 않는다(WD-706 Ruling — SMTP 키는 행 없음 ≡ 빈 칸, Tibero 는 "" 를 거절). 발송 설정에는
   * 키가 없고, 소비자는 키 부재를 빈 값으로 읽는다({@code getOrDefault(.., "")}·{@code isBlank}).
   */
  @Test
  void getSmtpConfig_emptyPassword_readsAsEmpty() {
    settingsService.updateSettings(
        Map.of("smtp.host", "relay.example.com", "smtp.password", ""), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).doesNotContainKey("smtp.password");
    assertThat(config.getOrDefault("smtp.password", "")).isEmpty();
    assertThat(config).containsEntry("smtp.host", "relay.example.com");
  }

  /**
   * 저장된 비밀번호를 빈 값으로 지우면 행이 사라지고, 읽기 결과는 처음부터 비밀번호가 없던 워크스페이스와 같다(발송 설정·화면 목록 모두) — WD-706 Ruling.
   */
  @Test
  void updateSettings_emptySmtpPassword_deletesRow_andReadsAsUnset() {
    settingsService.updateSettings(
        Map.of("smtp.host", "relay.example.com", "smtp.password", "real-smtp-password"), null);
    assertThat(rawValue("smtp.password")).isPresent();

    settingsService.updateSettings(Map.of("smtp.password", ""), null);

    assertThat(rawValue("smtp.password")).isEmpty();
    assertThat(settingsService.getSmtpConfig().getOrDefault("smtp.password", "")).isEmpty();
    // 화면 목록에도 비밀번호 키가 없다 — web 은 없는 키를 빈 값("저장된 비밀번호 없음")으로 읽는다.
    assertThat(settingsService.getResolvedByPrefix("smtp"))
        .extracting(r -> r.key())
        .doesNotContain("smtp.password")
        .contains("smtp.host");
    // 다른 키는 그대로다.
    assertThat(rawValue("smtp.host")).contains("relay.example.com");
  }

  /** 현재 테넌트의 저장 행 원문(없으면 빈 값). */
  private java.util.Optional<String> rawValue(String key) {
    return com.smartfirehub.support.SettingsTestSupport.rawTenantSettingValue(
        dsl, fixtureTransactionTemplate, testTenant, key);
  }

  @Test
  void updateSettings_username_updatesSuccessfully() {
    settingsService.updateSettings(Map.of("smtp.username", "smtp-user@example.com"), null);

    assertThat(settingsService.getSmtpConfig())
        .containsEntry("smtp.username", "smtp-user@example.com");
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

  // --- smtp.starttls 값 검증 (WD-537) ---

  /**
   * {@code smtp.starttls} 는 정확히 소문자 {@code true}/{@code false} 만 받는다. 연결 테스트·{@code
   * EmailDeliveryChannel} 은 {@code "true"}(대소문자 무시)일 때만 STARTTLS 를 켜므로, {@code yes} 같은 값은 경고 없이 평문
   * SMTP 인증으로 떨어진다. 거부는 저장 전에 일어나 행이 생기지 않아야 한다.
   */
  @ParameterizedTest(name = "smtp.starttls = [{0}] 은 거부된다")
  @CsvSource(
      ignoreLeadingAndTrailingWhitespace = false,
      value = {"yes", "maybe", "''", "TRUE", "' true'", "'false '", "1"})
  void updateSettings_starttlsNotTrueOrFalse_throwsIllegalArgument(String value) {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("smtp.starttls", value), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("STARTTLS 사용 값은 true 또는 false 여야 합니다. 입력값: " + value);

    assertThat(settingsService.getSmtpConfig()).doesNotContainKey("smtp.starttls");
  }

  /** 웹 스위치가 보내는 두 값은 그대로 저장된다. */
  @ParameterizedTest(name = "smtp.starttls = [{0}] 은 저장된다")
  @ValueSource(strings = {"true", "false"})
  void updateSettings_starttlsTrueOrFalse_isStored(String value) {
    assertThatCode(() -> settingsService.updateSettings(Map.of("smtp.starttls", value), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig()).containsEntry("smtp.starttls", value);
  }

  /** null 값은 NPE(500) 가 아니라 IllegalArgumentException(400) 으로 거부된다. */
  @Test
  void updateSettings_starttlsNull_throwsIllegalArgument() {
    Map<String, String> body = new HashMap<>();
    body.put("smtp.starttls", null);
    assertThatThrownBy(() -> settingsService.updateSettings(body, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("smtp.starttls");
  }

  /** 키가 없는 부분 PUT 은 STARTTLS 검증의 영향을 받지 않는다 — "키 없음 = 손대지 않음". */
  @Test
  void updateSettings_withoutStarttlsKey_isUnaffected() {
    assertThatCode(
            () -> settingsService.updateSettings(Map.of("smtp.host", "smtp.example.com"), null))
        .doesNotThrowAnyException();
    assertThat(settingsService.getSmtpConfig())
        .containsEntry("smtp.host", "smtp.example.com")
        .doesNotContainKey("smtp.starttls");
  }

  /** 설정 해제는 updateSettings 를 지나지 않으므로 STARTTLS 검증과 무관하게 저장된 값을 지운다. */
  @Test
  void clearSmtpSettings_afterStarttlsSaved_removesIt() {
    settingsService.updateSettings(Map.of("smtp.starttls", "false"), null);
    settingsService.clearSmtpSettings(null);
    assertThat(settingsService.getSmtpConfig()).doesNotContainKey("smtp.starttls");
  }
}
