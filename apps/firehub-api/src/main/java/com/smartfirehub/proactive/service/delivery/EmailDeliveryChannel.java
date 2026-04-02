package com.smartfirehub.proactive.service.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.util.ProactiveConfigParser;
import com.smartfirehub.proactive.util.ProactiveConfigParser.ChannelConfig;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import reactor.netty.http.client.HttpClient;

@Service
@Slf4j
public class EmailDeliveryChannel implements DeliveryChannel {

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String DEFAULT_FROM = "noreply@smartfirehub.io";
  private static final Duration CHART_TIMEOUT = Duration.ofSeconds(30);
  private static final List<String> CHART_COLORS =
      List.of("#228be6", "#40c057", "#fab005", "#fa5252", "#868e96");

  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final TemplateEngine templateEngine;
  private final Parser markdownParser;
  private final HtmlRenderer htmlRenderer;
  private final WebClient agentWebClient;
  private final ObjectMapper objectMapper;

  @Value("${agent.internal-token}")
  private String internalToken;

  public EmailDeliveryChannel(
      SettingsService settingsService,
      UserRepository userRepository,
      TemplateEngine templateEngine,
      @Value("${agent.url}") String agentUrl,
      ObjectMapper objectMapper) {
    this.settingsService = settingsService;
    this.userRepository = userRepository;
    this.templateEngine = templateEngine;
    this.objectMapper = objectMapper;
    var extensions = List.of(TablesExtension.create());
    this.markdownParser = Parser.builder().extensions(extensions).build();
    this.htmlRenderer = HtmlRenderer.builder().extensions(extensions).build();
    HttpClient httpClient = HttpClient.create().responseTimeout(CHART_TIMEOUT);
    this.agentWebClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
  }

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
        String ownerEmail =
            userRepository.findById(job.userId()).map(u -> u.email()).orElse(null);
        if (ownerEmail == null || ownerEmail.isBlank()) {
          log.warn("EmailDeliveryChannel skipped: no email for userId {}", job.userId());
          return;
        }
        toAddresses.add(ownerEmail);
      }

      // 이메일 공통 준비 (SMTP, 템플릿, 차트) - 루프 밖에서 1회 수행
      JavaMailSenderImpl mailSender = buildMailSender(smtp);
      List<Map<String, Object>> templateSections = buildTemplateSections(result.sections());
      List<ChartImage> chartImages = renderChartImages(templateSections);
      String html = renderTemplate(job, result, templateSections);
      String fromAddress = smtp.getOrDefault("smtp.from_address", DEFAULT_FROM);
      if (fromAddress.isBlank()) fromAddress = DEFAULT_FROM;

      // 각 수신자에게 개별 발송
      for (String toAddress : toAddresses) {
        try {
          var message = mailSender.createMimeMessage();
          boolean multipart = !chartImages.isEmpty();
          MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
          helper.setFrom(fromAddress);
          helper.setTo(toAddress);
          helper.setSubject("[Smart Fire Hub] " + result.title());
          helper.setText(html, true);

          for (ChartImage chart : chartImages) {
            byte[] imageBytes = Base64.getDecoder().decode(chart.base64());
            helper.addInline(chart.cid(), new ByteArrayResource(imageBytes), "image/png");
          }

          mailSender.send(message);
          log.info(
              "EmailDeliveryChannel sent report '{}' to {} for job {}",
              result.title(),
              toAddress,
              job.id());
        } catch (Exception e) {
          log.error(
              "EmailDeliveryChannel failed to send to {}: {}", toAddress, e.getMessage());
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

  private List<Map<String, Object>> buildTemplateSections(List<ProactiveResult.Section> sections) {
    List<Map<String, Object>> templateSections = new ArrayList<>();
    for (ProactiveResult.Section section : sections) {
      Map<String, Object> map = new HashMap<>();
      map.put("label", section.label() != null ? section.label() : "");
      map.put("content", section.content() != null ? markdownToHtml(section.content()) : "");

      // Extract cards from data if present
      if (section.data() instanceof Map<?, ?> dataMap) {
        Object cards = dataMap.get("cards");
        if (cards instanceof List<?> cardList) {
          map.put("cards", cardList);
        }
      }

      templateSections.add(map);
    }
    return templateSections;
  }

  private List<ChartImage> renderChartImages(List<Map<String, Object>> templateSections) {
    List<ChartImage> chartImages = new ArrayList<>();
    List<Map<String, Object>> chartRequests = new ArrayList<>();

    // Collect chart requests for all sections that have cards
    for (Map<String, Object> section : templateSections) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
      if (cards == null || cards.isEmpty()) {
        continue;
      }

      List<Object> labels = cards.stream().map(c -> c.get("label")).toList();
      List<Object> values = cards.stream().map(c -> c.get("value")).toList();

      Map<String, Object> chartRequest =
          Map.of(
              "type",
              "bar",
              "title",
              String.valueOf(section.get("label")),
              "data",
              Map.of(
                  "labels",
                  labels,
                  "datasets",
                  List.of(
                      Map.of(
                          "label", String.valueOf(section.get("label")),
                          "data", values,
                          "backgroundColor", CHART_COLORS))),
              "width",
              500,
              "height",
              300);

      chartRequests.add(chartRequest);
    }

    if (chartRequests.isEmpty()) {
      return chartImages;
    }

    try {
      Map<String, Object> requestBody = Map.of("charts", chartRequests);
      String responseBody =
          agentWebClient
              .post()
              .uri("/agent/chart-render")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Authorization", "Internal " + internalToken)
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(CHART_TIMEOUT)
              .block();

      if (responseBody == null) {
        log.warn("EmailDeliveryChannel: chart-render returned null response");
        return chartImages;
      }

      Map<String, Object> responseMap =
          objectMapper.readValue(responseBody, new TypeReference<>() {});
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> images = (List<Map<String, Object>>) responseMap.get("images");
      if (images == null) {
        return chartImages;
      }

      // Match chart images to sections that have cards
      int chartIndex = 0;
      for (Map<String, Object> section : templateSections) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
        if (cards == null || cards.isEmpty()) {
          continue;
        }
        if (chartIndex < images.size()) {
          Map<String, Object> image = images.get(chartIndex);
          String cid = "chart-" + chartIndex;
          String base64 = (String) image.get("base64");
          if (base64 != null && !base64.isBlank()) {
            chartImages.add(new ChartImage(cid, base64));
            section.put("chartCid", cid);
          }
          chartIndex++;
        }
      }
    } catch (Exception e) {
      log.warn(
          "EmailDeliveryChannel: chart rendering failed, proceeding without charts: {}",
          e.getMessage());
    }

    return chartImages;
  }

  private String markdownToHtml(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    Node document = markdownParser.parse(markdown);
    return htmlRenderer.render(document);
  }

  private record ChartImage(String cid, String base64) {}
}
