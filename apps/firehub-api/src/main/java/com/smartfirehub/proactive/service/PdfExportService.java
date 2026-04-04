package com.smartfirehub.proactive.service;

import com.lowagie.text.pdf.BaseFont;
import com.smartfirehub.proactive.dto.ProactiveResult;
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
    List<ReportRenderUtils.ChartImage> chartImages =
        reportRenderUtils.renderChartImages(templateSections);
    reportRenderUtils.convertChartCidsToDataUris(templateSections, chartImages);

    String title = result.effectiveTitle(jobName);

    Context ctx = new Context();
    ctx.setVariable("title", title);
    ctx.setVariable("jobName", jobName);
    ctx.setVariable("generatedAt", LocalDateTime.now().format(DISPLAY_FORMATTER));
    ctx.setVariable("sections", templateSections);

    String html = templateEngine.process("proactive-report-pdf", ctx);

    try {
      ITextRenderer renderer = new ITextRenderer();
      // NanumGothic font for Korean text support
      ClassPathResource fontResource = new ClassPathResource(FONT_PATH);
      renderer
          .getFontResolver()
          .addFont(fontResource.getURL().toString(), BaseFont.IDENTITY_H, BaseFont.NOT_EMBEDDED);

      renderer.setDocumentFromString(html);
      renderer.layout();

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      renderer.createPDF(out);

      return out.toByteArray();
    } catch (Exception e) {
      log.error("PdfExportService: PDF generation failed", e);
      throw new RuntimeException("PDF 생성에 실패했습니다: " + e.getMessage(), e);
    }
  }
}
