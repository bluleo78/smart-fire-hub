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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
   * 워크스페이스 SMTP 미설정(#712: getSmtpConfig 가 빈 맵)이면 영구 실패이고, 사유가 등록 위치를 안내한다 — 플랫폼 폴백이 없어져 이 상태가
   * 흔해졌으므로 사유만 보고 조치할 수 있어야 한다.
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
              assertThat(pf.details()).contains("SMTP 서버가 설정되지 않았습니다").contains("관리 › 설정 › 이메일 탭");
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
   * 워크스페이스가 호스트만 저장했으면 나머지 키는 맵에 <b>없다</b>(#712: 빈 값으로 채우지 않는다). 그때 포트는 587, STARTTLS 는 켜짐으로 해석돼야
   * 한다 — 저장하지 않은 보안 토글이 꺼짐으로 읽히면 자격증명이 평문 채널로 나간다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void deliver_hostOnly_usesSafeDefaultsForMissingKeys() {
    when(settingsService.getSmtpConfig())
        .thenReturn(Map.of("smtp.host", "tenant-relay.example.com"));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    ArgumentCaptor<Map<String, Object>> recipientCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), recipientCaptor.capture(), any(Map.class));
    Map<String, Object> smtpConfig =
        (Map<String, Object>) recipientCaptor.getValue().get("smtpConfig");
    assertThat(smtpConfig.get("port")).isEqualTo(587);
    // WD-593: STARTTLS 기본 켜짐은 "접속 후 승격 + 강제"로 전달돼야 한다 — secure(암시적 TLS)가 아니다.
    assertThat(smtpConfig.get("secure")).isEqualTo(false);
    assertThat(smtpConfig.get("requireTLS")).isEqualTo(true);
  }

  /**
   * WD-593: 포트 587 + STARTTLS 켜짐이면 평문으로 접속한 뒤 STARTTLS 로 승격해야 하므로 secure(암시적 TLS)는 false,
   * requireTLS 는 true 다. 예전처럼 secure=true 로 넘기면 nodemailer 가 587 에서 곧바로 TLS 핸드셰이크를 시도해 협상이 실패한다.
   */
  @Test
  void deliver_port587StartTlsTrue_sendsRequireTlsNotImplicitTls() {
    Map<String, Object> smtpConfig = capturedSmtpConfig("587", "true");

    assertThat(smtpConfig.get("secure")).isEqualTo(false);
    assertThat(smtpConfig.get("requireTLS")).isEqualTo(true);
  }

  /**
   * WD-593·WD-627: 포트 587 + STARTTLS 꺼짐은 기회적 STARTTLS 다 — 암시적 TLS 도, STARTTLS 강제도 하지 않는다. ignoreTLS
   * 는 보내지 않는다: 보내면 nodemailer 가 서버가 STARTTLS 를 광고해도 평문으로 보내 자격증명이 노출되고, AUTH 전에 STARTTLS 를 요구하는 서버는
   * 530 으로 거부한다.
   */
  @Test
  void deliver_port587StartTlsFalse_sendsOpportunisticStartTls() {
    Map<String, Object> smtpConfig = capturedSmtpConfig("587", "false");

    assertThat(smtpConfig.get("secure")).isEqualTo(false);
    assertThat(smtpConfig.get("requireTLS")).isEqualTo(false);
    assertThat(smtpConfig).doesNotContainKey("ignoreTLS");
  }

  /**
   * WD-593: 포트 465 는 관례상 암시적 TLS(SMTPS) 전용이라 STARTTLS 값과 무관하게 secure=true 로 접속 즉시 TLS 를 건다. 이미 TLS
   * 위이므로 STARTTLS 승격 강제(requireTLS)는 의미가 없어 false 다.
   */
  @Test
  void deliver_port465_sendsImplicitTlsWithoutRequireTls() {
    Map<String, Object> smtpConfig = capturedSmtpConfig("465", "true");

    assertThat(smtpConfig.get("secure")).isEqualTo(true);
    assertThat(smtpConfig.get("requireTLS")).isEqualTo(false);
  }

  /**
   * WD-593: 옛 행에 "TRUE" 처럼 대문자로 저장된 STARTTLS 값도 켜짐으로 해석한다 — JavaMail 경로(Boolean.parseBoolean)와 같은
   * 대소문자 무시 규칙이어야 같은 설정이 경로마다 다르게 동작하지 않는다.
   */
  @Test
  void deliver_legacyUppercaseStartTls_isTreatedAsEnabled() {
    Map<String, Object> smtpConfig = capturedSmtpConfig("587", "TRUE");

    assertThat(smtpConfig.get("secure")).isEqualTo(false);
    assertThat(smtpConfig.get("requireTLS")).isEqualTo(true);
  }

  /**
   * WD-627: 숫자가 아닌 옛 포트는 deliver() 밖으로 NumberFormatException 을 던지지 않고, SMTP 미설정과 같은 영구 실패(설정 위치
   * 안내)가 된다 — 재시도로 고쳐지지 않는 설정 문제이고, 처리되지 않은 예외는 발송 워커로 튀어나간다.
   */
  @Test
  void deliver_nonNumericPort_returnsPermanentFailureWithoutSending() {
    when(settingsService.getSmtpConfig())
        .thenReturn(Map.of("smtp.host", "smtp.example.com", "smtp.port", "smtp"));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result)
        .isInstanceOfSatisfying(
            DeliveryResult.PermanentFailure.class,
            pf -> {
              assertThat(pf.reason()).isEqualTo(PermanentFailureReason.UNRECOVERABLE);
              assertThat(pf.details()).isEqualTo(EmailChannel.SMTP_PORT_INVALID);
            });
    verify(channelHttpClient, never()).send(any(), any(), any());
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
   * 네트워크 오류(예: 커넥션 실패) 시 reason()에 원본 예외 클래스명이 아닌 안정적인 사유 코드 {@link
   * com.smartfirehub.notification.TransientFailureReason#NETWORK_ERROR}가 담겨야 한다 (#666). 예외 클래스명이
   * 그대로 노출되면 ChannelSettingsService가 만드는 사용자 토스트 메시지에 "WebClientRequestException" 같은 문구가 그대로 섞여
   * 나간다.
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
   * WD-686: firehub-channel 호출 실패 예외가 서버 응답 원문(AUTH 단계에서 되울린 비밀번호 포함 가능)을 담아도, warn 로그와 결과의 cause
   * (발송 워커의 debug 로그가 결과를 찍는다) 어디에도 비밀번호 원문이 없다. 원래 예외 클래스 이름은 남아 어느 단계에서 실패했는지 보인다.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void deliver_failureEchoingPassword_logAndCauseDoNotCarryRawPassword(CapturedOutput output) {
    String password = "Pw686Channel!";
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587",
                "smtp.username", "user@example.com",
                "smtp.password", password));
    doThrow(new RuntimeException("535 auth failed pass=" + password))
        .doThrow(new ChannelHttpException("smtp said pass=" + password, 502))
        .when(channelHttpClient)
        .send(anyString(), any(), any());

    var network = channel.deliver(ctx(null, "to@example.com"));
    var http = channel.deliver(ctx(null, "to@example.com"));

    for (var result : List.of(network, http)) {
      assertThat(result)
          .isInstanceOfSatisfying(
              DeliveryResult.TransientFailure.class,
              tf -> assertThat(String.valueOf(tf.cause())).doesNotContain(password));
    }
    assertThat(output.getAll())
        .contains("java.lang.RuntimeException: 535 auth failed pass=****")
        .contains(ChannelHttpException.class.getName())
        .doesNotContain(password);
  }

  /**
   * 빈 포트 방어 가드. 저장 경로는 빈 포트를 거부하지만(validateSmtpPort) 그 검증 이전에 저장된 옛 행이 있을 수 있다. {@code
   * getOrDefault} 는 <b>키가 없을 때만</b> 기본값을 주므로 빈 값을 따로 거르지 않으면 {@code Integer.parseInt("")} 가 터지고, 그
   * 줄은 {@code try} 블록 <b>밖</b>이라 {@code DeliveryResult} 로 변환되지 못한 채 발송 워커로 튀어나간다.
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

  /** 주어진 포트·STARTTLS 값으로 발송하고 firehub-channel 로 넘긴 smtpConfig 맵을 캡처해 돌려준다(WD-593 TLS 매핑 검증용). */
  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedSmtpConfig(String port, String startTls) {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", port,
                "smtp.username", "user@example.com",
                "smtp.password", "secret",
                "smtp.starttls", startTls));

    var result = channel.deliver(ctx(null, "to@example.com"));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    ArgumentCaptor<Map<String, Object>> recipientCaptor = ArgumentCaptor.forClass(Map.class);
    verify(channelHttpClient).send(eq("EMAIL"), recipientCaptor.capture(), any(Map.class));
    return (Map<String, Object>) recipientCaptor.getValue().get("smtpConfig");
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
