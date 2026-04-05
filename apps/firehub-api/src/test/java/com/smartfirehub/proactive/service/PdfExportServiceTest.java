package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PdfExportServiceTest extends IntegrationTestBase {

  @Autowired private PdfExportService pdfExportService;

  @MockitoBean private ReportRenderUtils reportRenderUtils;

  @Test
  void generatePdf_returns_valid_pdf_bytes() {
    // given
    List<Map<String, Object>> templateSections = new ArrayList<>();
    Map<String, Object> section = new java.util.HashMap<>();
    section.put("label", "요약");
    section.put("content", "<p>테스트 내용입니다.</p>");
    templateSections.add(section);

    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(templateSections);
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());

    ProactiveResult result =
        new ProactiveResult(
            "테스트 리포트",
            List.of(new ProactiveResult.Section("summary", "요약", "테스트 내용입니다.", "text", null)),
            new ProactiveResult.Usage(100, 200, 300),
            null,
            null);

    // when
    byte[] pdf = pdfExportService.generatePdf(result, "테스트 Job");

    // then
    assertThat(pdf).isNotNull();
    assertThat(pdf.length).isGreaterThan(0);
    // PDF files always start with %PDF
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void generatePdf_with_cards_renders_successfully() {
    // given
    List<Map<String, Object>> templateSections = new ArrayList<>();
    Map<String, Object> section = new java.util.HashMap<>();
    section.put("label", "통계");
    section.put("content", "");
    section.put(
        "cards",
        List.of(
            Map.of("label", "총 건수", "value", "1,234"),
            Map.of("label", "성공", "value", "1,100"),
            Map.of("label", "실패", "value", "134")));
    templateSections.add(section);

    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(templateSections);
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());

    ProactiveResult result =
        new ProactiveResult(
            "통계 리포트",
            List.of(new ProactiveResult.Section("stats", "통계", null, "cards", null)),
            new ProactiveResult.Usage(50, 100, 150),
            null,
            null);

    // when
    byte[] pdf = pdfExportService.generatePdf(result, "통계 Job");

    // then
    assertThat(pdf).isNotNull();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void generatePdf_with_empty_sections_renders_successfully() {
    // given
    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(new ArrayList<>());
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());

    ProactiveResult result =
        new ProactiveResult("빈 리포트", List.of(), new ProactiveResult.Usage(10, 20, 30), null, null);

    // when
    byte[] pdf = pdfExportService.generatePdf(result, "빈 Job");

    // then
    assertThat(pdf).isNotNull();
    assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
  }
}
