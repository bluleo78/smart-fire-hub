package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * {@link SmtpMailSenders} 단위 테스트(WD-627). 연결 테스트·리포트 메일이 공유하는 sender 의 호스트·포트·인증·타임아웃을 본다. 모드별 TLS
 * 키는 {@link SmtpTlsPolicyTest} 가 본다.
 */
class SmtpMailSendersTest {

  /** 인증 정보·타임아웃(쓰기 포함 — 예전 연결 테스트에는 없었다)이 들어가고 정책의 TLS 키가 적용된다. */
  @Test
  void create_setsHostPortAuthTimeoutsAndAppliesTls() {
    JavaMailSenderImpl sender =
        SmtpMailSenders.create(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "465",
                "smtp.username", "u",
                "smtp.password", "p"));

    assertThat(sender.getHost()).isEqualTo("smtp.example.com");
    assertThat(sender.getPort()).isEqualTo(465);
    assertThat(sender.getUsername()).isEqualTo("u");
    assertThat(sender.getPassword()).isEqualTo("p");
    assertThat(sender.getJavaMailProperties())
        .containsEntry("mail.transport.protocol", "smtp")
        .containsEntry("mail.smtp.auth", "true")
        .containsEntry("mail.smtp.connectiontimeout", "10000")
        .containsEntry("mail.smtp.timeout", "10000")
        .containsEntry("mail.smtp.writetimeout", "10000")
        // TLS 적용 스모크 — 모드별 키는 SmtpTlsPolicyTest 몫이다.
        .containsEntry("mail.smtp.ssl.enable", "true");
  }

  /** 사용자 이름이 없으면 인증 없는 릴레이(auth=false)이고, 포트가 없으면 587 이다. */
  @Test
  void create_withoutCredentials_disablesAuthAndDefaultsPort() {
    JavaMailSenderImpl sender = SmtpMailSenders.create(Map.of("smtp.host", "relay.internal"));

    assertThat(sender.getPort()).isEqualTo(587);
    assertThat(sender.getUsername()).isNull();
    assertThat(sender.getPassword()).isNull();
    assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false");
  }
}
