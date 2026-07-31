package com.smartfirehub.proactive.service;

import com.lowagie.text.pdf.BaseFont;
import com.smartfirehub.proactive.dto.ProactiveResult;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;
import reactor.netty.http.client.HttpClient;

/**
 * 프로액티브 리포트 PDF 생성 서비스.
 *
 * <p>htmlContent가 있으면 AI Agent의 Puppeteer 엔드포인트(/agent/html-to-pdf)를 호출하여 HTML→PDF 변환한다. headless
 * Chrome이 렌더링하므로 CSS3, SVG, 한글 폰트가 완벽하게 지원된다.
 *
 * <p>htmlContent가 없으면 기존 Flying Saucer(sections→Thymeleaf→XHTML→PDF) 경로를 유지한다(하위 호환).
 */
@Service
@Slf4j
public class PdfExportService {

  private static final DateTimeFormatter DISPLAY_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String FONT_PATH = "fonts/NanumGothic-Regular.ttf";
  private static final Duration PDF_TIMEOUT = Duration.ofSeconds(60);

  private final TemplateEngine templateEngine;
  private final ReportRenderUtils reportRenderUtils;
  private final WebClient agentWebClient;
  private final String internalToken;

  /** 화이트라벨링용 브랜드명. PDF 리포트 푸터의 브랜드 표기에 사용한다. 배포처별 APP_BRANDING_NAME으로 주입. */
  @Value("${app.branding.name:Smart Fire Hub}")
  private String brandName;

  public PdfExportService(
      TemplateEngine templateEngine,
      ReportRenderUtils reportRenderUtils,
      @Value("${agent.url}") String agentUrl,
      @Value("${agent.internal-token}") String internalToken) {
    this.templateEngine = templateEngine;
    this.reportRenderUtils = reportRenderUtils;
    this.internalToken = internalToken;

    HttpClient httpClient = HttpClient.create().responseTimeout(PDF_TIMEOUT);
    this.agentWebClient =
        WebClient.builder()
            .baseUrl(agentUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
  }

  /**
   * ProactiveResult로부터 PDF 바이트 배열을 생성한다.
   *
   * <p>htmlContent가 있으면 AI Agent의 Puppeteer 엔드포인트를 호출한다. 실패 시 Flying Saucer 폴백. htmlContent가 없으면 기존
   * Flying Saucer 경로를 사용한다.
   */
  public byte[] generatePdf(ProactiveResult result, String jobName) {
    if (result.htmlContent() != null && !result.htmlContent().isBlank()) {
      try {
        // Puppeteer 기반 HTML→PDF 변환 (CSS3, SVG, 한글 완벽 지원)
        log.debug("PdfExportService: Puppeteer HTML→PDF (jobName={})", jobName);
        return generatePdfViaPuppeteer(result.htmlContent());
      } catch (Exception e) {
        log.warn("PdfExportService: Puppeteer PDF 실패, Flying Saucer 폴백 ({})", e.getMessage());
        // Puppeteer 실패 시 Flying Saucer로 폴백
      }
    }

    // 기존 경로: sections → Thymeleaf → Flying Saucer
    return generatePdfViaFlyingSaucer(result, jobName);
  }

  /**
   * AI Agent의 /agent/html-to-pdf 엔드포인트를 호출하여 PDF를 생성한다. headless Chrome(Puppeteer)이 렌더링하므로 웹 뷰어와
   * 동일한 품질의 PDF가 생성된다.
   */
  private byte[] generatePdfViaPuppeteer(String htmlContent) {
    byte[] pdfBytes =
        agentWebClient
            .post()
            .uri("/agent/html-to-pdf")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Internal " + internalToken)
            .bodyValue(Map.of("html", htmlContent))
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(PDF_TIMEOUT)
            .block();

    if (pdfBytes == null || pdfBytes.length == 0) {
      throw new RuntimeException("Puppeteer returned empty PDF");
    }

    log.debug("PdfExportService: Puppeteer PDF 생성 완료 ({} bytes)", pdfBytes.length);
    return pdfBytes;
  }

  /** 기존 Flying Saucer 기반 PDF 생성 — sections → Thymeleaf → XHTML → PDF (하위 호환) */
  private byte[] generatePdfViaFlyingSaucer(ProactiveResult result, String jobName) {
    log.debug("PdfExportService: Flying Saucer sections→PDF (jobName={})", jobName);

    List<Map<String, Object>> templateSections =
        reportRenderUtils.buildTemplateSections(result.sections());
    List<ReportRenderUtils.ChartImage> chartImages =
        reportRenderUtils.renderChartImages(templateSections);
    reportRenderUtils.convertChartCidsToDataUris(templateSections, chartImages);

    String title = result.effectiveTitle(jobName);

    Context ctx = new Context();
    ctx.setVariable("title", title);
    ctx.setVariable("jobName", jobName);
    // AI가 생성한 총평. 화면(ExecutionDetailPage)과 동일한 값을 PDF에도 실어 두 경로의 내용을 맞춘다 (#363).
    // effectiveSummary()를 쓰지 않는 이유: summary가 없으면 첫 섹션 content로 폴백하는데,
    // 그 섹션은 바로 아래에서 다시 렌더되므로 같은 문단이 PDF에 두 번 찍힌다.
    // 화면도 result.summary를 그대로 읽으므로(폴백 없음) 여기서도 원본 값만 사용한다.
    // 섹션 본문과 같은 규칙으로 마크다운을 HTML로 변환한다 — 화면은 ReactMarkdown으로 렌더하므로
    // 여기서 변환하지 않으면 PDF에만 `**굵게**` 같은 원시 기호가 노출된다.
    String summary = result.summary();
    ctx.setVariable(
        "summary",
        summary != null && !summary.isBlank() ? reportRenderUtils.markdownToHtml(summary) : null);
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);
    // 화이트라벨링: PDF 템플릿 푸터의 브랜드명 표기에 사용
    ctx.setVariable("brandName", brandName);

    String xhtml = templateEngine.process("proactive-report-pdf", ctx);

    try {
      ITextRenderer renderer = new ITextRenderer();
      ClassPathResource fontResource = new ClassPathResource(FONT_PATH);
      renderer
          .getFontResolver()
          .addFont(fontResource.getURL().toString(), BaseFont.IDENTITY_H, BaseFont.NOT_EMBEDDED);

      renderer.setDocumentFromString(xhtml);
      renderer.layout();

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      renderer.createPDF(out);

      return out.toByteArray();
    } catch (Exception e) {
      log.error("PdfExportService: Flying Saucer PDF generation failed", e);
      throw new RuntimeException("PDF 생성에 실패했습니다: " + e.getMessage(), e);
    }
  }
}
