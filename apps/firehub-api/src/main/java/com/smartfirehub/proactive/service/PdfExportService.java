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
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);

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
