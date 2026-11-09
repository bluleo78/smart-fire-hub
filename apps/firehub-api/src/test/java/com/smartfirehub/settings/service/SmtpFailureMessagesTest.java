package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/**
 * {@link SmtpFailureMessages} 단위 테스트(WD-676) — 리포트 메일 발송과 설정 연결 테스트가 같은 한국어 분류 문구를 쓰는지, 원인
 * 사슬(cause· MailSendException 의 메시지별 예외)을 따라 분류하는지 본다.
 */
class SmtpFailureMessagesTest {

  /** STARTTLS 미지원 — 설정 위치에서 STARTTLS 를 끄거나 465 포트를 쓰라고 안내한다. send() 실패는 메시지별 예외 맵에 원인이 있다. */
  @Test
  void starttlsRequired_advisesTurningOffStarttlsOr465() {
    MessagingException cause =
        new MessagingException("STARTTLS is required but host does not support STARTTLS");
    MailSendException e =
        new MailSendException(java.util.Map.<Object, Exception>of(new Object(), cause));

    String m = SmtpFailureMessages.describe(e, "smtp.example.com", null);

    assertThat(m)
        .contains("smtp.example.com")
        .contains("STARTTLS 를 지원하지 않습니다")
        .contains(SmtpFailureMessages.SETTINGS_PLACE)
        .contains("465");
  }

  /**
   * 인증서 불일치 — STARTTLS 를 꺼도 기회적 모드에서 인증서를 확인하므로 「STARTTLS 를 끄라」고 하지 않고 호스트 이름·인증서를 고치라고 한다(Review
   * Focus 6).
   */
  @Test
  void certificateProblems_adviseHostnameOrCertificate_notStarttls() {
    Throwable[] cases = {
      // 실제 PKIX 실패처럼 핸드셰이크 예외의 원인에 CertificateException(ValidatorException 의 상위)이 있다.
      new MessagingException(
          "Could not convert socket to TLS",
          handshake("PKIX path building failed", new CertificateException("PKIX"))),
      new MessagingException(
          "Could not convert socket to TLS",
          handshake(
              "No subject alternative DNS name matching smtp.example.com found",
              new CertificateException(
                  "No subject alternative DNS name matching smtp.example.com found"))),
      new MessagingException("Can't send command", new CertificateException("bad cert")),
      new MessagingException("Can't verify identity of server: smtp.example.com"),
    };
    for (Throwable e : cases) {
      String m = SmtpFailureMessages.describe(e, "smtp.example.com", null);
      assertThat(m)
          .as(e.toString())
          .contains("인증서")
          .contains("호스트를 인증서에 적힌 이름으로")
          .contains(SmtpFailureMessages.SETTINGS_PLACE)
          .doesNotContain("STARTTLS 를 끄");
    }
  }

  @Test
  void authenticationFailed_advisesCredentials() {
    String m =
        SmtpFailureMessages.describe(
            new AuthenticationFailedException("535 5.7.8 auth failed"), "smtp.example.com", null);
    assertThat(m)
        .contains("인증에 실패했습니다")
        .contains("사용자 이름·비밀번호")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
  }

  @Test
  void nonNumericPort_advisesFixingPort() {
    assertThat(
            SmtpFailureMessages.describe(new NumberFormatException("For input string"), "h", null))
        .contains("포트가 숫자가 아닙니다")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
  }

  @Test
  void connectionFailures_adviseHostPort() {
    assertThat(
            SmtpFailureMessages.describe(
                new MessagingException("Couldn't connect", new UnknownHostException("x")),
                "x",
                null))
        .contains("연결하지 못했습니다");
    assertThat(
            SmtpFailureMessages.describe(
                new MessagingException("Couldn't connect", new ConnectException("refused")),
                "x",
                null))
        .contains("연결하지 못했습니다");
  }

  /** 분류하지 못한 오류는 메시지가 없으면 일반 문구 + 예외 클래스 이름. 호스트가 없으면 「SMTP 서버」로 쓴다. */
  @Test
  void other_genericMessageWithClassName() {
    String m = SmtpFailureMessages.describe(new IllegalStateException(), null, null);
    assertThat(m)
        .contains("메일 발송에 실패했습니다(IllegalStateException)")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
    assertThat(SmtpFailureMessages.describe(new AuthenticationFailedException("x"), "", null))
        .startsWith("SMTP 서버 인증에");
  }

  /** 원인에 인증서 문제가 없는 핸드셰이크 실패(TLS 버전·암호 방식, 상대가 끊음)는 인증서가 아니라 TLS 버전·포트 설정을 안내한다. */
  @Test
  void tlsHandshakeWithoutCertificateCause_advisesTlsVersionAndPort() {
    Throwable[] cases = {
      new MessagingException(
          "Could not convert socket to TLS",
          new SSLHandshakeException(
              "No appropriate protocol (protocol is disabled or cipher suites are inappropriate)")),
      new MessagingException(
          "Could not connect", new SSLHandshakeException("Remote host terminated the handshake")),
    };
    for (Throwable e : cases) {
      assertThat(SmtpFailureMessages.describe(e, "smtp.example.com", null))
          .as(e.toString())
          .contains("TLS 연결을 맺지 못했습니다")
          .contains("TLS 버전")
          .contains("포트")
          .doesNotContain("인증서");
    }
  }

  /**
   * 「530 … Must issue a STARTTLS command first」 — 서버가 암호화를 요구한다. STARTTLS 를 켜라고 안내한다(끄라는 말이 아니다).
   */
  @Test
  void serverRequiresStarttls_advisesTurningOn() {
    String m =
        SmtpFailureMessages.describe(
            new MailSendException(
                java.util.Map.<Object, Exception>of(
                    new Object(),
                    new MessagingException("530 5.7.0 Must issue a STARTTLS command first"))),
            "smtp.example.com",
            null);
    assertThat(m)
        .contains("STARTTLS 암호화를 요구합니다")
        .contains("STARTTLS 를 켜")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
  }

  /** 분류하지 못한 서버 응답은 조각을 남긴다(공백 접기·길이 제한). 비밀번호(원문·Base64)는 지운다. */
  @Test
  void other_keepsScrubbedServerReplyFragment() {
    String password = "S3cretPw!";
    String b64 =
        java.util.Base64.getEncoder()
            .encodeToString(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    MessagingException reply =
        new MessagingException("554 5.7.1 relay access denied\n  for " + password + " / " + b64);

    String m =
        SmtpFailureMessages.describe(
            new MailSendException("Failed messages", reply), "h", password);

    assertThat(m)
        .startsWith("메일 발송에 실패했습니다(554 5.7.1 relay access denied for **** / ****)")
        .doesNotContain(password)
        .doesNotContain(b64);

    String longReply = "554 " + "x".repeat(500);
    assertThat(SmtpFailureMessages.describe(new MessagingException(longReply), "h", null))
        .contains("x".repeat(SmtpFailureMessages.MAX_DETAIL_LENGTH - 4) + "…")
        .doesNotContain("x".repeat(SmtpFailureMessages.MAX_DETAIL_LENGTH));
  }

  /** 지우기엔 짧은 비밀번호가 조각에 들어 있으면 조각을 버리고 클래스 이름만 쓴다 — 비밀번호는 어떤 경우에도 나가지 않는다. */
  @Test
  void other_shortPasswordInReply_dropsFragment() {
    String m =
        SmtpFailureMessages.describe(new MessagingException("535 bad pw abc rejected"), "h", "abc");
    assertThat(m).contains("메일 발송에 실패했습니다(MessagingException)").doesNotContain("abc");
  }

  /** 가림 문자열은 끝 4자를 붙이지 않는다 — 4자 비밀번호면 API 키식 가림(****+끝 4자)이 비밀번호 전체를 드러낸다. */
  @Test
  void other_fourCharPassword_maskedWithoutTail() {
    String m =
        SmtpFailureMessages.describe(
            new MessagingException("535 bad pw Ab9z rejected"), "h", "Ab9z");
    assertThat(m).contains("535 bad pw **** rejected").doesNotContain("Ab9z");
  }

  /** null 예외도 IndexOutOfBounds 없이 일반 문구다. */
  @Test
  void nullError_returnsGenericMessage() {
    assertThat(SmtpFailureMessages.describe(null, "h", null))
        .isEqualTo(
            "메일 발송에 실패했습니다 — " + SmtpFailureMessages.SETTINGS_PLACE + "의 SMTP 설정과 서버 상태를 확인하세요");
  }

  private static SSLHandshakeException handshake(String message, Throwable cause) {
    SSLHandshakeException e = new SSLHandshakeException(message);
    e.initCause(cause);
    return e;
  }
}
