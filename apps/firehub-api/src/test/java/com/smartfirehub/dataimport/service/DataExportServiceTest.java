package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportColumnInfo;
import com.smartfirehub.dataimport.dto.ExportEstimate;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.dto.ExportRequest;
import com.smartfirehub.dataimport.dto.ExportResult;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Transactional
class DataExportServiceTest extends IntegrationTestBase {

  @Autowired private DataExportService dataExportService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  @MockitoBean private DataTableRowService dataTableRowService;

  @MockitoBean private AsyncJobService asyncJobService;

  @MockitoBean private AsyncJobRepository asyncJobRepository;

  @MockitoBean private AuditLogService auditLogService;

  /** 비동기 Export 실행 위임 빈 — 테스트에서는 mock으로 대체하여 실제 비동기 실행 없이 동작 */
  @MockitoBean private DataExportAsyncRunner asyncRunner;

  private Long userId;
  private Long datasetId;
  private Long geoDatasetId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "exporttest")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Export Tester")
            .set(USER.EMAIL, "export@test.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Dataset without geometry
    datasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Test Export",
                    "test_exp_data",
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("name", "이름", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("value", "값", "INTEGER", null, true, false, null)),
                    null),
                userId)
            .id();

    // Dataset with geometry
    geoDatasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Geo Export",
                    "test_geo_exp",
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("label", "라벨", "TEXT", null, true, false, null),
                        new DatasetColumnRequest(
                            "geom", "좌표", "GEOMETRY", null, true, false, null)),
                    null),
                userId)
            .id();

    // Default mocks
    when(dataTableRowService.countRows(anyString(), anyList(), any(), anyMap())).thenReturn(10L);

    when(dataTableRowService.queryData(
            eq("test_exp_data"),
            anyList(),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap()))
        .thenReturn(
            List.of(
                Map.<String, Object>of("name", "테스트1", "value", 100),
                Map.<String, Object>of("name", "테스트2", "value", 200)))
        .thenReturn(List.of());

    when(dataTableRowService.queryData(
            eq("test_geo_exp"),
            anyList(),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap()))
        .thenReturn(
            List.of(
                Map.<String, Object>of(
                    "label", "서울시청", "geom", "{\"type\":\"Point\",\"coordinates\":[126.97,37.56]}"),
                Map.<String, Object>of(
                    "label",
                    "부산시청",
                    "geom",
                    "{\"type\":\"Point\",\"coordinates\":[129.07,35.18]}")))
        .thenReturn(List.of());

    when(asyncJobRepository.findActiveByUserAndJobType(anyLong(), anyString()))
        .thenReturn(List.of());
  }

  // === estimateExport tests ===

  @Test
  void estimateExport_returnsCorrectRowCountAndAsyncFlag() {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    ExportEstimate estimate = dataExportService.estimateExport(datasetId, request);

    assertThat(estimate.rowCount()).isEqualTo(10);
    assertThat(estimate.async()).isFalse();
    assertThat(estimate.hasGeometryColumn()).isFalse();
    assertThat(estimate.columns()).hasSize(2);
  }

  @Test
  void estimateExport_withGeometryColumn_detectsGeometry() {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    ExportEstimate estimate = dataExportService.estimateExport(geoDatasetId, request);

    assertThat(estimate.hasGeometryColumn()).isTrue();
    assertThat(estimate.columns()).anyMatch(ExportColumnInfo::isGeometry);
  }

  // === Sync export: CSV ===

  @Test
  void exportDataset_csv_returnsSyncResultWithBom() throws Exception {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    ExportResult result =
        dataExportService.exportDataset(
            datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    assertThat(result.streamingBody()).isNotNull();
    assertThat(result.filename()).contains("Test_Export").endsWith(".csv");
    assertThat(result.contentType()).isEqualTo("text/csv; charset=UTF-8");

    byte[] bytes = writeToBytes(result.streamingBody());
    assertThat(bytes[0]).isEqualTo((byte) 0xEF);
    assertThat(bytes[1]).isEqualTo((byte) 0xBB);
    assertThat(bytes[2]).isEqualTo((byte) 0xBF);

    String content = new String(bytes, StandardCharsets.UTF_8);
    assertThat(content).contains("이름");
    assertThat(content).contains("테스트1");
  }

  // === Sync export: Excel ===

  @Test
  void exportDataset_excel_returnsValidXlsx() throws Exception {
    ExportRequest request = new ExportRequest(ExportFormat.EXCEL, null, null, null);
    ExportResult result =
        dataExportService.exportDataset(
            datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    assertThat(result.streamingBody()).isNotNull();
    assertThat(result.filename()).endsWith(".xlsx");
    assertThat(result.contentType()).contains("spreadsheetml");

    byte[] bytes = writeToBytes(result.streamingBody());
    // XLSX magic bytes (PK zip header)
    assertThat(bytes[0]).isEqualTo((byte) 0x50);
    assertThat(bytes[1]).isEqualTo((byte) 0x4B);
  }

  // === Sync export: GeoJSON ===

  @Test
  void exportDataset_geojson_returnsFeatureCollection() throws Exception {
    ExportRequest request = new ExportRequest(ExportFormat.GEOJSON, null, null, "geom");
    ExportResult result =
        dataExportService.exportDataset(
            geoDatasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    assertThat(result.streamingBody()).isNotNull();
    assertThat(result.filename()).endsWith(".geojson");

    byte[] bytes = writeToBytes(result.streamingBody());
    String json = new String(bytes, StandardCharsets.UTF_8);
    assertThat(json).contains("FeatureCollection");
    assertThat(json).contains("서울시청");
  }

  // === Column selection ===

  @Test
  void exportDataset_withColumnSelection_usesSelectedColumns() throws Exception {
    when(dataTableRowService.queryData(
            eq("test_exp_data"),
            eq(List.of("name")),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap()))
        .thenReturn(List.of(Map.<String, Object>of("name", "선택테스트")))
        .thenReturn(List.of());

    ExportRequest request = new ExportRequest(ExportFormat.CSV, List.of("name"), null, null);
    ExportResult result =
        dataExportService.exportDataset(
            datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    byte[] bytes = writeToBytes(result.streamingBody());
    String content = new String(bytes, StandardCharsets.UTF_8);
    assertThat(content).contains("이름");
    assertThat(content).doesNotContain("값");
  }

  // === Search filter ===

  @Test
  void exportDataset_withSearch_passesSearchToQuery() throws Exception {
    when(dataTableRowService.countRows(eq("test_exp_data"), anyList(), eq("검색어"), anyMap()))
        .thenReturn(5L);
    when(dataTableRowService.queryData(
            eq("test_exp_data"),
            anyList(),
            anyList(),
            eq("검색어"),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap()))
        .thenReturn(List.of(Map.<String, Object>of("name", "결과", "value", 1)))
        .thenReturn(List.of());

    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, "검색어", null);
    ExportResult result =
        dataExportService.exportDataset(
            datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    assertThat(result.streamingBody()).isNotNull();
    // countRows is called eagerly in exportDataset, queryData is lazy inside StreamingResponseBody
    verify(dataTableRowService).countRows(eq("test_exp_data"), anyList(), eq("검색어"), anyMap());

    // Verify queryData is called with search when body is written
    byte[] bytes = writeToBytes(result.streamingBody());
    assertThat(bytes).isNotEmpty();
    verify(dataTableRowService, atLeastOnce())
        .queryData(
            eq("test_exp_data"),
            anyList(),
            anyList(),
            eq("검색어"),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap());
  }

  // === GeoJSON without geometry column ===

  @Test
  void exportDataset_geojsonWithoutGeometry_throwsException() {
    ExportRequest request = new ExportRequest(ExportFormat.GEOJSON, null, null, null);

    assertThatThrownBy(
            () ->
                dataExportService.exportDataset(
                    datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // === Rate limit ===

  @Test
  void exportDataset_rateLimitExceeded_throwsException() {
    when(dataTableRowService.countRows(anyString(), anyList(), any(), anyMap()))
        .thenReturn(100_000L);
    when(asyncJobRepository.findActiveByUserAndJobType(userId, "DATA_EXPORT"))
        .thenReturn(
            List.of(
                createDummyJobStatus("job1"),
                createDummyJobStatus("job2"),
                createDummyJobStatus("job3")));

    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);

    assertThatThrownBy(
            () ->
                dataExportService.exportDataset(
                    datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3개");
  }

  // === Query result export: CSV ===

  @Test
  void exportQueryResult_csv_returnsStreamingBody() throws Exception {
    StreamingResponseBody body =
        dataExportService.exportQueryResult(
            List.of("col1", "col2"),
            List.of(Map.<String, Object>of("col1", "a", "col2", "b")),
            ExportFormat.CSV);

    byte[] bytes = writeToBytes(body);
    String content = new String(bytes, StandardCharsets.UTF_8);
    assertThat(content).contains("col1");
    assertThat(content).contains("\"a\"");
  }

  // === Query result export: Excel ===

  @Test
  void exportQueryResult_excel_returnsValidXlsx() throws Exception {
    StreamingResponseBody body =
        dataExportService.exportQueryResult(
            List.of("col1", "col2"),
            List.of(Map.<String, Object>of("col1", "x", "col2", "y")),
            ExportFormat.EXCEL);

    byte[] bytes = writeToBytes(body);
    assertThat(bytes[0]).isEqualTo((byte) 0x50);
    assertThat(bytes[1]).isEqualTo((byte) 0x4B);
  }

  // === CSV 수식 주입 중화 (WD-156, OWASP CSV Injection) ===

  /** 수식 주입 검증용 데이터셋 — 표시명이 '=' 로 시작하는 TEXT 컬럼 + INTEGER·DECIMAL·DATE 컬럼. */
  private Long createInjectionDataset() {
    Long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Injection Export",
                    "test_inj_exp",
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("t", "=헤더", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("n", "숫자", "INTEGER", null, true, false, null),
                        new DatasetColumnRequest("d", "소수", "DECIMAL", null, true, false, null),
                        new DatasetColumnRequest("dt", "날짜", "DATE", null, true, false, null)),
                    null),
                userId)
            .id();
    when(dataTableRowService.queryData(
            eq("test_inj_exp"),
            anyList(),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            anyMap()))
        .thenReturn(injectionRows())
        .thenReturn(List.of());
    return id;
  }

  /** 위험 문자 6종 + 정상 문자열을 TEXT 에, 음수·날짜를 비문자열 컬럼에 담은 행들. */
  private static List<Map<String, Object>> injectionRows() {
    List<String> texts = List.of("=1+1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", "정상값");
    return texts.stream()
        .map(
            t -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("t", t);
              row.put("n", -5L);
              row.put("d", new BigDecimal("-5.5"));
              row.put("dt", LocalDate.of(2026, 1, 2));
              return row;
            })
        .toList();
  }

  /**
   * BOM 을 떼고 OpenCSV 로 다시 읽는다 — 셀 단위로 정확히 단언하기 위해. 따옴표 안의 {@code \r} 이 줄바꿈({@code \n})으로 바뀌지 않게
   * keepCarriageReturn 을 켠다.
   */
  private static List<String[]> parseCsv(byte[] bytes) throws Exception {
    String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    try (CSVReader reader =
        new CSVReaderBuilder(new StringReader(content)).withKeepCarriageReturn(true).build()) {
      return reader.readAll();
    }
  }

  @Test
  void exportDataset_csv_neutralizesFormulaInTextColumnsAndHeader() throws Exception {
    Long injId = createInjectionDataset();
    ExportResult result =
        dataExportService.exportDataset(
            injId,
            new ExportRequest(ExportFormat.CSV, null, null, null),
            userId,
            "exporttest",
            "127.0.0.1",
            "test-agent");

    List<String[]> lines = parseCsv(writeToBytes(result.streamingBody()));
    // 헤더: '=' 로 시작하는 표시명은 중화, 나머지는 그대로
    assertThat(lines.get(0)).containsExactly("'=헤더", "숫자", "소수", "날짜");
    // TEXT 값: 위험 문자 6종은 ' 접두, 정상 문자열은 그대로
    assertThat(lines.subList(1, lines.size()).stream().map(r -> r[0]).toList())
        .containsExactly("'=1+1", "'+1", "'-1", "'@SUM(A1)", "'\t=1", "'\r=1", "정상값");
    // 비문자열 값: 음수·날짜는 원형 유지
    for (String[] row : lines.subList(1, lines.size())) {
      assertThat(row[1]).isEqualTo("-5");
      assertThat(row[2]).isEqualTo("-5.5");
      assertThat(row[3]).isEqualTo("2026-01-02");
    }
  }

  /** GEOMETRY 값(GeoJSON 문자열, '{' 시작)은 CSV 에서도 원형 그대로다. */
  @Test
  void exportDataset_csv_geometryUnchanged() throws Exception {
    ExportResult result =
        dataExportService.exportDataset(
            geoDatasetId,
            new ExportRequest(ExportFormat.CSV, null, null, null),
            userId,
            "exporttest",
            "127.0.0.1",
            "test-agent");

    List<String[]> lines = parseCsv(writeToBytes(result.streamingBody()));
    assertThat(lines.get(1)[1]).isEqualTo("{\"type\":\"Point\",\"coordinates\":[126.97,37.56]}");
  }

  /** XLSX 는 중화하지 않는다 — 문자열 셀(수식 아님)로 원문 그대로, 숫자도 원형. */
  @Test
  void exportDataset_excel_keepsRawStringCellsWithoutQuotePrefix() throws Exception {
    Long injId = createInjectionDataset();
    ExportResult result =
        dataExportService.exportDataset(
            injId,
            new ExportRequest(ExportFormat.EXCEL, null, null, null),
            userId,
            "exporttest",
            "127.0.0.1",
            "test-agent");

    try (XSSFWorkbook wb =
        new XSSFWorkbook(new ByteArrayInputStream(writeToBytes(result.streamingBody())))) {
      Row header = wb.getSheetAt(0).getRow(0);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("=헤더");
      Row first = wb.getSheetAt(0).getRow(1);
      assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(first.getCell(0).getStringCellValue()).isEqualTo("=1+1");
      assertThat(first.getCell(1).getStringCellValue()).isEqualTo("-5");
    }
  }

  /** 쿼리 결과 CSV — 값 타입으로 판정: 문자열은 중화, 숫자 음수·날짜는 원형, '=' 로 시작하는 별칭 헤더도 중화. */
  @Test
  void exportQueryResult_csv_neutralizesStringValuesOnly() throws Exception {
    List<Map<String, Object>> rows =
        List.of("=1+1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", "정상값").stream()
            .map(
                t -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("=alias", t);
                  row.put("n", -5);
                  row.put("d", new BigDecimal("-5.5"));
                  row.put("dt", LocalDate.of(2026, 1, 2));
                  return row;
                })
            .toList();

    StreamingResponseBody body =
        dataExportService.exportQueryResult(
            List.of("=alias", "n", "d", "dt"), rows, ExportFormat.CSV);

    List<String[]> lines = parseCsv(writeToBytes(body));
    assertThat(lines.get(0)).containsExactly("'=alias", "n", "d", "dt");
    assertThat(lines.subList(1, lines.size()).stream().map(r -> r[0]).toList())
        .containsExactly("'=1+1", "'+1", "'-1", "'@SUM(A1)", "'\t=1", "'\r=1", "정상값");
    for (String[] row : lines.subList(1, lines.size())) {
      assertThat(row[1]).isEqualTo("-5");
      assertThat(row[2]).isEqualTo("-5.5");
      assertThat(row[3]).isEqualTo("2026-01-02");
    }
  }

  /** 쿼리 결과 XLSX 는 중화하지 않는다. */
  @Test
  void exportQueryResult_excel_keepsRawString() throws Exception {
    StreamingResponseBody body =
        dataExportService.exportQueryResult(
            List.of("=alias"),
            List.of(Map.<String, Object>of("=alias", "=1+1")),
            ExportFormat.EXCEL);

    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(writeToBytes(body)))) {
      assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("=alias");
      assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
      assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("=1+1");
    }
  }

  // === File download access denied ===

  @Test
  void getExportFile_notOwner_throwsAccessDenied() {
    when(asyncJobRepository.findById("test-job"))
        .thenReturn(
            Optional.of(
                new AsyncJobStatusResponse(
                    "test-job",
                    "DATA_EXPORT",
                    "COMPLETED",
                    100,
                    "done",
                    Map.of("filePath", "/tmp/test.csv"),
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    999L)));

    assertThatThrownBy(() -> dataExportService.getExportFile("test-job", userId))
        .isInstanceOf(AccessDeniedException.class);
  }

  // === Audit log verification ===

  @Test
  void exportDataset_sync_callsAuditLog() {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    dataExportService.exportDataset(
        datasetId, request, userId, "exporttest", "127.0.0.1", "test-agent");

    verify(auditLogService)
        .log(
            eq(userId),
            eq("exporttest"),
            eq("DATA_EXPORT"),
            eq("dataset"),
            eq(String.valueOf(datasetId)),
            contains("CSV"),
            eq("127.0.0.1"),
            eq("test-agent"),
            eq("SUCCESS"),
            isNull(),
            any());
  }

  // === Helpers ===

  private byte[] writeToBytes(StreamingResponseBody body) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    body.writeTo(baos);
    return baos.toByteArray();
  }

  private AsyncJobStatusResponse createDummyJobStatus(String jobId) {
    return new AsyncJobStatusResponse(
        jobId,
        "DATA_EXPORT",
        "EXPORTING",
        50,
        "processing",
        Map.of(),
        null,
        LocalDateTime.now(),
        LocalDateTime.now(),
        userId);
  }
}
