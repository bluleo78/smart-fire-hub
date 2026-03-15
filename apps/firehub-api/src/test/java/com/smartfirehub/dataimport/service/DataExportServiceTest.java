package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            eq("test_exp_data"), anyList(), any(), anyInt(), anyInt(), any(), any(), anyMap()))
        .thenReturn(
            List.of(
                Map.<String, Object>of("name", "테스트1", "value", 100),
                Map.<String, Object>of("name", "테스트2", "value", 200)))
        .thenReturn(List.of());

    when(dataTableRowService.queryData(
            eq("test_geo_exp"), anyList(), any(), anyInt(), anyInt(), any(), any(), anyMap()))
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
            eq("test_exp_data"), anyList(), eq("검색어"), anyInt(), anyInt(), any(), any(), anyMap()))
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
            eq("test_exp_data"), anyList(), eq("검색어"), anyInt(), anyInt(), any(), any(), anyMap());
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
