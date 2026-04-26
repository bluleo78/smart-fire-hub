package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EmailChannel 경계 경로 단위 검증.
 *
 * <p>JavaMailSender 대신 ChannelHttpClient를 사용하는 새 구현 검증. 사전 검증(SMTP 호스트 미설정, 수신자 미확보) 경로와 정상 발송 경로
 * 확인.
 */
@ExtendWith(MockitoExtension.class)
class EmailChannelTest {

  @Mock private SettingsService settingsService;
  @Mock private UserRepository userRepository;
  @Mock private ChannelHttpClient channelHttpClient;

  @InjectMocks private EmailChannel channel;

  @Test
  void deliver_smtpHostMissing_returnsPermanentFailure() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

    var result = channel.deliver(ctx(1L, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.PermanentFailure.class,
            pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE));
  }

  @Test
  void deliver_missingRecipient_returnsPermanentFailure() {
    when(settingsService.getSmtpConfig()).thenReturn(smtpConfig());
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    var result = channel.deliver(ctx(1L, null));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.PermanentFailure.class,
            pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.RECIPIENT_INVALID));
  }

  /** 정상 발송: channelHttpClient.send("EMAIL", ...) 호출 → Sent 반환. */
  @Test
  void deliver_success_callsChannelHttpClientAndReturnsSent() {
    when(settingsService.getSmtpConfig()).thenReturn(smtpConfig());

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.Sent.class,
            sent -> assertThat(sent.externalMessageId()).startsWith("email-"));
    verify(channelHttpClient).send(eq("EMAIL"), any(Map.class), any(Map.class));
  }

  /** channelHttpClient.send 5xx → TransientFailure 반환. */
  @Test
  void deliver_channelHttp5xx_returnsTransientFailure() {
    when(settingsService.getSmtpConfig()).thenReturn(smtpConfig());
    doThrow(new ChannelHttpException("upstream_error", 500))
        .when(channelHttpClient)
        .send(anyString(), any(), any());

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
  }

  // ----------------------------------------------------------------
  // 헬퍼
  // ----------------------------------------------------------------

  private DeliveryContext ctx(Long userId, String address) {
    Payload p =
        new Payload(
            Payload.PayloadType.STANDARD,
            "제목",
            "요약",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of());
    return new DeliveryContext(1L, UUID.randomUUID(), userId, address, Optional.empty(), p);
  }

  private Map<String, String> smtpConfig() {
    return Map.of(
        "smtp.host", "smtp.example.com",
        "smtp.port", "587",
        "smtp.username", "user@example.com",
        "smtp.password", "secret",
        "smtp.starttls", "true");
  }
}
