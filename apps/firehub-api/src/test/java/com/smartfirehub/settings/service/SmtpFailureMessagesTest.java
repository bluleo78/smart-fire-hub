package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.Map;
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
    MailSendException e = new MailSendException(Map.<Object, Exception>of(new Object(), cause));

    String m = SmtpFailureMessages.describe(e, "smtp.example.com", null, null);

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
      String m = SmtpFailureMessages.describe(e, "smtp.example.com", null, null);
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
            new AuthenticationFailedException("535 5.7.8 auth failed"),
            "smtp.example.com",
            null,
            null);
    assertThat(m)
        .contains("인증에 실패했습니다")
        .contains("사용자 이름·비밀번호")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
  }

  @Test
  void nonNumericPort_advisesFixingPort() {
    assertThat(
            SmtpFailureMessages.describe(
                new NumberFormatException("For input string"), "h", null, null))
        .contains("포트가 숫자가 아닙니다")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
  }

  @Test
  void connectionFailures_adviseHostPort() {
    assertThat(
            SmtpFailureMessages.describe(
                new MessagingException("Couldn't connect", new UnknownHostException("x")),
                "x",
                null,
                null))
        .contains("연결하지 못했습니다");
    assertThat(
            SmtpFailureMessages.describe(
                new MessagingException("Couldn't connect", new ConnectException("refused")),
                "x",
                null,
                null))
        .contains("연결하지 못했습니다");
  }

  /** 분류하지 못한 오류는 메시지가 없으면 일반 문구 + 예외 클래스 이름. 호스트가 없으면 「SMTP 서버」로 쓴다. */
  @Test
  void other_genericMessageWithClassName() {
    String m = SmtpFailureMessages.describe(new IllegalStateException(), null, null, null);
    assertThat(m)
        .contains("메일 발송에 실패했습니다(IllegalStateException)")
        .contains(SmtpFailureMessages.SETTINGS_PLACE);
    assertThat(SmtpFailureMessages.describe(new AuthenticationFailedException("x"), "", null, null))
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
      assertThat(SmtpFailureMessages.describe(e, "smtp.example.com", null, null))
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
                Map.<Object, Exception>of(
                    new Object(),
                    new MessagingException("530 5.7.0 Must issue a STARTTLS command first"))),
            "smtp.example.com",
            null,
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
    String b64 = b64(password);
    MessagingException reply =
        new MessagingException("554 5.7.1 relay access denied\n  for " + password + " / " + b64);

    String m =
        SmtpFailureMessages.describe(
            new MailSendException("Failed messages", reply), "h", null, password);

    assertThat(m)
        .startsWith("메일 발송에 실패했습니다(554 5.7.1 relay access denied for **** / ****)")
        .doesNotContain(password)
        .doesNotContain(b64);

    String longReply = "554 " + "x".repeat(500);
    assertThat(SmtpFailureMessages.describe(new MessagingException(longReply), "h", null, null))
        .contains("x".repeat(SmtpFailureMessages.MAX_DETAIL_LENGTH - 4) + "…")
        .doesNotContain("x".repeat(SmtpFailureMessages.MAX_DETAIL_LENGTH));
  }

  /** 지우기엔 짧은 비밀번호가 조각에 들어 있으면 조각을 버리고 클래스 이름만 쓴다 — 비밀번호는 어떤 경우에도 나가지 않는다. */
  @Test
  void other_shortPasswordInReply_dropsFragment() {
    String m =
        SmtpFailureMessages.describe(
            new MessagingException("535 bad pw abc rejected"), "h", null, "abc");
    assertThat(m).contains("메일 발송에 실패했습니다(MessagingException)").doesNotContain("abc");
  }

  /** 가림 문자열은 끝 4자를 붙이지 않는다 — 4자 비밀번호면 API 키식 가림(****+끝 4자)이 비밀번호 전체를 드러낸다. */
  @Test
  void other_fourCharPassword_maskedWithoutTail() {
    String m =
        SmtpFailureMessages.describe(
            new MessagingException("535 bad pw Ab9z rejected"), "h", null, "Ab9z");
    assertThat(m).contains("535 bad pw **** rejected").doesNotContain("Ab9z");
  }

  /** null 예외도 IndexOutOfBounds 없이 일반 문구다. */
  @Test
  void nullError_returnsGenericMessage() {
    assertThat(SmtpFailureMessages.describe(null, "h", null, null))
        .isEqualTo(
            "메일 발송에 실패했습니다 — " + SmtpFailureMessages.SETTINGS_PLACE + "의 SMTP 설정과 서버 상태를 확인하세요");
  }

  private static SSLHandshakeException handshake(String message, Throwable cause) {
    SSLHandshakeException e = new SSLHandshakeException(message);
    e.initCause(cause);
    return e;
  }

  // ── WD-686: 던지는 예외의 cause 를 가린 사본으로 ─────────────────────────────────────────────

  private static final String PW686 = "Pw686Secret!";

  /** 스택 출력 전체(Caused by·Suppressed 포함) — 로그 warn·error 의 스택에 실리는 문자열과 같다. */
  static String stackOf(Throwable t) {
    StringWriter out = new StringWriter();
    t.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  /** UTF-8 바이트의 Base64 — 서버가 되울리는 AUTH LOGIN·PLAIN 형태를 만든다. */
  static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 가린 사본은 원래 예외 클래스 이름·스택 위치를 지키고, 메시지의 비밀번호(원문·Base64)는 가린다. cause 사슬과 MailSendException 의 메시지별
   * 예외(사본에서는 suppressed)도 같은 방식으로 가린다 — 원래 예외는 어디에도 붙지 않는다.
   */
  @Test
  void maskedCopy_keepsClassAndStackButMasksPasswordAcrossChain() {
    String base64 = b64(PW686);
    MessagingException root =
        new MessagingException("535 auth failed pass=" + PW686 + " b64=" + base64);
    IllegalStateException middle = new IllegalStateException("wrapped " + PW686, root);
    MessagingException perMessage = new MessagingException("554 relay denied " + PW686);
    MailSendException top =
        new MailSendException(
            "send failed", middle, Map.<Object, Exception>of(new Object(), perMessage));

    Throwable copy = SmtpFailureMessages.forCredentials(null, null, PW686).maskedCopy(top);

    String stack = stackOf(copy);
    assertThat(stack)
        .doesNotContain(PW686)
        .doesNotContain(base64)
        .contains("****")
        .contains("org.springframework.mail.MailSendException")
        .contains("java.lang.IllegalStateException: wrapped ****")
        .contains("jakarta.mail.MessagingException: 535 auth failed pass=****")
        .contains("554 relay denied ****");
    // 스택 위치는 원래 예외의 것 그대로다.
    assertThat(copy.getStackTrace()).isEqualTo(top.getStackTrace());
    assertThat(copy.getCause().getStackTrace()).isEqualTo(middle.getStackTrace());
    // 원래 예외 객체는 사슬 어디에도 없다.
    for (Throwable t = copy; t != null; t = t.getCause()) {
      assertThat(t).isNotSameAs(top).isNotSameAs(middle).isNotSameAs(root);
      assertThat(String.valueOf(t.getMessage())).doesNotContain(PW686);
      assertThat(t.toString()).doesNotContain(PW686);
    }
    // 원래 클래스 이름은 메시지 앞에 남는다(로깅 라이브러리와 상관없이 보이게).
    assertThat(copy.getMessage()).startsWith(MailSendException.class.getName() + ": ");
  }

  /** 순환하는 cause 사슬에서도 끝난다(사본도 같은 모양으로 순환한다). null 은 null. */
  @Test
  void maskedCopy_handlesCycleAndNull() {
    RuntimeException a = new RuntimeException("a " + PW686);
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    Throwable copy = SmtpFailureMessages.forCredentials(null, null, PW686).maskedCopy(a);

    assertThat(copy.getMessage()).isEqualTo("java.lang.RuntimeException: a ****");
    assertThat(copy.getCause().getCause()).isSameAs(copy);
    assertThat(stackOf(copy)).doesNotContain(PW686);
    assertThat(SmtpFailureMessages.forCredentials(null, null, PW686).maskedCopy(null)).isNull();
  }

  /** 지우기엔 짧은 비밀번호(다른 단어 속에 묻힘)가 지운 뒤에도 남으면 메시지를 통째로 뺀다 — 비밀번호는 어떤 경우에도 나가지 않는다. */
  @Test
  void maskedCopy_dropsMessageWhenPasswordSurvivesScrub() {
    Throwable copy =
        SmtpFailureMessages.forCredentials(null, null, "cde")
            .maskedCopy(new RuntimeException("server abcdef"));

    assertThat(String.valueOf(copy.getMessage())).doesNotContain("cde");
    assertThat(copy.getMessage()).startsWith("java.lang.RuntimeException");
  }

  /**
   * AUTH PLAIN 자격 증명 덩어리(WD-686) — 서버가 되울린 {@code Base64("\0" + user + "\0" + password)}(authzid
   * 없음)와 {@code Base64(user + "\0" + user + "\0" + password)}(authzid=user)는 비밀번호 원문·Base64 가 아니라
   * 예전 가림에 걸리지 않았다. 가린 사본과 분류 문구 모두에서 지운다.
   */
  @Test
  void authPlainBlobs_areMaskedInCopyAndDescribe() {
    String user = "user";
    String password = "secret123";
    String plain = b64("\0" + user + "\0" + password); // authzid 없음
    String withAuthzid = b64(user + "\0" + user + "\0" + password);
    MessagingException cause =
        new MessagingException(
            "535 5.7.8 Authentication credentials invalid AUTH PLAIN "
                + plain
                + " / "
                + withAuthzid);
    RuntimeException top = new RuntimeException("send failed", cause);

    String stack =
        stackOf(SmtpFailureMessages.forCredentials(null, user, password).maskedCopy(top));
    String described = SmtpFailureMessages.describe(top, "smtp.example.com", user, password);

    assertThat(stack).doesNotContain(plain).doesNotContain(withAuthzid).contains("AUTH PLAIN ****");
    assertThat(described)
        .doesNotContain(plain)
        .doesNotContain(withAuthzid)
        .contains("AUTH PLAIN ****");
  }
}
