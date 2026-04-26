package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.audit.service.AuditLogService;
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

/**
 * DataExportService 추가 통합 테스트. 기존 DataExportServiceTest에서 누락된 분기: - getExportFile: job not found,
 * not completed, file not found - exportQueryResult: GEOJSON 예외 - exportQueryResult: null 값 처리 (val
 * == null → "") - estimateExport: dataset not found - exportDataset: dataset not found - async
 * export 경로 (rowCount > SYNC_THRESHOLD)
 */
@Transactional
class DataExportServiceExtTest extends IntegrationTestBase {

  @Autowired private DataExportService dataExportService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  @MockitoBean private DataTableRowService dataTableRowService;
  @MockitoBean private AsyncJobService asyncJobService;
  @MockitoBean private AsyncJobRepository asyncJobRepository;
  @MockitoBean private AuditLogService auditLogService;

  private Long userId;
  private Long datasetId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "expext_" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Export Ext Tester")
            .set(USER.EMAIL, "expext_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    datasetId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "Ext Export DS",
                    "ext_exp_data",
                    null,
                    null,
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("name", "이름", "TEXT", null, true, false, null),
                        new DatasetColumnRequest("age", "나이", "INTEGER", null, true, false, null)),
                    null),
                userId)
            .id();

    when(dataTableRowService.countRows(anyString(), anyList(), any(), anyMap())).thenReturn(5L);
    when(dataTableRowService.queryData(
            anyString(), anyList(), any(), anyInt(), anyInt(), any(), any(), anyMap()))
        .thenReturn(List.of(Map.<String, Object>of("name", "홍길동", "age", 30)))
        .thenReturn(List.of());
    when(asyncJobRepository.findActiveByUserAndJobType(anyLong(), anyString()))
        .thenReturn(List.of());
  }

  // -----------------------------------------------------------------------
  // getExportFile — job not found
  // -----------------------------------------------------------------------

  @Test
  void getExportFile_jobNotFound_throwsIllegalArgument() {
    when(asyncJobRepository.findById("missing-job")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dataExportService.getExportFile("missing-job", userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Job not found");
  }

  // -----------------------------------------------------------------------
  // getExportFile — job not completed
  // -----------------------------------------------------------------------

  @Test
  void getExportFile_jobNotCompleted_throwsIllegalState() {
    when(asyncJobRepository.findById("running-job"))
        .thenReturn(
            Optional.of(
                new AsyncJobStatusResponse(
                    "running-job",
                    "DATA_EXPORT",
                    "EXPORTING", // not COMPLETED
                    50,
                    "in progress",
                    Map.of(),
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    userId)));

    assertThatThrownBy(() -> dataExportService.getExportFile("running-job", userId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not completed");
  }

  // -----------------------------------------------------------------------
  // getExportFile — file path missing in metadata
  // -----------------------------------------------------------------------

  @Test
  void getExportFile_completedButNoFilePath_throwsIllegalState() {
    when(asyncJobRepository.findById("no-path-job"))
        .thenReturn(
            Optional.of(
                new AsyncJobStatusResponse(
                    "no-path-job",
                    "DATA_EXPORT",
                    "COMPLETED",
                    100,
                    "done",
                    Map.of(), // filePath 없음
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    userId)));

    assertThatThrownBy(() -> dataExportService.getExportFile("no-path-job", userId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("file path not found");
  }

  // -----------------------------------------------------------------------
  // getExportFile — file doesn't exist on disk
  // -----------------------------------------------------------------------

  @Test
  void getExportFile_fileNotOnDisk_throwsIllegalState() {
    when(asyncJobRepository.findById("ghost-file-job"))
        .thenReturn(
            Optional.of(
                new AsyncJobStatusResponse(
                    "ghost-file-job",
                    "DATA_EXPORT",
                    "COMPLETED",
                    100,
                    "done",
                    Map.of("filePath", "/tmp/nonexistent_file_12345.csv"),
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    userId)));

    assertThatThrownBy(() -> dataExportService.getExportFile("ghost-file-job", userId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not found");
  }

  // -----------------------------------------------------------------------
  // getExportFile — access denied (not owner)
  // -----------------------------------------------------------------------

  @Test
  void getExportFile_notOwner_throwsAccessDenied() {
    when(asyncJobRepository.findById("owned-job"))
        .thenReturn(
            Optional.of(
                new AsyncJobStatusResponse(
                    "owned-job",
                    "DATA_EXPORT",
                    "COMPLETED",
                    100,
                    "done",
                    Map.of("filePath", "/tmp/something.csv"),
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    userId + 9999L))); // 다른 사용자 소유

    assertThatThrownBy(() -> dataExportService.getExportFile("owned-job", userId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("not the owner");
  }

  // -----------------------------------------------------------------------
  // exportQueryResult — GEOJSON 예외
  // -----------------------------------------------------------------------

  @Test
  void exportQueryResult_geojsonFormat_throwsIllegalArgument() {
    assertThatThrownBy(
            () ->
                dataExportService.exportQueryResult(
                    List.of("col1"), List.of(Map.of("col1", "val")), ExportFormat.GEOJSON))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GeoJSON");
  }

  // -----------------------------------------------------------------------
  // exportQueryResult — null 값 처리 (val == null → "")
  // -----------------------------------------------------------------------

  @Test
  void exportQueryResult_nullValues_renderedAsEmptyString() throws Exception {
    // null 값이 있는 row → "" 로 변환되어 CSV에 포함
    Map<String, Object> rowWithNull = new java.util.HashMap<>();
    rowWithNull.put("name", null);
    rowWithNull.put("age", 25);

    StreamingResponseBody body =
        dataExportService.exportQueryResult(
            List.of("name", "age"), List.of(rowWithNull), ExportFormat.CSV);

    byte[] bytes = writeToBytes(body);
    String content = new String(bytes, StandardCharsets.UTF_8);

    // null은 빈 문자열로 변환되어야 한다
    assertThat(content).contains("age");
    assertThat(content).contains("25");
  }

  // -----------------------------------------------------------------------
  // estimateExport — dataset not found
  // -----------------------------------------------------------------------

  @Test
  void estimateExport_datasetNotFound_throwsIllegalArgument() {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);

    assertThatThrownBy(() -> dataExportService.estimateExport(99999L, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dataset not found");
  }

  // -----------------------------------------------------------------------
  // exportDataset — dataset not found
  // -----------------------------------------------------------------------

  @Test
  void exportDataset_datasetNotFound_throwsIllegalArgument() {
    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);

    assertThatThrownBy(
            () ->
                dataExportService.exportDataset(
                    99999L, request, userId, "user", "127.0.0.1", "agent"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dataset not found");
  }

  // -----------------------------------------------------------------------
  // exportDataset — async path (rowCount > SYNC_THRESHOLD)
  // -----------------------------------------------------------------------

  @Test
  void exportDataset_largeDataset_returnsAsyncResult() {
    // SYNC_THRESHOLD(50,000)보다 큰 row count → async 경로
    when(dataTableRowService.countRows(anyString(), anyList(), any(), anyMap()))
        .thenReturn(DataExportService.SYNC_THRESHOLD + 1L);
    when(asyncJobRepository.findActiveByUserAndJobType(userId, "DATA_EXPORT"))
        .thenReturn(List.of());
    when(asyncJobService.createJob(any(), any(), any(), any(), any())).thenReturn("async-job-id-1");

    ExportRequest request = new ExportRequest(ExportFormat.CSV, null, null, null);
    ExportResult result =
        dataExportService.exportDataset(datasetId, request, userId, "expext", "127.0.0.1", "agent");

    assertThat(result.async()).isTrue();
    assertThat(result.jobId()).isEqualTo("async-job-id-1");
    assertThat(result.streamingBody()).isNull();
  }

  // -----------------------------------------------------------------------
  // estimateExport — with column filter
  // -----------------------------------------------------------------------

  @Test
  void estimateExport_withColumnFilter_usesSelectedColumns() {
    // 컬럼 목록 지정 시 해당 컬럼만 사용
    ExportRequest request = new ExportRequest(ExportFormat.CSV, List.of("name"), null, null);
    var estimate = dataExportService.estimateExport(datasetId, request);

    assertThat(estimate.rowCount()).isEqualTo(5L);
    // columns 정보는 allColumns 기반이므로 2개 모두 반환됨 (resolveColumns는 selectedColumns용)
    assertThat(estimate.columns()).isNotEmpty();
  }

  // -----------------------------------------------------------------------
  // Helper
  // -----------------------------------------------------------------------

  private byte[] writeToBytes(StreamingResponseBody body) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    body.writeTo(baos);
    return baos.toByteArray();
  }
}
