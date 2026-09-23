package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

  /**
   * 워크스페이스 SMTP 미설정(#712: getSmtpConfig 가 빈 맵)이면 영구 실패이고, 사유가 등록 위치를
   * 안내한다 — 플랫폼 폴백이 없어져 이 상태가 흔해졌으므로 사유만 보고 조치할 수 있어야 한다.
   */
  @Test
  void deliver_smtpNotConfigured_returnsPermanentFailureWithGuidance() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of());

    var result = channel.deliver(ctx(1L, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.PermanentFailure.class,
            pf -> {
              assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE);
              assertThat(pf.details()).contains("SMTP 미설정").contains("워크스페이스 설정 › 이메일");
            });
    verify(channelHttpClient, never()).send(any(), any(), any());
  }

  /** 호스트 키가 있어도 값이 비어 있으면 미설정과 같다. */
  @Test
  void deliver_smtpHostBlank_returnsPermanentFailure() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

    var result = channel.deliver(ctx(1L, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.PermanentFailure.class,
            pf -> assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE));
  }

  /**
   * 워크스페이스가 호스트만 저장했으면 나머지 키는 맵에 <b>없다</b>(#712: 빈 값으로 채우지 않는다).
   * 그때 포트는 587, STARTTLS 는 켜짐으로 해석돼야 한다 — 저장하지 않은 보안 토글이 꺼짐으로
   * 읽히면 자격증명이 평문 채널로 나간다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void deliver_hostOnly_usesSafeDefaultsForMissingKeys() {
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", "tenant-relay.example.com"));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    ArgumentCaptor<Map<String, Object>> recipientCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), recipientCaptor.capture(), any(Map.class));
    Map<String, Object> smtpConfig =
        (Map<String, Object>) recipientCaptor.getValue().get("smtpConfig");
    assertThat(smtpConfig.get("port")).isEqualTo(587);
    assertThat(smtpConfig.get("secure")).isEqualTo(true);
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

  /**
   * 네트워크 오류(예: 커넥션 실패) 시 reason()에 원본 예외 클래스명이 아닌 안정적인 사유 코드
   * {@link com.smartfirehub.notification.TransientFailureReason#NETWORK_ERROR}가 담겨야 한다 (#666).
   * 예외 클래스명이 그대로 노출되면 ChannelSettingsService가 만드는 사용자 토스트 메시지에
   * "WebClientRequestException" 같은 문구가 그대로 섞여 나간다.
   */
  @Test
  void deliver_networkError_returnsTransientFailureWithStableReasonCode() {
    when(settingsService.getSmtpConfig()).thenReturn(smtpConfig());
    doThrow(new RuntimeException("Connection refused: connect"))
        .when(channelHttpClient)
        .send(anyString(), any(), any());

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.TransientFailure.class,
            tf ->
                assertThat(tf.reason())
                    .isEqualTo(com.smartfirehub.notification.TransientFailureReason.NETWORK_ERROR)
                    .doesNotContain("Exception"));
  }

  /**
   * 빈 포트 방어 가드. 저장 경로는 빈 포트를 거부하지만(validateSmtpPort) 그 검증 이전에 저장된
   * 옛 행이 있을 수 있다. {@code getOrDefault} 는 <b>키가 없을 때만</b> 기본값을 주므로 빈 값을 따로
   * 거르지 않으면 {@code Integer.parseInt("")} 가 터지고, 그 줄은 {@code try} 블록 <b>밖</b>이라
   * {@code DeliveryResult} 로 변환되지 못한 채 발송 워커로 튀어나간다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void deliver_빈_포트에도_예외없이_기본포트로_발송한다() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "tenant-relay.example.com",
                "smtp.port", "",
                "smtp.username", "",
                "smtp.password", "",
                "smtp.starttls", "true"));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    ArgumentCaptor<Map<String, Object>> recipientCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), recipientCaptor.capture(), any(Map.class));
    Map<String, Object> smtpConfig =
        (Map<String, Object>) recipientCaptor.getValue().get("smtpConfig");
    assertThat(smtpConfig.get("port")).isEqualTo(587);
    // 자격증명이 비어 있으므로 무인증 릴레이로 시도된다.
    assertThat(smtpConfig.get("user")).isEqualTo("");
    assertThat(smtpConfig.get("pass")).isEqualTo("");
  }

  /** 화이트라벨링: 제목 미지정 시 subject가 주입된 브랜드명 기반("Acme 알림")으로 구성되어야 한다. */
  @Test
  @SuppressWarnings("unchecked")
  void deliver_nullTitle_usesBrandNameForSubject() {
    ReflectionTestUtils.setField(channel, "brandName", "Acme");
    when(settingsService.getSmtpConfig()).thenReturn(smtpConfig());

    channel.deliver(ctxWithNullTitle("to@example.com"));

    ArgumentCaptor<Map<String, Object>> messageCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), any(Map.class), messageCaptor.capture());
    assertThat(messageCaptor.getValue().get("text")).isEqualTo("Acme 알림");
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

  /** title이 null인 Payload — subject 기본값(브랜드명) 경로 검증용. */
  private DeliveryContext ctxWithNullTitle(String address) {
    Payload p =
        new Payload(
            Payload.PayloadType.STANDARD,
            null,
            "요약",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of());
    return new DeliveryContext(1L, UUID.randomUUID(), null, address, Optional.empty(), p);
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
