package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tibero 테넌트 증분 SQL 단계 개방 단위 테스트(WD-77 Task 10).
 *
 * <p>고정하는 것: 후보는 Tibero 시계({@link TiberoIncrementalCursor})에서 SQL 실행 <b>전에</b> 잡고(PG 함수는 부르지 않는다),
 * {@code {{last_run_at}}} 은 Tibero 리터럴로 치환되며, 출력·입력 테이블 행 트리거를 실행 전에 fail-closed 로 보장하고, 책갈피는 성공
 * 뒤에만 후보로 전진하며, 전체 재생성 선행문은 Tibero 판 DELETE 다. PG 테넌트는 기존 경로 그대로다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoIncrementalTest {

  private static final long TENANT_ID = 5L;
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final Long STEP_ID = 100L;
  private static final String OUTPUT_TABLE = "orders";

  private static final String INCREMENTAL_SELECT =
      "SELECT k, v FROM genia_t5.src_orders WHERE \"_UPDATED_AT\" >= {{last_run_at}}";

  /** 직전 책갈피(UTC) / 이번 실행 후보. */
  private static final OffsetDateTime LAST =
      OffsetDateTime.of(2026, 10, 24, 1, 2, 3, 123_456_000, ZoneOffset.UTC);

  private static final OffsetDateTime CANDIDATE =
      OffsetDateTime.of(2026, 10, 24, 4, 26, 39, 370_111_000, ZoneOffset.UTC);

  @Mock PipelineStepRepository stepRepository;
  @Mock PipelineExecutionRepository executionRepository;
  @Mock PipelineExecutionRegistry executionRegistry;
  @Mock PipelineRepository pipelineRepository;
  @Mock DataTableService dataTableService;
  @Mock DataTableRowService dataTableRowService;
  @Mock DatasetRepository datasetRepository;
  @Mock DatasetColumnRepository columnRepository;
  @Mock SqlColumnProbe sqlColumnProbe;
  @Mock SqlScriptExecutor sqlExecutor;
  @Mock PythonScriptExecutor pythonExecutor;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @Mock ApiCallExecutor apiCallExecutor;
  @Mock AiClassifyExecutor aiClassifyExecutor;
  @Mock ApiConnectionService apiConnectionService;
  @Mock ObjectMapper objectMapper;
  @Mock PermissionChecker permissionChecker;
  @Mock TempDatasetService tempDatasetService;
  @Mock ExecutorClient executorClient;
  @Mock DbExecutorClient dbExecutorClient;

  private final DataStoreResolver dataStoreResolver = mock(DataStoreResolver.class);
  private final SqlValidator pgValidator = spy(new SqlValidator("data", true));

  @Spy
  PipelineDataSchema pipelineDataSchema = new PipelineDataSchema(dataStoreResolver, pgValidator);

  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;
  // 증분 스텝 겹친 실행 직렬화(WD-223) — 목 기본값(null 잠금)으로 둔다. 러너가 null 잠금을 닫지 않는다.
  @Mock IncrementalStepLock incrementalStepLock;
  @Mock TiberoIncrementalCursor tiberoIncrementalCursor;
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void tiberoTenant() {
    TenantContext.set(TENANT_ID);
    lenient().when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    lenient()
        .when(pipelineSecurityGate.runAs(any()))
        .thenAnswer(
            inv -> {
              Long u = inv.getArgument(0);
              return new PipelineSecurityGate.RunAs(u, Clearance.none(u, TENANT_ID));
            });
    lenient()
        .when(datasetRepository.findTableNameById(OUTPUT_ID))
        .thenReturn(Optional.of(OUTPUT_TABLE));
    lenient().when(tiberoIncrementalCursor.captureCandidate()).thenReturn(CANDIDATE);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private static final String MISSING_PRIVILEGE =
      "이 워크스페이스의 Tibero 계정에 트리거 생성 권한이 없어 이 단계를 실행할 수 없습니다. "
          + "운영자에게 다음 문장 실행을 요청해 주세요: GRANT CREATE TRIGGER TO \"GENIA_T5\"";

  @Test
  void
      incrementalMerge_capturesTiberoCandidateBeforeRun_substitutesTiberoLiteral_advancesAfterSuccess() {
    stubCursor(LAST, false);
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    stubExecuteSuccess();

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    // 직전 책갈피가 Tibero 식(UTC instant 를 서버 벽시계로 변환, 마이크로초)으로 들어간다 — PG 리터럴이 아니다.
    String expectedSql =
        INCREMENTAL_SELECT.replace(
            "{{last_run_at}}",
            "CAST(TIMESTAMP '2026-10-24 01:02:03.123456 +00:00' AT TIME ZONE"
                + " TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') AS TIMESTAMP)");
    assertThat(sent.query())
        .isEqualTo(
            TiberoPipelineSql.mergeSelect(
                "GENIA_T5", OUTPUT_TABLE, List.of("k", "v"), List.of("k"), expectedSql));
    assertThat(sent.query()).doesNotContain("::timestamptz");
    assertThat(sent.preStatements()).isEmpty();
    verify(executionRepository).setInjectedLastRunAt(STEP_EXEC_ID, LAST);
    // 후보는 실행 전, 전진은 성공 뒤 — 순서가 설계의 핵심 불변식이다.
    var order = inOrder(tiberoIncrementalCursor, dbExecutorClient, stepRepository);
    order.verify(tiberoIncrementalCursor).captureCandidate();
    order.verify(dbExecutorClient).executeSql(anyString(), anyList());
    order.verify(stepRepository).advanceCursor(STEP_ID, CANDIDATE, false, OUTPUT_ID);
    // PG 시계 함수는 Tibero 테넌트에서 부르지 않는다.
    verifyNoInteractions(incrementalCursorService, executorClient, sqlExecutor, sqlColumnProbe);
  }

  @Test
  void incrementalMerge_ensuresRowTriggersOnOutputAndInputTablesBeforeExecuting() {
    stubCursor(LAST, false);
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    stubExecuteSuccess();

    run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    var order = inOrder(dataTableService, dbExecutorClient);
    // 출력·입력 테이블을 한 번에(한 접속) 보장한다 — 출력이 먼저다.
    order.verify(dataTableService).ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS"));
    order.verify(dbExecutorClient).executeSql(anyString(), anyList());
  }

  /** 출력과 입력이 같은 테이블이어도 한 번만 보장한다. */
  @Test
  void incrementalMerge_sameTableAsInputAndOutput_ensuredOnce() {
    stubCursor(LAST, false);
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    stubExecuteSuccess();

    run(
        sqlStep(
            "SELECT k, v FROM genia_t5.orders WHERE \"_UPDATED_AT\" >= {{last_run_at}}", "MERGE"));

    verify(dataTableService).ensureRowTriggers(List.of("ORDERS"));
  }

  @Test
  void incrementalMerge_missingTriggerPrivilege_failsClosedWithTask8Message() {
    stubCursor(LAST, false);
    doThrow(new DataStoreFeatureUnsupportedException(MISSING_PRIVILEGE))
        .when(dataTableService)
        .ensureRowTriggers(any());

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo(MISSING_PRIVILEGE);
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList());
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
  }

  /**
   * 출력·입력을 함께 보장하는 호출이 거부되면(예: 입력 하나만 트리거가 없고 권한 부족) 같은 안내로 실패한다. 어느 테이블이 모자란지 가리는 판정은 저장소
   * 몫이다({@code
   * TiberoDataTableStoreTriggerTest#ensureRowTriggers_oneMissingWithoutPrivilege_422_noDdl}).
   */
  @Test
  void incrementalMerge_inputTableTriggerMissing_failsClosed() {
    stubCursor(LAST, false);
    doThrow(new DataStoreFeatureUnsupportedException(MISSING_PRIVILEGE))
        .when(dataTableService)
        .ensureRowTriggers(List.of("ORDERS", "SRC_ORDERS"));

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo(MISSING_PRIVILEGE);
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList());
  }

  /** 전체 재생성 예약: 책갈피를 무시하고(최소 리터럴) Tibero 판 DELETE 를 선행문으로 보낸다 — PG DELETE 형식이 아니다. */
  @Test
  void fullRebuild_usesMinLiteralAndTiberoDeleteAllPreStatement_thenReleasesReservation() {
    stubCursor(LAST, true);
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    stubExecuteSuccess();

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("COMPLETED");
    SentSql sent = captureSent();
    assertThat(sent.query()).contains("TIMESTAMP '0001-01-01 00:00:00.000000'");
    assertThat(sent.preStatements())
        .containsExactly(TiberoPipelineSql.deleteAll("GENIA_T5", OUTPUT_TABLE));
    assertThat(sent.preStatements()).containsExactly("DELETE FROM \"GENIA_T5\".\"ORDERS\"");
    verify(executionRepository).setInjectedLastRunAt(eq(STEP_EXEC_ID), isNull());
    verify(stepRepository).advanceCursor(STEP_ID, CANDIDATE, true, OUTPUT_ID);
  }

  @Test
  void executionFailure_doesNotAdvanceCursor() {
    stubCursor(LAST, false);
    stubProbe("K", "V");
    stubOutputColumns(col("k", true), col("v", false));
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(new SqlExecuteResult(false, null, null, 0, "", "실패", null, null));

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("FAILED");
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
  }

  /** 후보를 못 읽으면 SQL 을 실행하지 않는다(책갈피 없이 도는 증분은 누락을 낳는다). */
  @Test
  void candidateFailure_failsBeforeExecuting() {
    stubCursor(LAST, false);
    when(tiberoIncrementalCursor.captureCandidate())
        .thenThrow(
            new com.smartfirehub.pipeline.exception.ScriptExecutionException(
                "Tibero 증분 커서 후보를 읽지 못했습니다"));

    String status = run(sqlStep(INCREMENTAL_SELECT, "MERGE"));

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).contains("Tibero 증분 커서");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList());
  }

  /** PG 테넌트: PG 함수 후보·PG 리터럴 그대로, Tibero 커서·트리거 보장은 건드리지 않는다. */
  @Test
  void pgTenant_incremental_usesPgCandidateAndLiteral_neverTouchesTibero() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    stubCursor(LAST, false);
    when(incrementalCursorService.captureCandidate()).thenReturn(CANDIDATE);

    run(
        sqlStep(
            "INSERT INTO data.orders (amount) SELECT amount FROM data.src WHERE _updated_at >="
                + " {{last_run_at}}",
            "APPEND"));

    verify(incrementalCursorService).captureCandidate();
    verifyNoInteractions(tiberoIncrementalCursor, dbExecutorClient);
    verify(dataTableService, never()).ensureRowTriggers(any());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sql.capture());
    assertThat(sql.getValue()).contains("'2026-10-24T01:02:03.123456Z'::timestamptz");
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private record SentSql(String query, List<String> preStatements) {}

  private String run(PipelineStepResponse step) {
    return runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, false);
  }

  @SuppressWarnings("unchecked")
  private SentSql captureSent() {
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> pre = ArgumentCaptor.forClass(List.class);
    verify(dbExecutorClient).executeSql(query.capture(), pre.capture());
    return new SentSql(query.getValue(), pre.getValue());
  }

  private String failureMessage() {
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    return error.getValue();
  }

  private void stubCursor(OffsetDateTime last, boolean fullRebuild) {
    when(stepRepository.findCursor(STEP_ID))
        .thenReturn(Optional.of(new StepCursor(last, fullRebuild, OUTPUT_ID)));
  }

  private void stubProbe(String... names) {
    List<ProbeColumn> columns =
        Arrays.stream(names)
            .map(n -> new ProbeColumn(n, Types.VARCHAR, "VARCHAR", 100, 0))
            .toList();
    when(dbExecutorClient.probe(anyString()))
        .thenReturn(new ProbeResult(true, columns, null, null));
  }

  private void stubOutputColumns(DatasetColumnResponse... columns) {
    when(columnRepository.findByDatasetId(OUTPUT_ID)).thenReturn(List.of(columns));
  }

  private void stubExecuteSuccess() {
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new SqlExecuteResult(true, List.of(), List.of(), 2, "2 rows", null, null, null));
  }

  private static PipelineStepResponse sqlStep(String sql, String strategy) {
    return new PipelineStepResponse(
        STEP_ID, "s1", null, "SQL", sql, OUTPUT_ID, null, List.of(), List.of(), 0, strategy, null,
        null, null, null);
  }

  private static DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
