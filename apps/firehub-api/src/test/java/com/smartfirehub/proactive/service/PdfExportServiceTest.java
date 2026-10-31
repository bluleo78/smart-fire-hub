package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

  /** PDF에서 텍스트를 추출한다. 바이트 길이나 %PDF 헤더만 보면 "요약이 실제로 실렸는가"를 검증할 수 없어, 렌더된 문자열을 직접 읽는다 (#363). */
  private String extractText(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /**
   * #363 회귀 — htmlContent 없이 Flying Saucer 경로로 생성한 PDF에 summary가 실려야 한다.
   *
   * <p>화면(ExecutionDetailPage)은 result.summary를 요약 블록으로 보여주는데 PDF에는 summary 참조가 아예 없어, 같은 실행을 화면으로
   * 볼 때와 PDF로 받을 때의 내용이 달랐다.
   */
  @Test
  void generatePdf_includes_summary_in_rendered_text() throws Exception {
    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(new ArrayList<>());
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());
    when(reportRenderUtils.markdownToHtml(any()))
        .thenAnswer(inv -> "<p>" + inv.getArgument(0) + "</p>");

    ProactiveResult result =
        new ProactiveResult(
            "요약 포함 리포트",
            List.of(),
            new ProactiveResult.Usage(10, 20, 30),
            null,
            "이번 주 처리량이 지난주 대비 증가했습니다.");

    byte[] pdf = pdfExportService.generatePdf(result, "요약 Job");

    assertThat(extractText(pdf)).contains("이번 주 처리량이 지난주 대비 증가했습니다.");
  }

  /**
   * summary가 없을 때 effectiveSummary()의 폴백(첫 섹션 content)을 쓰지 않는다는 것을 고정한다.
   *
   * <p>폴백을 쓰면 첫 섹션 본문이 요약 블록과 섹션 본문에 두 번 찍힌다. 화면도 result.summary를 그대로 읽으므로(폴백 없음) PDF만 다르게 동작해서도 안
   * 된다.
   */
  @Test
  void generatePdf_without_summary_does_not_duplicate_first_section() throws Exception {
    List<Map<String, Object>> templateSections = new ArrayList<>();
    Map<String, Object> section = new java.util.HashMap<>();
    section.put("label", "상세");
    section.put("content", "<p>중복되면 안 되는 본문</p>");
    templateSections.add(section);

    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(templateSections);
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());

    ProactiveResult result =
        new ProactiveResult(
            "요약 없는 리포트",
            List.of(new ProactiveResult.Section("detail", "상세", "중복되면 안 되는 본문", "text", null)),
            new ProactiveResult.Usage(10, 20, 30),
            null,
            null);

    String text = extractText(pdfExportService.generatePdf(result, "요약 없는 Job"));

    assertThat(text).contains("중복되면 안 되는 본문");
    assertThat(text.split("중복되면 안 되는 본문", -1)).hasSize(2); // 정확히 1회 등장
  }

  /**
   * WD-344 — 머리 시각은 리포트(실행) 완료 시각을 작업 시간대로 찍고, 내려받을 때마다 바뀌지 않는다. 예전엔 PDF 를 만들 때의 현재 시각(JVM 시간대)이 찍혀
   * 지난 리포트도 오늘 날짜로 보였다.
   */
  @Test
  void generatePdf_header_shows_report_completed_time_in_job_timezone() throws Exception {
    when(reportRenderUtils.buildTemplateSections(any())).thenReturn(new ArrayList<>());
    when(reportRenderUtils.renderChartImages(anyList())).thenReturn(List.of());
    ProactiveResult result =
        new ProactiveResult("시각 리포트", List.of(), new ProactiveResult.Usage(1, 1, 2), null, null);
    LocalDateTime completedAtUtc = LocalDateTime.of(2026, 10, 28, 13, 6, 18);

    String first =
        extractText(pdfExportService.generatePdf(result, "시각 Job", completedAtUtc, "Asia/Seoul"));
    String second =
        extractText(pdfExportService.generatePdf(result, "시각 Job", completedAtUtc, "Asia/Seoul"));

    // UTC 13:06 → 서울 22:06, 두 번 받아도 같다
    assertThat(first).contains("2026-10-28 22:06");
    assertThat(second).contains("2026-10-28 22:06");
  }

  /** WD-344 — 시간대 변환·기본값. 작업 시간대가 없거나 잘못됐으면 작업 기본값(Asia/Seoul)으로 찍는다. */
  @Test
  void formatReportedAt_convertsUtcToJobTimezone() {
    LocalDateTime utc = LocalDateTime.of(2026, 10, 28, 13, 6, 18);
    assertThat(PdfExportService.formatReportedAt(utc, "Asia/Seoul")).isEqualTo("2026-10-28 22:06");
    assertThat(PdfExportService.formatReportedAt(utc, "America/New_York"))
        .isEqualTo("2026-10-28 09:06");
    assertThat(PdfExportService.formatReportedAt(utc, null)).isEqualTo("2026-10-28 22:06");
    assertThat(PdfExportService.formatReportedAt(utc, "Not/AZone")).isEqualTo("2026-10-28 22:06");
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
