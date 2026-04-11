package com.smartfirehub.proactive.service.delivery;

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

  @Test
  void deliver_smtpNotConfigured_skips() {
    // smtp.host blank -> should return immediately without calling userRepository
    Map<String, Object> config = Map.of("channels", List.of("EMAIL"));
    ProactiveJobResponse job = makeJob(1L, config);

    emailDeliveryChannel.deliver(job, 1L, makeResult());

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
