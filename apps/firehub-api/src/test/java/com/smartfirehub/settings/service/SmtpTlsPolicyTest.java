package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.service.SmtpTlsPolicy.Mode;
import com.smartfirehub.settings.service.SmtpTlsPolicy.NodemailerFlags;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SmtpTlsPolicy} 단위 테스트(WD-627).
 *
 * <p>알림 메일·연결 테스트·리포트 메일 세 경로가 이 해석 하나를 쓰므로, (port, starttls) → 모드 표와 JavaMail 속성·nodemailer 플래그를
 * 여기서 고정한다.
 */
class SmtpTlsPolicyTest {

  /** "<absent>" 는 그 키를 맵에 넣지 않고, "<null>" 은 키를 null 값으로 넣는다는 뜻이다. */
  private static final String ABSENT = "<absent>";

  private static final String NULL = "<null>";

  /** 포트 465 는 STARTTLS 값(켜짐·꺼짐·키 없음)과 무관하게 암시적 TLS 다 — 465 는 관례상 SMTPS 전용 포트다(WD-593). */
  @ParameterizedTest
  @ValueSource(strings = {"true", "false", ABSENT})
  void port465_isImplicitTlsRegardlessOfStartTls(String startTls) {
    SmtpTlsPolicy policy = SmtpTlsPolicy.from(smtp("465", startTls));

    assertThat(policy.port()).isEqualTo(465);
    assertThat(policy.mode()).isEqualTo(Mode.IMPLICIT_TLS);
  }

  /**
   * 465 가 아닌 포트(587·25) + STARTTLS 켜짐(키 없음, 값 null, 옛 행의 대문자 포함)은 STARTTLS 강제다 — 저장하지 않은 보안 토글이
   * 꺼짐으로 읽혀 강제가 풀리면 안 된다.
   */
  @ParameterizedTest
  @CsvSource({"587,true", "587,TRUE", "587," + ABSENT, "587," + NULL, "25,true"})
  void nonImplicitPort_startTlsOnAbsentOrNull_isStartTlsRequired(String port, String startTls) {
    SmtpTlsPolicy policy = SmtpTlsPolicy.from(smtp(port, startTls));

    assertThat(policy.port()).isEqualTo(Integer.parseInt(port));
    assertThat(policy.mode()).isEqualTo(Mode.STARTTLS_REQUIRED);
  }

  /** 465 가 아닌 포트 + STARTTLS 꺼짐은 기회적 STARTTLS 다 — 평문 강제가 아니다. */
  @ParameterizedTest
  @ValueSource(strings = {"587", "25"})
  void nonImplicitPort_startTlsOff_isOpportunistic(String port) {
    assertThat(SmtpTlsPolicy.from(smtp(port, "false")).mode())
        .isEqualTo(Mode.STARTTLS_OPPORTUNISTIC);
  }

  /** 포트가 비었거나 키가 없거나 null 이면 587 로 본다 — 검증 이전의 옛 행에서 Integer.parseInt("") 예외가 나지 않게 한다. */
  @ParameterizedTest
  @ValueSource(strings = {"", "  ", ABSENT, NULL})
  void blankOrMissingPort_defaultsTo587(String port) {
    SmtpTlsPolicy policy = SmtpTlsPolicy.from(smtp(port, "true"));

    assertThat(policy.port()).isEqualTo(587);
    assertThat(policy.mode()).isEqualTo(Mode.STARTTLS_REQUIRED);
  }

  /** 숫자가 아닌 포트는 NumberFormatException 이다 — 호출부가 자기 실패 경로(영구 실패·실패 응답)로 옮긴다. */
  @Test
  void nonNumericPort_throwsNumberFormatException() {
    assertThatThrownBy(() -> SmtpTlsPolicy.from(smtp("smtp", "true")))
        .isInstanceOf(NumberFormatException.class);
  }

  /** 암시적 TLS 는 ssl.enable 로 접속 즉시 TLS 를 걸고 인증서 호스트 이름을 확인한다. STARTTLS 키는 넣지 않는다. */
  @Test
  void applyTo_implicitTls_setsSslEnableAndServerIdentityCheck() {
    Properties props = new Properties();
    SmtpTlsPolicy.from(smtp("465", "true")).applyTo(props);

    assertThat(props)
        .containsEntry("mail.smtp.ssl.enable", "true")
        .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
        .doesNotContainKey("mail.smtp.starttls.enable")
        .doesNotContainKey("mail.smtp.starttls.required");
  }

  /**
   * STARTTLS 강제는 enable 과 required 를 함께 넣는다 — required 가 없으면 서버가 STARTTLS 를 광고하지 않을 때 JavaMail 이
   * 평문으로 조용히 진행한다(예전 연결 테스트·리포트 메일의 다운그레이드 버그).
   */
  @Test
  void applyTo_startTlsRequired_setsEnableAndRequired() {
    Properties props = new Properties();
    SmtpTlsPolicy.from(smtp("587", "true")).applyTo(props);

    assertThat(props)
        .containsEntry("mail.smtp.starttls.enable", "true")
        .containsEntry("mail.smtp.starttls.required", "true")
        .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
        .doesNotContainKey("mail.smtp.ssl.enable");
  }

  /** 기회적 STARTTLS 는 enable 만 넣고 required 는 넣지 않는다 — 서버가 광고하면 승격, 아니면 평문. 승격 시 인증서 확인은 같다. */
  @Test
  void applyTo_opportunistic_setsEnableWithoutRequired() {
    Properties props = new Properties();
    SmtpTlsPolicy.from(smtp("587", "false")).applyTo(props);

    assertThat(props)
        .containsEntry("mail.smtp.starttls.enable", "true")
        .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
        .doesNotContainKey("mail.smtp.starttls.required")
        .doesNotContainKey("mail.smtp.ssl.enable");
  }

  /**
   * nodemailer 플래그: 465 → secure, STARTTLS 강제 → requireTLS, 기회적 → 둘 다 false(nodemailer 가 광고 시 스스로
   * 승격).
   */
  @Test
  void nodemailerFlags_mapEachMode() {
    assertThat(SmtpTlsPolicy.from(smtp("465", "false")).nodemailerFlags())
        .isEqualTo(new NodemailerFlags(true, false));
    assertThat(SmtpTlsPolicy.from(smtp("587", "true")).nodemailerFlags())
        .isEqualTo(new NodemailerFlags(false, true));
    assertThat(SmtpTlsPolicy.from(smtp("587", "false")).nodemailerFlags())
        .isEqualTo(new NodemailerFlags(false, false));
  }

  private static Map<String, String> smtp(String port, String startTls) {
    Map<String, String> map = new HashMap<>();
    map.put("smtp.host", "smtp.example.com");
    put(map, "smtp.port", port);
    put(map, "smtp.starttls", startTls);
    return map;
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (ABSENT.equals(value)) return;
    map.put(key, NULL.equals(value) ? null : value);
  }
}
