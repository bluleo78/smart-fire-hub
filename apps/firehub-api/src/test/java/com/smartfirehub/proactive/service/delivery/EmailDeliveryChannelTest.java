package com.smartfirehub.proactive.service.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ReportRenderUtils;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.thymeleaf.context.IContext;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * EmailDeliveryChannel 단위 테스트. Spring 컨텍스트 없이 순수 Mockito로 실행한다. 통합 테스트로 돌리면 테스트마다 Hikari 풀이 생성되어
 * Postgres `too many clients` 에러를 유발하므로, 의존성 전부 Mock으로 충분한 이 테스트는 단위 테스트 범주로 유지한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailDeliveryChannelTest {

  @Mock private SettingsService settingsService;
  @Mock private UserRepository userRepository;
  @Mock private SpringTemplateEngine templateEngine;
  @Mock private ReportRenderUtils reportRenderUtils;
  @Mock private PdfExportService pdfExportService;

  @InjectMocks private EmailDeliveryChannel emailDeliveryChannel;

  @BeforeEach
  void setup() {
    // Default: SMTP not configured -> deliver() returns early
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));
    // Default: return empty lists so deliver() doesn't NPE when SMTP is configured
    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(List.of());
    when(reportRenderUtils.renderChartImages(any())).thenReturn(List.of());
  }

  private ProactiveResult makeResult() {
    return new ProactiveResult("Test Report", List.of(), null, null, null);
  }

  private ProactiveJobResponse makeJob(Long userId, Map<String, Object> config) {
    return new ProactiveJobResponse(
        1L,
        userId,
        null,
        null,
        "Test Job",
        "prompt",
        "0 9 * * *",
        "Asia/Seoul",
        true,
        null,
        config,
        null,
        null,
        null,
        null,
        null);
  }

  private UserResponse makeUser(Long id, String name, String email) {
    return new UserResponse(id, "user" + id, email, name, true, null);
  }

  /**
   * 빈 호스트는 <b>조용한 건너뛰기가 아니라 예외</b>다(#390 item 5).
   *
   * <p>예전 이름은 {@code deliver_smtpNotConfigured_skips} 였고 "정상 반환한다"를 계약처럼 말했다.
   * 그런데 정상 반환은 호출부({@code ProactiveJobAsyncRunner})가 EMAIL 을
   * {@code deliveredChannels} 에 담아 <b>실행 기록에 "전달됨"으로 남기는</b> 것을 뜻한다 —
   * 한 통도 안 나갔는데. 같은 상태에서 {@code EmailChannel} 은 {@code PermanentFailure} 를
   * 돌려주므로 두 소비자의 가시성이 어긋나 있었다.
   *
   * <p><b>흔한 상태다.</b> {@code V42:108} 이 {@code smtp.host} 를 빈 문자열로 시드하므로 운영자가
   * SMTP 를 설정하지 않은 <b>모든 배포</b>가 이미 여기를 지난다. P7-c1 의 원자 해석은 도달 경로를
   * 하나 더 얹었을 뿐이다(포트만·비밀번호만 재정의하면 번들이 호스트를 빈 값으로 채운다).
   * 그래서 이 계약 변경의 영향은 새 번들 상태가 아니라 <b>기존 미설정 배포 전체</b>에 미친다.
   */
  @Test
  void deliver_smtpNotConfigured_throwsSoRunnerDoesNotRecordDelivery() {
    Map<String, Object> config = Map.of("channels", List.of("EMAIL"));
    ProactiveJobResponse job = makeJob(1L, config);

    assertThatThrownBy(() -> emailDeliveryChannel.deliver(job, 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP 호스트 미설정");

    // 던지기 전에 아무 일도 하지 않는다 — 수신자 조회조차 가지 않는다.
    verify(userRepository, never()).findById(anyLong());
  }

  @Test
  void deliver_noRecipients_defaultsToJobOwner() {
    // SMTP configured, no recipientUserIds -> falls back to job owner
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(userRepository.findById(1L))
        .thenReturn(Optional.of(makeUser(1L, "Owner", "owner@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(),
                    "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(1L, config);

    // Will try to send but fail (no real SMTP) — the exception is caught internally
    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // Verify owner email was resolved
    verify(userRepository).findById(1L);
  }

  @Test
  void deliver_withRecipientUserIds_loadsEachUserEmail() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(userRepository.findById(10L))
        .thenReturn(Optional.of(makeUser(10L, "Alice", "alice@example.com")));
    when(userRepository.findById(20L))
        .thenReturn(Optional.of(makeUser(20L, "Bob", "bob@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(10, 20),
                    "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    // Will try to send but SMTP will fail internally (caught)
    emailDeliveryChannel.deliver(job, 1L, makeResult());

    verify(userRepository).findById(10L);
    verify(userRepository).findById(20L);
    // owner should NOT be looked up (recipients specified)
    verify(userRepository, never()).findById(99L);
  }

  @Test
  void deliver_withRecipientEmails_usesExternalEmailsDirectly() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(),
                    "recipientEmails", List.of("external@example.com"))));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // No user repository calls needed for external emails
    verify(userRepository, never()).findById(anyLong());
  }

  @Test
  void deliver_withMixedRecipients_combinesBoth() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(userRepository.findById(10L))
        .thenReturn(Optional.of(makeUser(10L, "Alice", "alice@example.com")));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(10),
                    "recipientEmails", List.of("external@example.com"))));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    // Registered user resolved
    verify(userRepository).findById(10L);
    // Owner not involved
    verify(userRepository, never()).findById(99L);
  }

  @Test
  void deliver_attaches_pdf_when_attachPdf_is_true() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");
    when(pdfExportService.generatePdf(any(ProactiveResult.class), anyString()))
        .thenReturn(new byte[] {1, 2, 3});

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type",
                    "EMAIL",
                    "recipientUserIds",
                    List.of(),
                    "recipientEmails",
                    List.of("pdf@example.com"),
                    "attachPdf",
                    true)));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    verify(pdfExportService).generatePdf(any(ProactiveResult.class), anyString());
  }

  @Test
  void deliver_skips_pdf_when_attachPdf_is_false() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type",
                    "EMAIL",
                    "recipientUserIds",
                    List.of(),
                    "recipientEmails",
                    List.of("nopdf@example.com"),
                    "attachPdf",
                    false)));
    ProactiveJobResponse job = makeJob(99L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

    verify(pdfExportService, never()).generatePdf(any(), anyString());
  }
}
