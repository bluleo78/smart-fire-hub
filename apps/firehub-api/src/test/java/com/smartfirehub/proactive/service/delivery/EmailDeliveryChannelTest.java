package com.smartfirehub.proactive.service.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Map;
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
  @Mock private ActiveRecipientResolver recipientResolver;
  @Mock private SpringTemplateEngine templateEngine;
  @Mock private ReportRenderUtils reportRenderUtils;
  @Mock private PdfExportService pdfExportService;

  @InjectMocks private EmailDeliveryChannel emailDeliveryChannel;

  @BeforeEach
  void setup() {
    // 기본값: 워크스페이스 SMTP 미설정 — #712 이후 getSmtpConfig 는 미설정이면 빈 맵을 준다.
    when(settingsService.getSmtpConfig()).thenReturn(Map.of());
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
   * <p>예전 이름은 {@code deliver_smtpNotConfigured_skips} 였고 "정상 반환한다"를 계약처럼 말했다. 그런데 정상 반환은 호출부({@code
   * ProactiveJobAsyncRunner})가 EMAIL 을 {@code deliveredChannels} 에 담아 <b>실행 기록에 "전달됨"으로 남기는</b> 것을
   * 뜻한다 — 한 통도 안 나갔는데. 같은 상태에서 {@code EmailChannel} 은 {@code PermanentFailure} 를 돌려주므로 두 소비자의 가시성이
   * 어긋나 있었다.
   *
   * <p><b>흔한 상태다.</b> #712 로 SMTP 가 워크스페이스 전용이 되어, 자기 SMTP 를 등록하지 않은 <b>모든 워크스페이스</b>가 여기를 지난다(플랫폼
   * 폴백 없음). 그래서 예외 문구가 등록 위치 (워크스페이스 설정 › 이메일)를 안내하는지까지 본다.
   */
  @Test
  void deliver_smtpNotConfigured_throwsSoRunnerDoesNotRecordDelivery() {
    Map<String, Object> config = Map.of("channels", List.of("EMAIL"));
    ProactiveJobResponse job = makeJob(1L, config);

    assertThatThrownBy(() -> emailDeliveryChannel.deliver(job, 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP 미설정")
        .hasMessageContaining("워크스페이스 설정 › 이메일");

    // 던지기 전에 아무 일도 하지 않는다 — 수신자 조회조차 가지 않는다.
    verify(recipientResolver, never()).activeUsers(any());
  }

  @Test
  void deliver_noRecipients_defaultsToJobOwner() {
    // SMTP configured, no recipientUserIds -> falls back to job owner
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(recipientResolver.activeUsers(List.of(1L)))
        .thenReturn(List.of(makeUser(1L, "Owner", "owner@example.com")));
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
    verify(recipientResolver).activeUsers(List.of(1L));
  }

  @Test
  void deliver_withRecipientUserIds_loadsEachUserEmail() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(recipientResolver.activeUsers(List.of(10L, 20L)))
        .thenReturn(
            List.of(
                makeUser(10L, "Alice", "alice@example.com"),
                makeUser(20L, "Bob", "bob@example.com")));
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

    verify(recipientResolver).activeUsers(List.of(10L, 20L));
    // owner should NOT be looked up (recipients specified)
    verify(recipientResolver, never()).activeUsers(List.of(99L));
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

    // 외부 메일만 지정 — 작업 주인으로 대체하지 않는다
    verify(recipientResolver, never()).activeUsers(List.of(99L));
  }

  /**
   * WD-172: 지정한 사용자 수신자가 모두 정지·제거돼 받을 사람이 없으면, 작업 주인에게 대신 보내지 않고 예외를 던진다 — 정상 반환이면 실행 기록에 「이메일로
   * 전달됨」이 거짓으로 남는다.
   */
  @Test
  void deliver_allRecipientsUnreachable_throwsInsteadOfFallingBackToOwner() {
    when(settingsService.getSmtpConfig())
        .thenReturn(Map.of("smtp.host", "smtp.example.com", "smtp.port", "587"));
    when(recipientResolver.activeUsers(List.of(10L))).thenReturn(List.of());

    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(10),
                    "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    assertThatThrownBy(() -> emailDeliveryChannel.deliver(job, 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("받을 수 있는 수신자가 없습니다");
    verify(recipientResolver, never()).activeUsers(List.of(99L));
    verify(templateEngine, never()).process(anyString(), any(IContext.class));
  }

  @Test
  void deliver_withMixedRecipients_combinesBoth() {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "smtp.example.com",
                "smtp.port", "587"));
    when(recipientResolver.activeUsers(List.of(10L)))
        .thenReturn(List.of(makeUser(10L, "Alice", "alice@example.com")));
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
    verify(recipientResolver).activeUsers(List.of(10L));
    // Owner not involved
    verify(recipientResolver, never()).activeUsers(List.of(99L));
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

  /** 대소문자만 다른 외부 이메일은 한 사람이다 — 한 번만 보낸다(WD-299). 먼저 나온 표기를 쓴다. */
  @Test
  void distinctAddresses_mergesCaseOnlyDifferences() {
    assertThat(
            EmailDeliveryChannel.distinctAddresses(
                List.of("QA@Example.COM", "qa@example.com", "workloop@example.com")))
        .containsExactly("QA@Example.COM", "workloop@example.com");
  }

  /** 등록 사용자로 고른 사람의 주소를 외부 이메일로 또 넣어도 그 사람에게는 한 통만 간다(WD-299). */
  @Test
  void distinctAddresses_dropsExternalEmailSameAsRegisteredUser() {
    // 등록 사용자 주소가 앞, 외부 이메일이 뒤 — deliver 가 잇는 순서 그대로
    assertThat(
            EmailDeliveryChannel.distinctAddresses(
                List.of("qa267low@example.com", " QA267LOW@example.com ", "other@example.com", "")))
        .containsExactly("qa267low@example.com", "other@example.com");
  }
}
