package com.smartfirehub.settings.service;

import java.util.Map;
import java.util.Properties;

/**
 * 워크스페이스 SMTP 설정({@code smtp.port}·{@code smtp.starttls})을 TLS 접속 방식으로 해석하는 단일 출처(WD-627). 알림
 * 메일(nodemailer, {@link #nodemailerFlags()})·연결 테스트·리포트 메일(JavaMail, {@link
 * SmtpMailSenders#create})이 모두 이 해석을 써서 같은 설정에서 같은 TLS 동작을 낸다.
 *
 * <table>
 *   <caption>해석 규칙</caption>
 *   <tr><th>포트</th><th>smtp.starttls</th><th>모드</th><th>JavaMail</th><th>nodemailer</th></tr>
 *   <tr><td>465</td><td>무관</td><td>{@link Mode#IMPLICIT_TLS}</td>
 *       <td>ssl.enable</td><td>secure</td></tr>
 *   <tr><td>그 밖</td><td>켜짐(키 없음·null 포함, 대소문자 무시)</td><td>{@link Mode#STARTTLS_REQUIRED}</td>
 *       <td>starttls.enable + required</td><td>requireTLS</td></tr>
 *   <tr><td>그 밖</td><td>그 밖 값(false 등)</td><td>{@link Mode#STARTTLS_OPPORTUNISTIC}</td>
 *       <td>starttls.enable</td><td>(기본 — 광고 시 승격)</td></tr>
 * </table>
 *
 * <p>TLS 를 쓰는 모든 모드에서 JavaMail 은 {@code ssl.checkserveridentity=true} 로 인증서 호스트 이름을 확인한다(nodemailer
 * 기본과 같음). 승격 후 TLS 핸드셰이크가 실패하면 평문으로 되돌아가지 않고 발송이 실패한다.
 *
 * @param port 접속 포트. 키가 없거나 비어 있으면 {@value #DEFAULT_PORT}
 * @param mode TLS 방식
 */
public record SmtpTlsPolicy(int port, Mode mode) {

  /** 포트 미설정 시 기본값(제출 포트). */
  public static final int DEFAULT_PORT = 587;

  /** 관례상 암시적 TLS(SMTPS) 전용 포트 — STARTTLS 값과 무관하다(WD-593). */
  public static final int IMPLICIT_TLS_PORT = 465;

  /** SMTP 접속의 TLS 방식. */
  public enum Mode {
    /** 접속 즉시 TLS(포트 465). */
    IMPLICIT_TLS,
    /** STARTTLS 승격 강제 — 서버가 지원하지 않으면 평문으로 내려가지 않고 실패한다(STARTTLS 켜짐). */
    STARTTLS_REQUIRED,
    /**
     * 기회적 STARTTLS — 서버가 광고하면 승격, 아니면 평문(STARTTLS 꺼짐). 평문 강제가 아니다: AUTH 전 STARTTLS 를 요구하는 서버도 동작한다.
     */
    STARTTLS_OPPORTUNISTIC
  }

  /**
   * firehub-channel 로 넘기는 nodemailer smtpConfig 의 TLS 플래그.
   *
   * @param secure 접속 즉시 TLS
   * @param requireTls STARTTLS 승격 강제
   */
  public record NodemailerFlags(boolean secure, boolean requireTls) {}

  /**
   * SMTP 설정 맵({@code SettingsService#getSmtpConfig()})에서 정책을 만든다. starttls 가 없거나 null 이면 켜짐이다 —
   * 저장하지 않은 보안 토글이 꺼짐으로 읽히면 안 된다.
   *
   * @throws NumberFormatException 포트가 숫자가 아닐 때(검증 이전의 옛 행). 호출부가 자기 실패 경로로 옮긴다.
   */
  public static SmtpTlsPolicy from(Map<String, String> smtp) {
    String portStr = smtp.get("smtp.port");
    int port = portStr == null || portStr.isBlank() ? DEFAULT_PORT : Integer.parseInt(portStr);
    if (port == IMPLICIT_TLS_PORT) {
      return new SmtpTlsPolicy(port, Mode.IMPLICIT_TLS);
    }
    // getOrDefault 는 값이 null 인 키에 기본값을 주지 않으므로 get 으로 읽어 null 을 켜짐으로 본다.
    String startTls = smtp.get("smtp.starttls");
    boolean required = startTls == null || "true".equalsIgnoreCase(startTls);
    return new SmtpTlsPolicy(port, required ? Mode.STARTTLS_REQUIRED : Mode.STARTTLS_OPPORTUNISTIC);
  }

  /** JavaMail(Angus Mail) 접속 속성에 이 정책의 TLS 키를 넣는다(클래스 표의 JavaMail 열). */
  public void applyTo(Properties props) {
    switch (mode) {
      case IMPLICIT_TLS -> props.put("mail.smtp.ssl.enable", "true");
      case STARTTLS_REQUIRED -> {
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
      }
      case STARTTLS_OPPORTUNISTIC -> props.put("mail.smtp.starttls.enable", "true");
    }
    props.put("mail.smtp.ssl.checkserveridentity", "true");
  }

  /** nodemailer TLS 플래그로 옮긴다(클래스 표의 nodemailer 열). 모드가 늘면 컴파일 오류로 드러나도록 switch 식을 쓴다. */
  public NodemailerFlags nodemailerFlags() {
    return switch (mode) {
      case IMPLICIT_TLS -> new NodemailerFlags(true, false);
      case STARTTLS_REQUIRED -> new NodemailerFlags(false, true);
      case STARTTLS_OPPORTUNISTIC -> new NodemailerFlags(false, false);
    };
  }
}
