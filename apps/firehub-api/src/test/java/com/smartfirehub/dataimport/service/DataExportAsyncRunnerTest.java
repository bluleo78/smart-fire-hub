package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.job.service.AsyncJobService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * DataExportAsyncRunner 단위 테스트.
 *
 * <p>이 테스트는 별도 빈으로 분리된 비동기 Export Runner의 핵심 로직을 동기 방식으로 직접 검증한다:
 *
 * <ol>
 *   <li>정상 내보내기 시 파일이 생성되고 AsyncJobService에 완료 상태가 갱신되는가
 *   <li>내보내기 실패 시 AsyncJobService에 실패 상태가 갱신되는가
 *   <li>감사 로그가 올바르게 기록되는가
 * </ol>
 *
 * <p>@Async 비동기 라우팅 자체는 Spring AOP 동작이 보장하므로 여기서는 순수 로직 정확성만 검증한다. 별도 빈 분리 자체(= 자기호출 우회)는
 * DataExportServiceExtTest의 위임 검증 테스트로 커버한다.
 */
class DataExportAsyncRunnerTest {

  private DataExportAsyncRunner asyncRunner;

  @Mock private DataTableRowService dataTableRowService;
  @Mock private AsyncJobService asyncJobService;
  @Mock private AuditLogService auditLogService;

  private AutoCloseable mockitoSession;
  private Path createdFile;

  @BeforeEach
  void setUp() {
    // Mockito 어노테이션 기반 mock 초기화
    mockitoSession = MockitoAnnotations.openMocks(this);
    asyncRunner = new DataExportAsyncRunner(dataTableRowService, asyncJobService, auditLogService);
  }

  @AfterEach
  void tearDown() throws Exception {
    mockitoSession.close();
    // 테스트 중 생성된 임시 파일 정리
    if (createdFile != null) {
      Files.deleteIfExists(createdFile);
    }
  }

  // -----------------------------------------------------------------------
  // 정상 내보내기 — CSV 파일 생성 및 job 완료 처리
  // -----------------------------------------------------------------------

  /** CSV 내보내기가 성공할 때 파일이 생성되고, AsyncJobService에 완료 상태가 갱신되며, 감사 로그가 기록되어야 한다. */
  @Test
  void executeAsyncExport_csv_createsFileAndCompletesJob() throws Exception {
    String jobId = "test-async-job-csv";
    createdFile = DataExportService.EXPORT_DIR.resolve(jobId + ".csv");

    DatasetResponse dataset = buildDataset(1L, "test_ds", "테스트 데이터셋");
    List<DatasetColumnResponse> columns =
        List.of(buildColumn("name", "이름", "TEXT"), buildColumn("value", "값", "INTEGER"));
    Map<String, String> columnTypes = Map.of("name", "TEXT", "value", "INTEGER");

    when(dataTableRowService.countRows(eq("test_ds"), anyList(), isNull(), anyMap()))
        .thenReturn(2L);
    when(dataTableRowService.queryData(
            eq("test_ds"), anyList(), isNull(), eq(0), anyInt(), isNull(), eq("ASC"), anyMap()))
        .thenReturn(
            List.of(Map.of("name", "홍길동", "value", 100), Map.of("name", "김철수", "value", 200)))
        .thenReturn(List.of());

    asyncRunner.executeAsyncExport(
        jobId,
        dataset,
        columns,
        columnTypes,
        null,
        ExportFormat.CSV,
        null,
        "test.csv",
        1L,
        "testuser",
        "127.0.0.1",
        "test-agent");

    // 파일이 생성되어야 한다
    assertThat(createdFile).exists();
    assertThat(Files.size(createdFile)).isGreaterThan(0);

    // AsyncJobService에 완료 상태가 갱신되어야 한다
    verify(asyncJobService)
        .completeJob(
            eq(jobId),
            argThat(meta -> meta.containsKey("filePath") && meta.containsKey("filename")));

    // 감사 로그가 SUCCESS로 기록되어야 한다
    verify(auditLogService)
        .log(
            eq(1L),
            eq("testuser"),
            eq("DATA_EXPORT"),
            eq("dataset"),
            eq("1"),
            contains("완료"),
            eq("127.0.0.1"),
            eq("test-agent"),
            eq("SUCCESS"),
            isNull(),
            any());
  }

  // -----------------------------------------------------------------------
  // 내보내기 실패 — 예외 발생 시 job 실패 처리
  // -----------------------------------------------------------------------

  /** 데이터 조회 중 예외가 발생하면 AsyncJobService에 실패 상태가 갱신되고, 감사 로그에 FAILURE로 기록되어야 한다. */
  @Test
  void executeAsyncExport_dataQueryFails_failsJobAndLogsFailure() {
    String jobId = "test-async-job-fail";
    createdFile = DataExportService.EXPORT_DIR.resolve(jobId + ".csv");

    DatasetResponse dataset = buildDataset(2L, "fail_ds", "실패 테스트 데이터셋");
    List<DatasetColumnResponse> columns = List.of(buildColumn("col", "컬럼", "TEXT"));
    Map<String, String> columnTypes = Map.of("col", "TEXT");

    when(dataTableRowService.countRows(eq("fail_ds"), anyList(), isNull(), anyMap()))
        .thenReturn(1L);
    // 데이터 조회 시 예외 발생
    when(dataTableRowService.queryData(
            eq("fail_ds"), anyList(), isNull(), anyInt(), anyInt(), isNull(), eq("ASC"), anyMap()))
        .thenThrow(new RuntimeException("DB connection failed"));

    asyncRunner.executeAsyncExport(
        jobId,
        dataset,
        columns,
        columnTypes,
        null,
        ExportFormat.CSV,
        null,
        "fail.csv",
        2L,
        "failuser",
        "127.0.0.1",
        "test-agent");

    // AsyncJobService에 실패 상태가 갱신되어야 한다
    verify(asyncJobService).failJob(eq(jobId), contains("실패"));

    // 감사 로그가 FAILURE로 기록되어야 한다
    verify(auditLogService)
        .log(
            eq(2L),
            eq("failuser"),
            eq("DATA_EXPORT"),
            eq("dataset"),
            eq("2"),
            contains("실패"),
            eq("127.0.0.1"),
            eq("test-agent"),
            eq("FAILURE"),
            anyString(),
            isNull());
  }

  // -----------------------------------------------------------------------
  // Excel 내보내기 — XLSX 파일 생성 확인
  // -----------------------------------------------------------------------

  @Test
  void executeAsyncExport_excel_createsValidXlsxFile() throws Exception {
    String jobId = "test-async-job-xlsx";
    createdFile = DataExportService.EXPORT_DIR.resolve(jobId + ".xlsx");

    DatasetResponse dataset = buildDataset(3L, "xlsx_ds", "Excel 데이터셋");
    List<DatasetColumnResponse> columns = List.of(buildColumn("col1", "컬럼1", "TEXT"));
    Map<String, String> columnTypes = Map.of("col1", "TEXT");

    when(dataTableRowService.countRows(anyString(), anyList(), isNull(), anyMap())).thenReturn(1L);
    when(dataTableRowService.queryData(
            anyString(), anyList(), isNull(), eq(0), anyInt(), isNull(), eq("ASC"), anyMap()))
        .thenReturn(List.of(Map.of("col1", "test")))
        .thenReturn(List.of());

    asyncRunner.executeAsyncExport(
        jobId,
        dataset,
        columns,
        columnTypes,
        null,
        ExportFormat.EXCEL,
        null,
        "test.xlsx",
        3L,
        "user",
        "127.0.0.1",
        "agent");

    // XLSX 파일이 생성되어야 한다 (PK zip 헤더)
    assertThat(createdFile).exists();
    byte[] bytes = Files.readAllBytes(createdFile);
    assertThat(bytes[0]).isEqualTo((byte) 0x50); // 'P'
    assertThat(bytes[1]).isEqualTo((byte) 0x4B); // 'K'
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private DatasetResponse buildDataset(Long id, String tableName, String name) {
    return new DatasetResponse(
        id, name, tableName, null, null, "SOURCE", null, false, null, null, null, null, null, null);
  }

  private DatasetColumnResponse buildColumn(
      String columnName, String displayName, String dataType) {
    return new DatasetColumnResponse(
        null, columnName, displayName, dataType, null, true, false, null, 0, false);
  }
}
