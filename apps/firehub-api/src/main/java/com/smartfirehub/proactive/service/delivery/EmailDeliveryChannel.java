package com.smartfirehub.proactive.service.delivery;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ReportRenderUtils;
import com.smartfirehub.proactive.service.ReportRenderUtils.ChartImage;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailDeliveryChannel implements DeliveryChannel {

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String DEFAULT_FROM = "noreply@smartfirehub.io";

  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final TemplateEngine templateEngine;
  private final ReportRenderUtils reportRenderUtils;
  private final PdfExportService pdfExportService;

  /** 이메일 내 "웹에서 보기" 링크 생성에 사용할 앱 기본 URL. application.yml에서 app.base-url로 설정하며, 없으면 빈 링크로 처리한다. */
  @Value("${app.base-url:}")
  private String appBaseUrl;

  @Override
  public String type() {
    return "EMAIL";
  }

  @Override
  public void deliver(ProactiveJobResponse job, Long executionId, ProactiveResult result) {
    try {
      Map<String, String> smtp = settingsService.getSmtpConfig();
      String host = smtp.getOrDefault("smtp.host", "");
      if (host.isBlank()) {
        log.debug("EmailDeliveryChannel skipped: SMTP host is not configured");
        return;
      }

      // config에서 EMAIL 채널의 수신자 목록 구성
      List<String> toAddresses = new ArrayList<>();

      Optional<ChannelConfig> emailConfig =
          ProactiveConfigParser.getChannelConfig(job.config(), "EMAIL");

      if (emailConfig.isPresent()) {
        ChannelConfig cfg = emailConfig.get();

        // 등록 사용자 이메일 조회
        for (Long userId : cfg.recipientUserIds()) {
          userRepository
              .findById(userId)
              .map(u -> u.email())
              .filter(e -> e != null && !e.isBlank())
              .ifPresent(toAddresses::add);
        }

        // 외부 이메일 추가
        toAddresses.addAll(cfg.recipientEmails());
      }

      // 미지정 시 생성자 이메일 (기존 동작)
      if (toAddresses.isEmpty()) {
        String ownerEmail = userRepository.findById(job.userId()).map(u -> u.email()).orElse(null);
        if (ownerEmail == null || ownerEmail.isBlank()) {
          log.warn("EmailDeliveryChannel skipped: no email for userId {}", job.userId());
          return;
        }
        toAddresses.add(ownerEmail);
      }

      // 이메일 공통 준비 (SMTP, 템플릿) - 루프 밖에서 1회 수행
      // htmlContent가 있으면 요약(summary) 기반 이메일을 보내고, PDF에 전체 리포트를 첨부한다.
      // htmlContent가 없으면 기존 sections 기반 전체 내용을 이메일 본문에 포함한다.
      JavaMailSenderImpl mailSender = buildMailSender(smtp);
      List<Map<String, Object>> templateSections;
      List<ChartImage> chartImages;

      boolean hasHtmlReport = result.htmlContent() != null && !result.htmlContent().isBlank();
      if (hasHtmlReport) {
        // 요약은 Thymeleaf 템플릿의 summary 박스에 이미 표시되므로 sections는 비운다
        // 전체 리포트는 PDF 첨부 + "웹에서 보기" 링크로 제공
        templateSections = List.of();
        chartImages = List.of();
      } else {
        // 기존 경로: sections 전체를 이메일 본문에 포함
        templateSections = reportRenderUtils.buildTemplateSections(result.sections());
        chartImages = reportRenderUtils.renderChartImages(templateSections);
      }
      String html = renderTemplate(job, executionId, result, templateSections);
      String fromAddress = smtp.getOrDefault("smtp.from_address", DEFAULT_FROM);
      if (fromAddress.isBlank()) fromAddress = DEFAULT_FROM;

      // PDF 첨부 여부 확인 및 생성
      boolean shouldAttachPdf = emailConfig.map(ChannelConfig::attachPdf).orElse(false);
      byte[] pdfBytes = null;
      if (shouldAttachPdf) {
        try {
          pdfBytes = pdfExportService.generatePdf(result, job.name());
        } catch (Exception e) {
          log.warn("PDF generation failed for job {}: {}", job.id(), e.getMessage());
        }
      }
      final byte[] finalPdfBytes = pdfBytes;

      // 각 수신자에게 개별 발송
      for (String toAddress : toAddresses) {
        try {
          var message = mailSender.createMimeMessage();
          boolean multipart = !chartImages.isEmpty() || finalPdfBytes != null;
          MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
          helper.setFrom(fromAddress);
          helper.setTo(toAddress);
          helper.setSubject("[Smart Fire Hub] " + result.effectiveTitle(job.name()));
          helper.setText(html, true);

          for (ChartImage chart : chartImages) {
            byte[] imageBytes = Base64.getDecoder().decode(chart.base64());
            helper.addInline(chart.cid(), new ByteArrayResource(imageBytes), "image/png");
          }

          if (finalPdfBytes != null) {
            String pdfName = result.effectiveTitle(job.name()) + ".pdf";
            helper.addAttachment(pdfName, new ByteArrayResource(finalPdfBytes), "application/pdf");
          }

          mailSender.send(message);
          log.info(
              "EmailDeliveryChannel sent report '{}' to {} for job {}",
              result.effectiveTitle(job.name()),
              toAddress,
              job.id());
        } catch (Exception e) {
          log.error("EmailDeliveryChannel failed to send to {}: {}", toAddress, e.getMessage());
          // 개별 발송 실패는 다른 수신자에게 영향을 주지 않도록 continue
        }
      }
    } catch (Exception e) {
      log.error("EmailDeliveryChannel delivery failed for job {}: {}", job.id(), e.getMessage(), e);
    }
  }

  private JavaMailSenderImpl buildMailSender(Map<String, String> smtp) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(smtp.getOrDefault("smtp.host", ""));

    String portStr = smtp.getOrDefault("smtp.port", "587");
    sender.setPort(portStr.isBlank() ? 587 : Integer.parseInt(portStr));

    String username = smtp.getOrDefault("smtp.username", "");
    if (!username.isBlank()) {
      sender.setUsername(username);
    }

    String password = smtp.getOrDefault("smtp.password", "");
    if (!password.isBlank()) {
      sender.setPassword(password);
    }

    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", !username.isBlank() ? "true" : "false");
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");

    String starttls = smtp.getOrDefault("smtp.starttls", "true");
    if ("true".equalsIgnoreCase(starttls)) {
      props.put("mail.smtp.starttls.enable", "true");
    }

    return sender;
  }

  /**
   * 이메일 본문 HTML을 Thymeleaf 템플릿으로 렌더링한다.
   *
   * <p>summary 텍스트를 본문 상단에 표시하고, "웹에서 보기" 링크(reportUrl)를 포함한다. reportUrl은 appBaseUrl이 설정된 경우에만
   * 생성된다.
   */
  private String renderTemplate(
      ProactiveJobResponse job,
      Long executionId,
      ProactiveResult result,
      List<Map<String, Object>> templateSections) {
    // "웹에서 보기" URL 구성: baseUrl이 없으면 빈 문자열(템플릿에서 조건부 표시)
    String reportUrl = "";
    if (appBaseUrl != null && !appBaseUrl.isBlank()) {
      reportUrl =
          appBaseUrl + "/ai-insights/jobs/" + job.id() + "/executions/" + executionId + "/report";
    }

    Context ctx = new Context();
    ctx.setVariable("title", result.effectiveTitle(job.name()));
    ctx.setVariable("jobName", job.name());
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);
    // summary 마크다운을 HTML로 변환하여 이메일에서 굵게, 목록 등이 렌더링되도록 한다
    ctx.setVariable("summary", reportRenderUtils.markdownToHtml(result.effectiveSummary()));
    ctx.setVariable("reportUrl", reportUrl);
    return templateEngine.process("proactive-report", ctx);
  }
}
