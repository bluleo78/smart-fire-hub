package com.smartfirehub.proactive.service.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.util.StringUtils.countOccurrencesOf;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ReportRenderUtils;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.settings.service.SmtpFailureMessages;
import com.smartfirehub.settings.service.SmtpFailureMessages.MaskedSmtpException;
import com.smartfirehub.settings.service.SmtpMailSenders;
import com.smartfirehub.user.dto.UserResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
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

  /**
   * SMTP 팩토리 정적 mock(WD-676) — 실제 SMTP 없이 발송 성공·실패를 정한다. 예전 테스트는 「실제 SMTP 가 없어 내부에서 잡힌다」에 기댔는데, 이제
   * 한 통도 못 보내면 예외라 기본은 성공하는 mock sender 를 돌려준다.
   */
  private MockedStatic<SmtpMailSenders> smtpFactory;

  private JavaMailSenderImpl mailSender;

  @BeforeEach
  void mockSmtpFactory() {
    mailSender = mock(JavaMailSenderImpl.class);
    when(mailSender.createMimeMessage())
        .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    smtpFactory = mockStatic(SmtpMailSenders.class);
    smtpFactory.when(() -> SmtpMailSenders.create(any())).thenReturn(mailSender);
  }

  @AfterEach
  void closeSmtpFactory() {
    smtpFactory.close();
  }

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
   * 폴백 없음). 그래서 예외 문구가 등록 위치 (관리 › 설정 › 이메일 탭)를 안내하는지까지 본다.
   */
  @Test
  void deliver_smtpNotConfigured_throwsSoRunnerDoesNotRecordDelivery() {
    Map<String, Object> config = Map.of("channels", List.of("EMAIL"));
    ProactiveJobResponse job = makeJob(1L, config);

    assertThatThrownBy(() -> emailDeliveryChannel.deliver(job, 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SMTP 서버가 설정되지 않아")
        .hasMessageContaining("관리 › 설정 › 이메일 탭");

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

    // mock sender 로 보낸다(WD-676 — 실제 SMTP 실패를 삼키던 동작에 기대지 않는다)
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

    // mock sender 로 두 사람에게 보낸다
    emailDeliveryChannel.deliver(job, 1L, makeResult());
    verify(mailSender, times(2)).send(any(MimeMessage.class));

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

  // ── WD-676: 한 통도 못 보내면 예외(실행 기록에 「전달됨」이 남지 않게) ─────────────────────────

  private static final Map<String, String> SMTP =
      Map.of("smtp.host", "smtp.example.com", "smtp.port", "587");

  private ProactiveJobResponse jobWithExternal(String... emails) {
    return makeJob(
        99L,
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "EMAIL",
                    "recipientUserIds", List.of(),
                    "recipientEmails", List.of(emails)))));
  }

  /** 모든 수신자에서 STARTTLS 미지원으로 실패하면 한국어 분류 문구(설정 위치 포함)로 예외를 던진다. */
  @Test
  void deliver_allSendsFailWithStarttls_throwsClassifiedKoreanMessage() {
    when(settingsService.getSmtpConfig()).thenReturn(SMTP);
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");
    doThrow(
            new MailSendException(
                "Mail server connection failed",
                new MessagingException("STARTTLS is required but host does not support STARTTLS")))
        .when(mailSender)
        .send(any(MimeMessage.class));

    assertThatThrownBy(
            () ->
                emailDeliveryChannel.deliver(
                    jobWithExternal("a@example.com", "b@example.com"), 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STARTTLS")
        .hasMessageContaining(SmtpFailureMessages.SETTINGS_PLACE)
        .hasMessageContaining("smtp.example.com");
    verify(mailSender, times(2)).send(any(MimeMessage.class));
  }

  /** 두 수신자 중 하나만 실패하면 지금처럼 정상 반환한다(개별 주소 오류는 로그로만). */
  @Test
  void deliver_oneOfTwoFails_returnsNormally() {
    when(settingsService.getSmtpConfig()).thenReturn(SMTP);
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");
    doThrow(new MailSendException("Invalid Addresses"))
        .doNothing()
        .when(mailSender)
        .send(any(MimeMessage.class));

    emailDeliveryChannel.deliver(
        jobWithExternal("bad@example.com", "ok@example.com"), 1L, makeResult());

    verify(mailSender, times(2)).send(any(MimeMessage.class));
  }

  /** sender 를 만들지 못하면(바깥 catch — 숫자가 아닌 옛 포트 등) 아무에게도 못 보냈으므로 예외다. */
  @Test
  void deliver_senderCreationFails_throws() {
    when(settingsService.getSmtpConfig()).thenReturn(SMTP);
    smtpFactory
        .when(() -> SmtpMailSenders.create(any()))
        .thenThrow(new NumberFormatException("For input string: \"smtp\""));

    assertThatThrownBy(
            () -> emailDeliveryChannel.deliver(jobWithExternal("a@example.com"), 1L, makeResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("포트")
        .hasMessageContaining(SmtpFailureMessages.SETTINGS_PLACE);
  }

  // ── WD-686: 발송 실패 원문(비밀번호·서버 응답)이 던지는 예외의 cause·로그에 남지 않게 ─────────────────

  private static final String PW686 = "Pw686Secret!";

  /** UTF-8 바이트의 Base64 — 서버가 되울리는 AUTH LOGIN·PLAIN 형태를 만든다. */
  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** 스택 출력 전체(Caused by·Suppressed 포함) — 호출부가 스택을 찍으면 남는 문자열과 같다. */
  private static String stackOf(Throwable t) {
    StringWriter out = new StringWriter();
    t.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  /**
   * 모든 발송이 비밀번호가 든 서버 응답으로 실패하면, 던진 예외와 cause 사슬 전체(메시지·toString·스택 출력)와 이 채널의 error 로그 어디에도
   * 비밀번호(원문·Base64)가 없고, 가린 문구와 원래 예외 클래스 이름은 남는다. 예전에는 원래 예외를 cause 로 담아, 호출부가 스택을 찍으면 원문이 그대로
   * 나갔고, 이 채널의 error 로그도 원문 메시지를 찍었다. 스택은 첫 실패에만 찍힌다(수신자 수만큼 반복되지 않게).
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void deliver_allSendsFail_causeChainAndLogsDoNotCarryRawPassword(CapturedOutput output) {
    String base64 = b64(PW686);
    // AUTH PLAIN 자격 증명 덩어리(WD-686) — Base64("\0" + user + "\0" + password)
    String plainBlob = b64("\0mailer\0" + PW686);
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host",
                "smtp.example.com",
                "smtp.port",
                "587",
                "smtp.username",
                "mailer",
                "smtp.password",
                PW686));
    when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");
    doThrow(
            new MailSendException(
                "Failed messages: 554 relay denied for pass=" + PW686,
                new MessagingException(
                    "535 5.7.8 AUTH PLAIN "
                        + plainBlob
                        + " relay denied AUTH "
                        + base64
                        + " pass="
                        + PW686)))
        .when(mailSender)
        .send(any(MimeMessage.class));

    Throwable thrown =
        catchThrowable(
            () ->
                emailDeliveryChannel.deliver(
                    jobWithExternal("a@example.com", "b@example.com"), 1L, makeResult()));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(stackOf(thrown))
        .doesNotContain(PW686)
        .doesNotContain(base64)
        .doesNotContain(plainBlob)
        .contains("****")
        .contains("org.springframework.mail.MailSendException")
        .contains("jakarta.mail.MessagingException");
    assertThat(thrown.getCause()).isNotNull().isNotInstanceOf(MailSendException.class);
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      assertThat(String.valueOf(t.getMessage()))
          .doesNotContain(PW686)
          .doesNotContain(base64)
          .doesNotContain(plainBlob);
      assertThat(t.toString()).doesNotContain(PW686).doesNotContain(base64);
    }
    // 이 채널의 error 로그(수신자별 실패)에도 원문이 없다. 가린 사본을 throwable 로 남기므로 원래 클래스 이름·스택 위치는 보인다(WD-686).
    assertThat(output.getAll())
        .contains("EmailDeliveryChannel failed to send to")
        .contains("org.springframework.mail.MailSendException")
        .contains("jakarta.mail.MessagingException: 535 5.7.8 AUTH PLAIN ****")
        .doesNotContain(PW686)
        .doesNotContain(base64)
        .doesNotContain(plainBlob);
    // 두 수신자 모두 실패를 남기지만 스택(가린 사본의 Caused by)은 첫 실패에만 있다.
    String all = output.getAll();
    assertThat(countOccurrencesOf(all, "EmailDeliveryChannel failed to send to")).isEqualTo(2);
    assertThat(countOccurrencesOf(all, "Caused by: " + MaskedSmtpException.class.getName()))
        .isEqualTo(1);
  }

  /** sender 생성·템플릿 단계 실패(바깥 catch)의 error 로그 스택에도 원문 비밀번호가 없다(WD-686). */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void deliver_outerFailure_logStackDoesNotCarryRawPassword(CapturedOutput output) {
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of("smtp.host", "smtp.example.com", "smtp.port", "587", "smtp.password", PW686));
    smtpFactory
        .when(() -> SmtpMailSenders.create(any()))
        .thenThrow(new IllegalStateException("bad config with " + PW686));

    Throwable thrown =
        catchThrowable(
            () -> emailDeliveryChannel.deliver(jobWithExternal("a@example.com"), 1L, makeResult()));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(output.getAll())
        .contains("EmailDeliveryChannel delivery failed for job")
        .contains("bad config with ****")
        .doesNotContain(PW686);
  }
}
