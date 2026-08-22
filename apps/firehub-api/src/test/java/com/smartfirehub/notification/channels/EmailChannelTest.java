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

  /**
   * <b>연결 번들 규칙이 만든 새 상태</b>: 테넌트가 {@code smtp.host} 만 재정의하면 나머지 연결
   * 4키가 <b>키는 있고 값은 빈</b> 상태로 내려온다(P7-c1 Task 5). 그 조합은 이 커밋 이전에는
   * 존재할 수 없었다 — V42 가 {@code smtp.port='587'} 로 시드하고 쓰기 검증(1~65535)이 빈 포트를
   * 거부하므로 발송기에 빈 포트가 도달할 길이 없었다.
   *
   * <p>{@code getOrDefault} 는 <b>키가 없을 때만</b> 기본값을 준다. 그래서 빈 값을 따로 걸러내지
   * 않으면 {@code Integer.parseInt("")} 가 터지고, 그 줄은 {@code try} 블록 <b>밖</b>이라
   * {@code DeliveryResult} 로 변환되지 못한 채 발송 워커로 튀어나간다 — 번들 규칙이 약속한
   * "인증 없는 릴레이 시도 → 눈에 보이는 발송 실패"가 처리되지 않은 예외로 바뀐다.
   *
   * <p>호스트 미설정 가드는 이 경로를 막아 주지 <b>못한다</b>. 시나리오의 정의상 호스트는
   * 테넌트가 넣은 non-blank 값이라 가드를 통과하고 포트 줄까지 내려온다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void deliver_번들로_비워진_포트에도_예외없이_기본포트로_발송한다() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "tenant-relay.example.com",
                "smtp.port", "",
                "smtp.username", "",
                "smtp.password", "",
                "smtp.starttls", ""));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    ArgumentCaptor<Map<String, Object>> recipientCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), recipientCaptor.capture(), any(Map.class));
    Map<String, Object> smtpConfig =
        (Map<String, Object>) recipientCaptor.getValue().get("smtpConfig");
    assertThat(smtpConfig.get("port")).isEqualTo(587);
    // 자격증명이 비어 있으므로 무인증 릴레이로 시도된다 — 이것이 번들 규칙이 의도한 결과다.
    assertThat(smtpConfig.get("user")).isEqualTo("");
    assertThat(smtpConfig.get("pass")).isEqualTo("");
    // 빈 starttls 는 꺼짐이다(Boolean.parseBoolean("") == false) — 화면의 Switch 표시와 일치한다.
    assertThat(smtpConfig.get("secure")).isEqualTo(false);
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
