# Phase 7-1: PDF 리포트 내보내기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프로액티브 리포트 실행 결과를 PDF로 다운로드하고, 이메일에 PDF를 첨부할 수 있게 한다.

**Architecture:** 기존 이메일 전송 파이프라인(Thymeleaf + CommonMark + 차트 렌더링)에서 공통 로직을 추출하고, Flying Saucer(OpenPDF)로 HTML→PDF 변환 서비스를 추가한다. 프론트엔드에 다운로드 버튼과 이메일 PDF 첨부 토글을 추가한다.

**Tech Stack:** Flying Saucer + OpenPDF (HTML→PDF), Thymeleaf (HTML 템플릿), CommonMark (마크다운→HTML), NanumGothic (한글 폰트)

**Spec:** `docs/superpowers/specs/2026-04-02-phase-7-1-pdf-export-design.md`

---

## File Structure

### 신규 생성
| 파일 | 역할 |
|------|------|
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java` | 마크다운→HTML, 카드 추출, 차트 렌더링 공통 유틸 |
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/PdfExportService.java` | HTML→PDF 변환 서비스 |
| `apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html` | PDF 전용 Thymeleaf 템플릿 |
| `apps/firehub-api/src/main/resources/fonts/NanumGothic-Regular.ttf` | 한글 폰트 번들 |
| `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ReportRenderUtilsTest.java` | 공통 유틸 테스트 |
| `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/PdfExportServiceTest.java` | PDF 생성 테스트 |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `apps/firehub-api/build.gradle.kts` | Flying Saucer + OpenPDF 의존성 추가 |
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java` | 공통 로직 → ReportRenderUtils 위임, PDF 첨부 로직 추가 |
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/util/ProactiveConfigParser.java` | ChannelConfig에 attachPdf 필드 추가 |
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java` | PDF 다운로드 엔드포인트 추가 |
| `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannelTest.java` | ReportRenderUtils mock으로 전환, PDF 첨부 테스트 추가 |
| `apps/firehub-api/src/test/java/com/smartfirehub/proactive/util/ProactiveConfigParserTest.java` | attachPdf 파싱 테스트 추가 |
| `apps/firehub-web/src/api/proactive.ts` | downloadExecutionPdf() 함수 추가 |
| `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx` | PDF 다운로드 버튼 추가 |
| `apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx` | 이메일 PDF 첨부 토글 추가 |
| `apps/firehub-web/src/lib/validations/proactive-job.ts` | channelConfigSchema에 attachPdf 추가 |

---

## Task 1: 의존성 추가 + ReportRenderUtils 추출

EmailDeliveryChannel에서 공통 렌더링 로직을 추출하여 ReportRenderUtils로 분리한다.

**Files:**
- Modify: `apps/firehub-api/build.gradle.kts`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ReportRenderUtilsTest.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannelTest.java`

### Step 1: Flying Saucer 의존성 추가

`apps/firehub-api/build.gradle.kts`의 `dependencies` 블록에 추가:

```kotlin
implementation("org.xhtmlrenderer:flying-saucer-openpdf:9.7.1")
```

### Step 2: ReportRenderUtils 테스트 작성

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ReportRenderUtilsTest.java` 생성:

```java
package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.IntegrationTestBase;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReportRenderUtilsTest extends IntegrationTestBase {

  @Autowired private ReportRenderUtils reportRenderUtils;

  @Test
  void markdownToHtml_converts_basic_markdown() {
    String result = reportRenderUtils.markdownToHtml("**bold** text");
    assertThat(result).contains("<strong>bold</strong>");
    assertThat(result).contains("text");
  }

  @Test
  void markdownToHtml_converts_gfm_table() {
    String md = "| A | B |\n|---|---|\n| 1 | 2 |";
    String result = reportRenderUtils.markdownToHtml(md);
    assertThat(result).contains("<table>");
    assertThat(result).contains("<td>1</td>");
  }

  @Test
  void markdownToHtml_returns_empty_for_null() {
    assertThat(reportRenderUtils.markdownToHtml(null)).isEmpty();
    assertThat(reportRenderUtils.markdownToHtml("")).isEmpty();
    assertThat(reportRenderUtils.markdownToHtml("  ")).isEmpty();
  }

  @Test
  void buildTemplateSections_extracts_content_and_cards() {
    var sections = List.of(
        new ProactiveResult.Section("s1", "Summary", "**hello**", "text", null),
        new ProactiveResult.Section("s2", "KPI", null, "cards",
            Map.of("cards", List.of(
                Map.of("label", "매출", "value", "100만", "color", "blue")))));

    List<Map<String, Object>> result = reportRenderUtils.buildTemplateSections(sections);

    assertThat(result).hasSize(2);
    // First section: markdown converted to HTML
    assertThat((String) result.get(0).get("content")).contains("<strong>hello</strong>");
    assertThat(result.get(0).get("label")).isEqualTo("Summary");
    // Second section: cards extracted
    assertThat(result.get(1).get("cards")).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cards = (List<Map<String, Object>>) result.get(1).get("cards");
    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).get("label")).isEqualTo("매출");
  }

  @Test
  void buildTemplateSections_handles_null_content() {
    var sections = List.of(
        new ProactiveResult.Section("s1", "Empty", null, "text", null));
    List<Map<String, Object>> result = reportRenderUtils.buildTemplateSections(sections);
    assertThat(result).hasSize(1);
    assertThat((String) result.get(0).get("content")).isEmpty();
  }
}
```

### Step 3: 테스트 실패 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ReportRenderUtilsTest" -x generateJooqSchemaSource
```

Expected: FAIL — `ReportRenderUtils` 클래스가 존재하지 않음.

### Step 4: ReportRenderUtils 구현

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java` 생성:

```java
package com.smartfirehub.proactive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
@Slf4j
public class ReportRenderUtils {

  private static final Duration CHART_TIMEOUT = Duration.ofSeconds(30);
  private static final List<String> CHART_COLORS =
      List.of("#228be6", "#40c057", "#fab005", "#fa5252", "#868e96");

  private final Parser markdownParser;
  private final HtmlRenderer htmlRenderer;
  private final WebClient agentWebClient;
  private final ObjectMapper objectMapper;
  private final String internalToken;

  public ReportRenderUtils(
      @Value("${agent.url}") String agentUrl,
      @Value("${agent.internal-token}") String internalToken,
      ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.internalToken = internalToken;
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

  public String markdownToHtml(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    Node document = markdownParser.parse(markdown);
    return htmlRenderer.render(document);
  }

  public List<Map<String, Object>> buildTemplateSections(List<ProactiveResult.Section> sections) {
    List<Map<String, Object>> templateSections = new ArrayList<>();
    for (ProactiveResult.Section section : sections) {
      Map<String, Object> map = new HashMap<>();
      map.put("label", section.label() != null ? section.label() : "");
      map.put("content", section.content() != null ? markdownToHtml(section.content()) : "");

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

  @SuppressWarnings("unchecked")
  public List<ChartImage> renderChartImages(List<Map<String, Object>> templateSections) {
    List<ChartImage> chartImages = new ArrayList<>();
    List<Map<String, Object>> chartRequests = new ArrayList<>();

    for (Map<String, Object> section : templateSections) {
      List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
      if (cards == null || cards.isEmpty()) continue;

      List<Object> labels = cards.stream().map(c -> c.get("label")).toList();
      List<Object> values = cards.stream().map(c -> c.get("value")).toList();

      chartRequests.add(
          Map.of(
              "type", "bar",
              "title", String.valueOf(section.get("label")),
              "data", Map.of(
                  "labels", labels,
                  "datasets", List.of(
                      Map.of(
                          "label", String.valueOf(section.get("label")),
                          "data", values,
                          "backgroundColor", CHART_COLORS))),
              "width", 500,
              "height", 300));
    }

    if (chartRequests.isEmpty()) return chartImages;

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
        log.warn("ReportRenderUtils: chart-render returned null response");
        return chartImages;
      }

      Map<String, Object> responseMap =
          objectMapper.readValue(responseBody, new TypeReference<>() {});
      List<Map<String, Object>> images = (List<Map<String, Object>>) responseMap.get("images");
      if (images == null) return chartImages;

      int chartIndex = 0;
      for (Map<String, Object> section : templateSections) {
        List<Map<String, Object>> cards = (List<Map<String, Object>>) section.get("cards");
        if (cards == null || cards.isEmpty()) continue;
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
      log.warn("ReportRenderUtils: chart rendering failed: {}", e.getMessage());
    }

    return chartImages;
  }

  /** Base64 chart image를 data URI로 변환 (PDF용). */
  public void convertChartCidsToDataUris(
      List<Map<String, Object>> templateSections, List<ChartImage> chartImages) {
    Map<String, String> cidToDataUri = new HashMap<>();
    for (ChartImage img : chartImages) {
      cidToDataUri.put(img.cid(), "data:image/png;base64," + img.base64());
    }
    for (Map<String, Object> section : templateSections) {
      String cid = (String) section.get("chartCid");
      if (cid != null && cidToDataUri.containsKey(cid)) {
        section.put("chartDataUri", cidToDataUri.get(cid));
      }
    }
  }

  public record ChartImage(String cid, String base64) {}
}
```

### Step 5: 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ReportRenderUtilsTest" -x generateJooqSchemaSource
```

Expected: PASS

### Step 6: EmailDeliveryChannel 리팩터링

`EmailDeliveryChannel.java`에서 다음을 변경:

1. `markdownParser`, `htmlRenderer`, `agentWebClient`, `objectMapper`, `internalToken` 필드 제거
2. `ReportRenderUtils reportRenderUtils` 필드 추가 (생성자 주입)
3. `markdownToHtml()`, `buildTemplateSections()`, `renderChartImages()`, `ChartImage` 제거
4. `deliver()` 메서드에서 `this.buildTemplateSections(...)` → `reportRenderUtils.buildTemplateSections(...)` 등으로 교체

리팩터링 후 `EmailDeliveryChannel.java`:

```java
package com.smartfirehub.proactive.service.delivery;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
public class EmailDeliveryChannel implements DeliveryChannel {

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String DEFAULT_FROM = "noreply@smartfirehub.io";

  private final SettingsService settingsService;
  private final UserRepository userRepository;
  private final TemplateEngine templateEngine;
  private final ReportRenderUtils reportRenderUtils;

  public EmailDeliveryChannel(
      SettingsService settingsService,
      UserRepository userRepository,
      TemplateEngine templateEngine,
      ReportRenderUtils reportRenderUtils) {
    this.settingsService = settingsService;
    this.userRepository = userRepository;
    this.templateEngine = templateEngine;
    this.reportRenderUtils = reportRenderUtils;
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

      List<String> toAddresses = new ArrayList<>();
      Optional<ChannelConfig> emailConfig =
          ProactiveConfigParser.getChannelConfig(job.config(), "EMAIL");

      if (emailConfig.isPresent()) {
        ChannelConfig cfg = emailConfig.get();
        for (Long userId : cfg.recipientUserIds()) {
          userRepository
              .findById(userId)
              .map(u -> u.email())
              .filter(e -> e != null && !e.isBlank())
              .ifPresent(toAddresses::add);
        }
        toAddresses.addAll(cfg.recipientEmails());
      }

      if (toAddresses.isEmpty()) {
        String ownerEmail =
            userRepository.findById(job.userId()).map(u -> u.email()).orElse(null);
        if (ownerEmail == null || ownerEmail.isBlank()) {
          log.warn("EmailDeliveryChannel skipped: no email for userId {}", job.userId());
          return;
        }
        toAddresses.add(ownerEmail);
      }

      JavaMailSenderImpl mailSender = buildMailSender(smtp);
      List<Map<String, Object>> templateSections =
          reportRenderUtils.buildTemplateSections(result.sections());
      List<ChartImage> chartImages = reportRenderUtils.renderChartImages(templateSections);
      String html = renderTemplate(job, result, templateSections);
      String fromAddress = smtp.getOrDefault("smtp.from_address", DEFAULT_FROM);
      if (fromAddress.isBlank()) fromAddress = DEFAULT_FROM;

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
              result.title(), toAddress, job.id());
        } catch (Exception e) {
          log.error("EmailDeliveryChannel failed to send to {}: {}", toAddress, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error(
          "EmailDeliveryChannel delivery failed for job {}: {}", job.id(), e.getMessage(), e);
    }
  }

  private JavaMailSenderImpl buildMailSender(Map<String, String> smtp) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(smtp.getOrDefault("smtp.host", ""));
    String portStr = smtp.getOrDefault("smtp.port", "587");
    sender.setPort(portStr.isBlank() ? 587 : Integer.parseInt(portStr));
    String username = smtp.getOrDefault("smtp.username", "");
    if (!username.isBlank()) sender.setUsername(username);
    String password = smtp.getOrDefault("smtp.password", "");
    if (!password.isBlank()) sender.setPassword(password);
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
```

### Step 7: EmailDeliveryChannelTest 업데이트

기존 테스트에서 `ReportRenderUtils`를 `@MockitoBean`으로 모킹하도록 변경. 기존 테스트의 `@MockitoBean private SpringTemplateEngine templateEngine;` 등은 그대로 유지하되, ReportRenderUtils mock 추가:

```java
@MockitoBean private ReportRenderUtils reportRenderUtils;

@BeforeEach
void setup() {
  // ... 기존 setup 유지 ...
  when(reportRenderUtils.buildTemplateSections(anyList()))
      .thenReturn(List.of(Map.of("label", "Test", "content", "<p>test</p>")));
  when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());
}
```

### Step 8: 전체 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ReportRenderUtilsTest" --tests "*.EmailDeliveryChannelTest" -x generateJooqSchemaSource
```

Expected: PASS

### Step 9: 커밋

```bash
git add apps/firehub-api/build.gradle.kts \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ReportRenderUtilsTest.java \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannelTest.java
git commit -m "refactor(proactive): extract ReportRenderUtils from EmailDeliveryChannel"
```

---

## Task 2: PdfExportService + Thymeleaf PDF 템플릿

HTML→PDF 변환 서비스와 PDF 전용 Thymeleaf 템플릿을 생성한다.

**Files:**
- Create: `apps/firehub-api/src/main/resources/fonts/NanumGothic-Regular.ttf`
- Create: `apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/PdfExportService.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/PdfExportServiceTest.java`

### Step 1: 한글 폰트 다운로드

```bash
cd apps/firehub-api/src/main/resources
mkdir -p fonts
curl -L -o fonts/NanumGothic-Regular.ttf \
  "https://github.com/google/fonts/raw/main/ofl/nanumgothic/NanumGothic-Regular.ttf"
```

폰트가 다운로드되었는지 확인:

```bash
ls -la apps/firehub-api/src/main/resources/fonts/NanumGothic-Regular.ttf
```

### Step 2: PDF Thymeleaf 템플릿 생성

`apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html` 생성:

```html
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:th="http://www.thymeleaf.org" lang="ko">
  <head>
    <meta charset="UTF-8" />
    <title th:text="${title}">Smart Fire Hub Report</title>
    <style>
      @page {
        size: A4;
        margin: 15mm 20mm;
      }
      body {
        font-family: 'NanumGothic', sans-serif;
        font-size: 11pt;
        color: #333333;
        line-height: 1.6;
        margin: 0;
        padding: 0;
      }
      .header {
        background-color: #1a1a2e;
        color: #ffffff;
        padding: 20px 24px;
        border-radius: 6px;
        margin-bottom: 20px;
      }
      .header h1 {
        margin: 0 0 4px 0;
        font-size: 18pt;
        font-weight: 700;
      }
      .header .meta {
        font-size: 9pt;
        color: #adb5bd;
        margin: 0;
      }
      .section {
        margin-bottom: 20px;
        page-break-inside: avoid;
      }
      .section-label {
        font-size: 11pt;
        font-weight: 700;
        color: #1a1a2e;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        border-bottom: 2px solid #e9ecef;
        padding-bottom: 6px;
        margin-bottom: 10px;
      }
      .section-content {
        font-size: 10pt;
        line-height: 1.7;
        color: #495057;
        word-break: break-word;
      }
      .section-content table {
        border-collapse: collapse;
        width: 100%;
        margin: 8px 0;
      }
      .section-content th,
      .section-content td {
        border: 1px solid #dee2e6;
        padding: 5px 8px;
        text-align: left;
        font-size: 9pt;
      }
      .section-content th {
        background-color: #f1f3f5;
        font-weight: 600;
      }
      .cards-row {
        margin-bottom: 10px;
      }
      .cards-row table {
        width: 100%;
        border-collapse: separate;
        border-spacing: 8px 0;
      }
      .card {
        text-align: center;
        padding: 10px 12px;
        border-radius: 4px;
        background-color: #f8f9fa;
        border: 1px solid #e9ecef;
      }
      .card-value {
        font-size: 18pt;
        font-weight: 700;
        color: #1a1a2e;
      }
      .card-label {
        font-size: 8pt;
        color: #868e96;
        margin-top: 2px;
      }
      .chart-image {
        margin: 10px 0;
        text-align: center;
      }
      .chart-image img {
        max-width: 100%;
        height: auto;
        border-radius: 6px;
      }
      .footer {
        margin-top: 30px;
        padding-top: 10px;
        border-top: 1px solid #e9ecef;
        font-size: 8pt;
        color: #868e96;
        text-align: center;
      }
    </style>
  </head>
  <body>
    <div class="header">
      <h1 th:text="${title}">리포트 제목</h1>
      <p class="meta">
        <span th:text="${jobName}">Job Name</span>
        &#160;·&#160;
        <span th:text="${generatedAt}">2026-01-01 09:00</span>
      </p>
    </div>

    <div th:each="section : ${sections}" class="section">
      <div class="section-label" th:text="${section['label']}">섹션</div>

      <!-- cards -->
      <th:block th:if="${section['cards'] != null and !section['cards'].isEmpty()}">
        <div class="cards-row">
          <table>
            <tr>
              <td th:each="card : ${section['cards']}" class="card">
                <div class="card-value" th:text="${card['value']}">0</div>
                <div class="card-label" th:text="${card['label']}">항목</div>
              </td>
            </tr>
          </table>
        </div>
      </th:block>

      <!-- chart image (data URI for PDF) -->
      <th:block th:if="${section['chartDataUri'] != null}">
        <div class="chart-image">
          <img th:src="${section['chartDataUri']}" alt="차트" />
        </div>
      </th:block>

      <!-- markdown content -->
      <div
        th:if="${section['content'] != null and !section['content'].isBlank()}"
        class="section-content"
        th:utext="${section['content']}">
        내용
      </div>
    </div>

    <div class="footer">
      Smart Fire Hub에서 자동 생성됨
    </div>
  </body>
</html>
```

### Step 3: PdfExportService 테스트 작성

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/PdfExportServiceTest.java` 생성:

```java
package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.smartfirehub.IntegrationTestBase;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockitoBean;

class PdfExportServiceTest extends IntegrationTestBase {

  @Autowired private PdfExportService pdfExportService;
  @MockitoBean private ReportRenderUtils reportRenderUtils;

  @Test
  void generatePdf_returns_valid_pdf_bytes() {
    // Arrange
    var sections = List.of(
        new ProactiveResult.Section("s1", "주간 요약", "**매출 증가** 10%", "text", null));
    var result = new ProactiveResult("주간 리포트", sections, 
        new ProactiveResult.Usage(100, 200, 300));

    when(reportRenderUtils.buildTemplateSections(anyList()))
        .thenReturn(List.of(Map.of("label", "주간 요약", "content", "<p><strong>매출 증가</strong> 10%</p>")));
    when(reportRenderUtils.renderChartImages(anyList()))
        .thenReturn(List.of());

    // Act
    byte[] pdf = pdfExportService.generatePdf(result, "테스트 작업");

    // Assert
    assertThat(pdf).isNotNull();
    assertThat(pdf.length).isGreaterThan(0);
    // PDF starts with %PDF
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void generatePdf_with_cards_renders_successfully() {
    var sections = List.of(
        new ProactiveResult.Section("s1", "KPI", null, "cards",
            Map.of("cards", List.of(Map.of("label", "매출", "value", "1000만")))));
    var result = new ProactiveResult("KPI 리포트", sections,
        new ProactiveResult.Usage(50, 100, 150));

    when(reportRenderUtils.buildTemplateSections(anyList()))
        .thenReturn(List.of(Map.of(
            "label", "KPI",
            "content", "",
            "cards", List.of(Map.of("label", "매출", "value", "1000만")))));
    when(reportRenderUtils.renderChartImages(anyList()))
        .thenReturn(List.of());

    byte[] pdf = pdfExportService.generatePdf(result, "KPI 작업");

    assertThat(pdf).isNotNull();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void generatePdf_with_empty_sections_renders_successfully() {
    var result = new ProactiveResult("빈 리포트", List.of(),
        new ProactiveResult.Usage(10, 20, 30));

    when(reportRenderUtils.buildTemplateSections(anyList())).thenReturn(List.of());
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());

    byte[] pdf = pdfExportService.generatePdf(result, "빈 작업");

    assertThat(pdf).isNotNull();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }
}
```

### Step 4: 테스트 실패 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.PdfExportServiceTest" -x generateJooqSchemaSource
```

Expected: FAIL — `PdfExportService` 클래스가 존재하지 않음.

### Step 5: PdfExportService 구현

`apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/PdfExportService.java` 생성:

```java
package com.smartfirehub.proactive.service;

import com.lowagie.text.pdf.BaseFont;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.service.ReportRenderUtils.ChartImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfExportService {

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String FONT_PATH = "fonts/NanumGothic-Regular.ttf";

  private final TemplateEngine templateEngine;
  private final ReportRenderUtils reportRenderUtils;

  public byte[] generatePdf(ProactiveResult result, String jobName) {
    List<Map<String, Object>> templateSections =
        reportRenderUtils.buildTemplateSections(result.sections());
    List<ChartImage> chartImages = reportRenderUtils.renderChartImages(templateSections);
    reportRenderUtils.convertChartCidsToDataUris(templateSections, chartImages);

    Context ctx = new Context();
    ctx.setVariable("title", result.title());
    ctx.setVariable("jobName", jobName);
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);
    String html = templateEngine.process("proactive-report-pdf", ctx);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ITextRenderer renderer = new ITextRenderer();

      // Register Korean font
      ClassPathResource fontResource = new ClassPathResource(FONT_PATH);
      if (fontResource.exists()) {
        renderer
            .getFontResolver()
            .addFont(
                fontResource.getURL().toString(),
                BaseFont.IDENTITY_H,
                BaseFont.NOT_EMBEDDED);
      } else {
        log.warn("Korean font not found at classpath:{}", FONT_PATH);
      }

      renderer.setDocumentFromString(html);
      renderer.layout();
      renderer.createPDF(out);
      return out.toByteArray();
    } catch (Exception e) {
      log.error("PDF generation failed: {}", e.getMessage(), e);
      throw new RuntimeException("PDF 생성에 실패했습니다.", e);
    }
  }
}
```

### Step 6: 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.PdfExportServiceTest" -x generateJooqSchemaSource
```

Expected: PASS

### Step 7: 커밋

```bash
git add apps/firehub-api/src/main/resources/fonts/ \
  apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/PdfExportService.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/PdfExportServiceTest.java
git commit -m "feat(proactive): add PdfExportService with Thymeleaf PDF template"
```

---

## Task 3: PDF 다운로드 API 엔드포인트

프론트엔드에서 호출할 PDF 다운로드 API를 추가한다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java` (getExecution 메서드 추가 필요 시)

### Step 1: ProactiveJobService에 단건 실행 조회 메서드 확인

`ProactiveJobService`에 실행 결과 단건 조회 메서드가 없으면 추가한다. `ProactiveJobExecutionRepository.findById()`는 이미 존재하므로 서비스에서 래핑만 하면 된다.

`ProactiveJobService.java`에 추가:

```java
public ProactiveJobExecutionResponse getExecution(Long executionId) {
  return executionRepository.findById(executionId)
      .orElseThrow(() -> new IllegalArgumentException("실행 결과를 찾을 수 없습니다: " + executionId));
}
```

### Step 2: PDF 다운로드 엔드포인트 추가

`ProactiveJobController.java`에 추가:

```java
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

생성자에 `PdfExportService pdfExportService`와 `ObjectMapper objectMapper` 추가:

```java
private final ProactiveJobService proactiveJobService;
private final PdfExportService pdfExportService;
private final ObjectMapper objectMapper;
```

엔드포인트 메서드:

```java
@GetMapping("/{jobId}/executions/{executionId}/pdf")
@RequirePermission("proactive:read")
public ResponseEntity<byte[]> downloadExecutionPdf(
    @PathVariable Long jobId,
    @PathVariable Long executionId,
    Authentication authentication) {
  Long userId = (Long) authentication.getPrincipal();
  // Validate job ownership
  ProactiveJobResponse job = proactiveJobService.getJob(jobId, userId);

  // Get execution and validate
  ProactiveJobExecutionResponse execution = proactiveJobService.getExecution(executionId);
  if (!execution.jobId().equals(jobId)) {
    return ResponseEntity.badRequest().build();
  }
  if (!"COMPLETED".equals(execution.status())) {
    return ResponseEntity.badRequest().build();
  }
  if (execution.result() == null) {
    return ResponseEntity.badRequest().build();
  }

  // Parse result to ProactiveResult
  ProactiveResult result = objectMapper.convertValue(execution.result(), ProactiveResult.class);

  byte[] pdf = pdfExportService.generatePdf(result, job.name());

  String filename = "report-" + job.name() + "-" + executionId + ".pdf";
  String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);

  return ResponseEntity.ok()
      .header(HttpHeaders.CONTENT_DISPOSITION,
          "attachment; filename*=UTF-8''" + encodedFilename)
      .contentType(MediaType.APPLICATION_PDF)
      .body(pdf);
}
```

### Step 3: 빌드 확인

```bash
cd apps/firehub-api && ./gradlew build -x test -x generateJooqSchemaSource
```

Expected: BUILD SUCCESS

### Step 4: 커밋

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java
git commit -m "feat(proactive): add PDF download endpoint GET /jobs/{id}/executions/{id}/pdf"
```

---

## Task 4: ChannelConfig에 attachPdf 추가 + 이메일 PDF 첨부

이메일 발송 시 PDF를 첨부할 수 있도록 ChannelConfig를 확장하고 EmailDeliveryChannel에 첨부 로직을 추가한다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/util/ProactiveConfigParser.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/util/ProactiveConfigParserTest.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannelTest.java`

### Step 1: ProactiveConfigParser 테스트 추가

`ProactiveConfigParserTest.java`에 추가:

```java
@Test
void parseChannels_reads_attachPdf_true() {
  Map<String, Object> config = Map.of("channels", List.of(
      Map.of("type", "EMAIL",
          "recipientUserIds", List.of(),
          "recipientEmails", List.of(),
          "attachPdf", true)));
  List<ProactiveConfigParser.ChannelConfig> channels =
      ProactiveConfigParser.parseChannels(config);
  assertThat(channels).hasSize(1);
  assertThat(channels.get(0).attachPdf()).isTrue();
}

@Test
void parseChannels_defaults_attachPdf_to_false() {
  Map<String, Object> config = Map.of("channels", List.of(
      Map.of("type", "EMAIL",
          "recipientUserIds", List.of(),
          "recipientEmails", List.of())));
  List<ProactiveConfigParser.ChannelConfig> channels =
      ProactiveConfigParser.parseChannels(config);
  assertThat(channels).hasSize(1);
  assertThat(channels.get(0).attachPdf()).isFalse();
}

@Test
void parseChannels_old_format_attachPdf_false() {
  Map<String, Object> config = Map.of("channels", List.of("CHAT", "EMAIL"));
  List<ProactiveConfigParser.ChannelConfig> channels =
      ProactiveConfigParser.parseChannels(config);
  assertThat(channels.stream().allMatch(c -> !c.attachPdf())).isTrue();
}
```

### Step 2: 테스트 실패 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveConfigParserTest" -x generateJooqSchemaSource
```

Expected: FAIL — `attachPdf()` 메서드가 없음.

### Step 3: ChannelConfig에 attachPdf 필드 추가

`ProactiveConfigParser.java`의 `ChannelConfig` 레코드 변경:

```java
public record ChannelConfig(
    String type,
    List<Long> recipientUserIds,
    List<String> recipientEmails,
    boolean attachPdf) {}
```

`parseChannels()` 메서드에서 구 형식 분기:

```java
// 구 형식: ["CHAT", "EMAIL"]
result.add(new ChannelConfig(type, List.of(), List.of(), false));
```

신 형식 분기에 `attachPdf` 파싱 추가:

```java
// 신 형식
boolean attachPdf = Boolean.TRUE.equals(map.get("attachPdf"));
result.add(new ChannelConfig(type, userIds, emails, attachPdf));
```

### Step 4: 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveConfigParserTest" -x generateJooqSchemaSource
```

Expected: PASS

### Step 5: EmailDeliveryChannel에 PDF 첨부 로직 추가

`EmailDeliveryChannel.java`에 `PdfExportService` 주입 추가:

```java
private final PdfExportService pdfExportService;

public EmailDeliveryChannel(
    SettingsService settingsService,
    UserRepository userRepository,
    TemplateEngine templateEngine,
    ReportRenderUtils reportRenderUtils,
    PdfExportService pdfExportService) {
  this.settingsService = settingsService;
  this.userRepository = userRepository;
  this.templateEngine = templateEngine;
  this.reportRenderUtils = reportRenderUtils;
  this.pdfExportService = pdfExportService;
}
```

`deliver()` 메서드에서, 이메일 전송 루프 직전에 PDF 생성:

```java
// PDF 첨부 여부 확인 및 생성
boolean shouldAttachPdf = emailConfig.map(ChannelConfig::attachPdf).orElse(false);
byte[] pdfBytes = null;
if (shouldAttachPdf) {
  try {
    pdfBytes = pdfExportService.generatePdf(result, job.name());
  } catch (Exception e) {
    log.warn("PDF generation failed for job {}, sending without attachment: {}",
        job.id(), e.getMessage());
  }
}
```

이메일 전송 루프 내에서, 차트 이미지 첨부 후에 PDF 첨부:

```java
// PDF 첨부 (있는 경우)
if (pdfBytes != null) {
  if (!multipart) {
    // multipart가 false였으면 helper를 multipart로 재생성
    message = mailSender.createMimeMessage();
    helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setFrom(fromAddress);
    helper.setTo(toAddress);
    helper.setSubject("[Smart Fire Hub] " + result.title());
    helper.setText(html, true);
  }
  String pdfFilename = result.title() + ".pdf";
  helper.addAttachment(pdfFilename, new ByteArrayResource(pdfBytes), "application/pdf");
}
```

**더 깔끔한 접근:** `multipart` 변수를 항상 `true`로 설정 (PDF 또는 차트가 있을 때):

```java
boolean multipart = !chartImages.isEmpty() || pdfBytes != null;
```

이렇게 하면 재생성 로직이 불필요하다. `deliver()` 메서드의 관련 부분을 다시 정리:

```java
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

for (String toAddress : toAddresses) {
  try {
    var message = mailSender.createMimeMessage();
    boolean multipart = !chartImages.isEmpty() || pdfBytes != null;
    MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
    helper.setFrom(fromAddress);
    helper.setTo(toAddress);
    helper.setSubject("[Smart Fire Hub] " + result.title());
    helper.setText(html, true);

    for (ChartImage chart : chartImages) {
      byte[] imageBytes = Base64.getDecoder().decode(chart.base64());
      helper.addInline(chart.cid(), new ByteArrayResource(imageBytes), "image/png");
    }

    if (pdfBytes != null) {
      helper.addAttachment(result.title() + ".pdf",
          new ByteArrayResource(pdfBytes), "application/pdf");
    }

    mailSender.send(message);
    // ...
  }
}
```

### Step 6: EmailDeliveryChannelTest에 PDF 첨부 테스트 추가

```java
@MockitoBean private PdfExportService pdfExportService;

@Test
void deliver_attaches_pdf_when_attachPdf_is_true() {
  // config with attachPdf: true
  Map<String, Object> config = Map.of("channels", List.of(
      Map.of("type", "EMAIL", "recipientUserIds", List.of(),
          "recipientEmails", List.of("test@example.com"), "attachPdf", true)));
  var job = makeJob(10L, config);
  var result = makeResult();

  when(pdfExportService.generatePdf(any(), anyString()))
      .thenReturn("%PDF-test".getBytes());

  emailDeliveryChannel.deliver(job, 1L, result);

  verify(pdfExportService).generatePdf(any(), anyString());
}

@Test
void deliver_skips_pdf_when_attachPdf_is_false() {
  Map<String, Object> config = Map.of("channels", List.of(
      Map.of("type", "EMAIL", "recipientUserIds", List.of(),
          "recipientEmails", List.of("test@example.com"))));
  var job = makeJob(10L, config);
  var result = makeResult();

  emailDeliveryChannel.deliver(job, 1L, result);

  verify(pdfExportService, never()).generatePdf(any(), anyString());
}
```

### Step 7: 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveConfigParserTest" --tests "*.EmailDeliveryChannelTest" -x generateJooqSchemaSource
```

Expected: PASS

### Step 8: 커밋

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/util/ProactiveConfigParser.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/util/ProactiveConfigParserTest.java \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannel.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/delivery/EmailDeliveryChannelTest.java
git commit -m "feat(proactive): add attachPdf option to email delivery channel"
```

---

## Task 5: 프론트엔드 — PDF 다운로드 버튼

실행 결과 상세 뷰에 PDF 다운로드 버튼을 추가한다.

**Files:**
- Modify: `apps/firehub-web/src/api/proactive.ts`
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx`

### Step 1: proactiveApi에 downloadExecutionPdf 추가

`apps/firehub-web/src/api/proactive.ts`의 `proactiveApi` 객체에 추가:

```typescript
downloadExecutionPdf: (jobId: number, executionId: number) =>
  client.get(`/proactive/jobs/${jobId}/executions/${executionId}/pdf`, {
    responseType: 'blob',
  }),
```

### Step 2: JobExecutionsTab에 다운로드 버튼 추가

`apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx` 변경:

import 추가:

```typescript
import { FileDown, Loader2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';

import { proactiveApi } from '@/api/proactive';
import { downloadBlob } from '@/lib/download';
```

`ExecutionResultView` 컴포넌트에 `jobId` prop 추가:

```typescript
function ExecutionResultView({ execution, jobId }: { execution: ProactiveJobExecution; jobId: number }) {
```

COMPLETED 상태 렌더링 부분 (기존 `return` 문의 `<div className="p-4 space-y-4">` 부분)을 변경:

```typescript
  const [downloading, setDownloading] = useState(false);

  const handleDownloadPdf = useCallback(async () => {
    setDownloading(true);
    try {
      const response = await proactiveApi.downloadExecutionPdf(jobId, execution.id);
      downloadBlob(`report-${execution.id}.pdf`, response.data as Blob);
    } catch {
      toast.error('PDF 다운로드에 실패했습니다.');
    } finally {
      setDownloading(false);
    }
  }, [jobId, execution.id]);
```

`toast`를 import에 추가:

```typescript
import { toast } from 'sonner';
```

COMPLETED 상태의 return 문을:

```typescript
return (
  <div className="p-4 space-y-4">
    <div className="flex justify-end">
      <Button
        variant="outline"
        size="sm"
        onClick={handleDownloadPdf}
        disabled={downloading}
      >
        {downloading ? (
          <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />
        ) : (
          <FileDown className="h-3.5 w-3.5 mr-1" />
        )}
        PDF
      </Button>
    </div>
    {sections.map((section) => (
      <div key={section.key}>
        {sections.length > 1 && (
          <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
            {section.label}
          </p>
        )}
        <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 text-sm leading-relaxed">
          <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
            {section.content}
          </ReactMarkdown>
        </div>
      </div>
    ))}
  </div>
);
```

`ExecutionResultView` 호출부에서 `jobId` 전달:

```typescript
<ExecutionResultView execution={selected} jobId={jobId} />
```

### Step 3: 타입체크 확인

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 0 errors

### Step 4: 빌드 확인

```bash
cd apps/firehub-web && pnpm build
```

Expected: BUILD SUCCESS

### Step 5: 커밋

```bash
git add apps/firehub-web/src/api/proactive.ts \
  apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx
git commit -m "feat(proactive): add PDF download button to execution result view"
```

---

## Task 6: 프론트엔드 — 이메일 PDF 첨부 토글

스마트 작업 설정의 이메일 채널에 "PDF 첨부" 체크박스를 추가한다.

**Files:**
- Modify: `apps/firehub-web/src/lib/validations/proactive-job.ts`
- Modify: `apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx`

### Step 1: Zod 스키마에 attachPdf 추가

`apps/firehub-web/src/lib/validations/proactive-job.ts`의 `channelConfigSchema`를 변경:

```typescript
export const channelConfigSchema = z.object({
  type: z.enum(['CHAT', 'EMAIL']),
  recipientUserIds: z.array(z.number()),
  recipientEmails: z.array(z.string().email('올바른 이메일 형식이 아닙니다')),
  attachPdf: z.boolean().optional(),
});
```

### Step 2: ChannelRecipientEditor에 토글 추가

`apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx` 변경:

import에 `FileDown` 추가:

```typescript
import { FileDown, Mail, MessageSquare } from 'lucide-react';
```

이메일 채널의 `{emailEnabled && (` 블록 내, `EmailTagInput` 다음에 PDF 첨부 토글 추가:

```typescript
{emailEnabled && (
  <div className="mt-3 space-y-3">
    <p className="text-xs text-muted-foreground">
      수신자를 지정하지 않으면 본인에게만 전달됩니다
    </p>

    <div className="space-y-1">
      <Label className="text-xs">등록 사용자</Label>
      <UserCombobox
        selectedUserIds={emailChannel!.recipientUserIds}
        onChange={(ids) => updateChannel('EMAIL', { recipientUserIds: ids })}
        placeholder="사용자 검색 (이름 또는 이메일)"
        disabled={disabled}
      />
    </div>

    <div className="space-y-1">
      <Label className="text-xs">외부 이메일</Label>
      <EmailTagInput
        emails={emailChannel!.recipientEmails}
        onChange={(emails) => updateChannel('EMAIL', { recipientEmails: emails })}
        disabled={disabled}
      />
    </div>

    {/* PDF 첨부 옵션 */}
    <div className="flex items-center gap-2 pt-1 border-t">
      <Checkbox
        id="attach-pdf"
        checked={emailChannel!.attachPdf ?? false}
        disabled={disabled}
        onCheckedChange={(checked) =>
          updateChannel('EMAIL', { attachPdf: !!checked })
        }
      />
      <Label
        htmlFor="attach-pdf"
        className="flex items-center gap-1.5 text-xs cursor-pointer"
      >
        <FileDown className="h-3.5 w-3.5" />
        리포트를 PDF로 첨부
      </Label>
    </div>
  </div>
)}
```

### Step 3: 타입체크 확인

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 0 errors

### Step 4: 빌드 확인

```bash
cd apps/firehub-web && pnpm build
```

Expected: BUILD SUCCESS

### Step 5: 커밋

```bash
git add apps/firehub-web/src/lib/validations/proactive-job.ts \
  apps/firehub-web/src/pages/ai-insights/components/ChannelRecipientEditor.tsx
git commit -m "feat(proactive): add PDF attachment toggle to email channel config"
```

---

## Task 7: 전체 빌드 검증 + 린트

모든 변경사항의 빌드, 타입체크, 린트를 확인한다.

**Files:** 없음 (검증만)

### Step 1: 백엔드 전체 빌드 + 테스트

```bash
cd apps/firehub-api && ./gradlew build -x generateJooqSchemaSource
```

Expected: BUILD SUCCESS (테스트 포함)

### Step 2: 프론트엔드 타입체크 + 빌드 + 린트

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build && pnpm lint
```

Expected: 모두 성공

### Step 3: Spotless 적용 (백엔드 포매팅)

```bash
cd apps/firehub-api && ./gradlew spotlessApply
```

포매팅 변경이 있으면 커밋:

```bash
git add -A
git commit -m "style: apply spotless formatting"
```
