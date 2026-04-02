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

      // 이메일 공통 준비 (SMTP, 템플릿, 차트) - 루프 밖에서 1회 수행
      JavaMailSenderImpl mailSender = buildMailSender(smtp);
      List<Map<String, Object>> templateSections =
          reportRenderUtils.buildTemplateSections(result.sections());
      List<ChartImage> chartImages = reportRenderUtils.renderChartImages(templateSections);
      String html = renderTemplate(job, result, templateSections);
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
          helper.setSubject("[Smart Fire Hub] " + result.title());
          helper.setText(html, true);

          for (ChartImage chart : chartImages) {
            byte[] imageBytes = Base64.getDecoder().decode(chart.base64());
            helper.addInline(chart.cid(), new ByteArrayResource(imageBytes), "image/png");
          }

          if (finalPdfBytes != null) {
            String pdfName = (result.title() != null ? result.title() : job.name()) + ".pdf";
            helper.addAttachment(pdfName, new ByteArrayResource(finalPdfBytes), "application/pdf");
          }

          mailSender.send(message);
          log.info(
              "EmailDeliveryChannel sent report '{}' to {} for job {}",
              result.title(),
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

  private String renderTemplate(
      ProactiveJobResponse job,
      ProactiveResult result,
      List<Map<String, Object>> templateSections) {
    Context ctx = new Context();
    ctx.setVariable("title", result.title());
    ctx.setVariable("jobName", job.name());
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);
    return templateEngine.process("proactive-report", ctx);
  }
}
