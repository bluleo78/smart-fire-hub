package com.smartfirehub.settings.service;

import java.util.Map;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 워크스페이스 SMTP 설정으로 JavaMail sender 를 만드는 단일 팩토리(WD-627).
 *
 * <p>SMTP 연결 테스트({@code SettingsController})와 리포트 메일({@code EmailDeliveryChannel})이 같은 sender 를 쓴다.
 * 예전에는 두 곳이 같은 코드를 따로 갖고 있다가 어긋났다(연결 테스트에만 쓰기 타임아웃이 없었고, TLS 키도 각자 넣었다). 연결 테스트는 실제 발송과 <b>같은</b>
 * 설정으로 접속해야 「테스트는 성공하는데 발송은 실패」가 생기지 않는다.
 */
public final class SmtpMailSenders {

  /** 접속·읽기·쓰기 타임아웃(ms). 느린 SMTP 서버가 발송 스레드·연결 테스트 요청을 오래 붙잡지 않게 한다. */
  static final String TIMEOUT_MS = "10000";

  private SmtpMailSenders() {}

  /**
   * 설정 맵으로 sender 를 만든다. 호스트·포트·인증·타임아웃을 넣고 TLS 키는 {@link SmtpTlsPolicy#applyTo} 가 넣는다.
   *
   * @param smtp {@code SettingsService#getSmtpConfig()} 결과
   * @throws NumberFormatException 포트가 숫자가 아닐 때({@link SmtpTlsPolicy#from})
   */
  public static JavaMailSenderImpl create(Map<String, String> smtp) {
    SmtpTlsPolicy tls = SmtpTlsPolicy.from(smtp);

    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(smtp.get("smtp.host"));
    sender.setPort(tls.port());

    String username = smtp.get("smtp.username");
    if (username != null && !username.isBlank()) {
      sender.setUsername(username);
    }
    String password = smtp.get("smtp.password");
    if (password != null && !password.isBlank()) {
      sender.setPassword(password);
    }

    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    // 사용자 이름이 없으면 인증 없는 릴레이로 본다.
    props.put("mail.smtp.auth", sender.getUsername() != null ? "true" : "false");
    props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
    props.put("mail.smtp.timeout", TIMEOUT_MS);
    props.put("mail.smtp.writetimeout", TIMEOUT_MS);
    tls.applyTo(props);
    return sender;
  }
}
