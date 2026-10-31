package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
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
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tibero 테넌트 SQL 단계의 사용자 직접 쓴 INSERT 개방 단위 테스트(WD-77 Task 9).
 *
 * <p>고정하는 것: INSERT 는 실행 전에 쓰기 대상 테이블마다 행 트리거를 보장({@code ensureRowTriggers})한 뒤 db-executor 로 그대로
 * 가고, 트리거 권한이 없으면 Task 8 의 운영자 안내 문구로 실패하며(조용한 누락 없음, 실행기 호출 없음), UPDATE 는 같은 보장을 하되 권한 부족을 경고로만 넘겨
 * 예전처럼 실행되고, DELETE 는 트리거가 필요 없어 건드리지 않으며, PG 테넌트는 이 경로를 전혀 지나지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoInsertTest {

  private static final long TENANT_ID = 5L;
  private static final String SCHEMA = "GENIA_T5";
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final String OUTPUT_TABLE = "orders";

  @Mock PipelineStepRepository stepRepository;
  @Mock PipelineExecutionRepository executionRepository;
  @Mock PipelineExecutionRegistry executionRegistry;
  @Mock PipelineTenantAdmission tenantAdmission;
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

  /** 테넌트 저장소 판정기 — 이 테스트의 유일한 백엔드 입력. @BeforeEach 에서 TIBERO 로 고정한다. */
  private final DataStoreResolver dataStoreResolver = mock(DataStoreResolver.class);

  /** PG 테넌트용 기본 검증기 — Tibero 테넌트에서는 쓰이지 않아야 한다(PipelineDataSchema 가 Tibero 검증기를 고른다). */
  private final SqlValidator pgValidator = spy(new SqlValidator("data", true));

  @Spy
  PipelineDataSchema pipelineDataSchema = new PipelineDataSchema(dataStoreResolver, pgValidator);

  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;
  // 증분 스텝 겹친 실행 직렬화(WD-223) — 목 기본값(null 잠금)으로 둔다. 러너가 null 잠금을 닫지 않는다.
  @Mock IncrementalStepLock incrementalStepLock;
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void tiberoTenant() {
    // 운영에서는 pipelineExecutor 의 TenantContextTaskDecorator 가 승계하는 테넌트 — 여기서는 직접 세운다.
    TenantContext.set(TENANT_ID);
    lenient().when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    // 보안 게이트 판정 자체는 PipelineSqlAccessTest 등 통합 테스트 몫이다 — 여기서는 통과(목 기본값)로 두고 라우팅만 본다.
    // 사용자 SQL 판정 기본값(WD-426) — 아무 데이터셋도 참조하지 않는 허용 결과. 판정 결과를 쓰는 테스트는 자기 스텁으로 덮어쓴다.
    RunnerSqlAccessStubs.allowSqlWithoutDatasetsByDefault(pipelineSecurityGate);
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
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** Task 8 이 던지는 문구와 같은 운영자 안내(권한 없음). */
  private static final String MISSING_PRIVILEGE =
      "이 워크스페이스의 Tibero 계정에 트리거 생성 권한이 없어 이 단계를 실행할 수 없습니다. "
          + "운영자에게 다음 문장 실행을 요청해 주세요: GRANT CREATE TRIGGER TO \"GENIA_T5\"";

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "INSERT INTO genia_t5.orders (amount) SELECT amount FROM genia_t5.src_orders|REPLACE",
        "insert into genia_t5.orders (amount) select amount from genia_t5.src_orders|APPEND",
        "/* 적재 */ INSERT INTO genia_t5.orders (amount) SELECT 1 FROM DUAL|APPEND",
      })
  void userInsert_ensuresTriggerThenRunsVerbatimOnDbExecutor(String sql, String strategy) {
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, strategy), false);

    assertThat(status).isEqualTo("COMPLETED");
    // 트리거 보장이 실행보다 먼저 — 트리거 없이 INSERT 가 나가면 ID 가 비어 실패한다.
    var order = inOrder(dataTableService, dbExecutorClient);
    order.verify(dataTableService).ensureRowTriggers(List.of("ORDERS"));
    order.verify(dbExecutorClient).executeSql(eq(sql), anyList(), nullable(Long.class));
    verify(dbExecutorClient, never()).probe(anyString());
    verifyNoPgExecution();
  }

  @Test
  void userInsert_missingPrivilege_failsWithTask8MessageAndNeverExecutes() {
    String sql = "INSERT INTO genia_t5.orders (amount) SELECT amount FROM genia_t5.src_orders";
    doThrow(new DataStoreFeatureUnsupportedException(MISSING_PRIVILEGE))
        .when(dataTableService)
        .ensureRowTriggers(List.of("ORDERS"));

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo(MISSING_PRIVILEGE);
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
    verifyNoPgExecution();
  }

  @Test
  void userUpdate_ensuresTriggerBestEffort_privilegeMissingOnlyWarnsAndStillRuns() {
    String sql = "UPDATE genia_t5.orders SET amount = 1 WHERE amount IS NULL";
    doThrow(new DataStoreFeatureUnsupportedException(MISSING_PRIVILEGE))
        .when(dataTableService)
        .ensureRowTriggers(List.of("ORDERS"));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    // 이전에도 허용되던 UPDATE 는 권한 없는 테넌트에서 막지 않는다 — _UPDATED_AT 만 운영자가 권한을 줄 때까지 움직이지 않는다.
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).ensureRowTriggers(List.of("ORDERS"));
    assertThat(captureSent().query()).isEqualTo(sql);
    verifyNoPgExecution();
  }

  /** 권한 부족이 아닌 일반 예외(접속·DDL 오류)도 UPDATE 는 막지 않는다. */
  @Test
  void userUpdate_plainRuntimeException_stillRuns() {
    String sql = "UPDATE genia_t5.orders SET amount = 1 WHERE amount IS NULL";
    doThrow(new RuntimeException("접속 불가"))
        .when(dataTableService)
        .ensureRowTriggers(List.of("ORDERS"));
    stubExecuteSuccess();

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("COMPLETED");
    assertThat(captureSent().query()).isEqualTo(sql);
  }

  /** INSERT 는 어떤 예외든 fail-closed — 실행기를 부르지 않는다. */
  @Test
  void userInsert_plainRuntimeException_failsClosed() {
    String sql = "INSERT INTO genia_t5.orders (amount) SELECT amount FROM genia_t5.src_orders";
    doThrow(new RuntimeException("접속 불가"))
        .when(dataTableService)
        .ensureRowTriggers(List.of("ORDERS"));

    String status = run(sqlStep(sql, OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).isEqualTo("접속 불가");
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList(), nullable(Long.class));
  }

  /** 따옴표 대상 이름도 검증기가 접은 물리 이름(대문자)으로 ensureRowTriggers 에 넘어간다. */
  @Test
  void userInsert_quotedTarget_ensuresPhysicalUpperCaseName() {
    stubExecuteSuccess();

    String status =
        run(
            sqlStep(
                "INSERT INTO genia_t5.\"ORDERS\" (amount) SELECT 1 FROM DUAL", OUTPUT_ID, "APPEND"),
            false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).ensureRowTriggers(List.of("ORDERS"));
  }

  /** 첫 단어가 WITH 인 DML 은 트리거를 보장하지 않는다(결정 고정) — 트리거가 없으면 ID 가 NULL 이라 DB 가 크게 실패한다. */
  @Test
  void withLedInsert_isNotEnsured() {
    stubExecuteSuccess();

    run(
        sqlStep(
            "WITH s AS (SELECT 1 AS amount FROM DUAL) INSERT INTO genia_t5.orders (amount) SELECT amount FROM s",
            OUTPUT_ID,
            "APPEND"),
        false);

    verify(dataTableService, never()).ensureRowTriggers(any());
  }

  @Test
  void userDelete_doesNotTouchTrigger() {
    stubExecuteSuccess();

    String status =
        run(sqlStep("DELETE FROM genia_t5.orders WHERE amount < 0", OUTPUT_ID, "APPEND"), false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService, never()).ensureRowTriggers(any());
  }

  @Test
  void selectLoad_doesNotTouchTrigger() {
    stubProbe("AMOUNT");
    stubOutputColumns(col("amount", false));
    stubExecuteSuccess();

    run(sqlStep("SELECT amount FROM genia_t5.src_orders", OUTPUT_ID, "APPEND"), false);

    verify(dataTableService, never()).ensureRowTriggers(any());
  }

  /** PG 테넌트는 같은 INSERT 가 이전과 똑같이 PG 경로로 가고 Tibero 트리거 보장은 불리지 않는다. */
  @Test
  void pgTenant_userInsert_neverEnsuresTrigger() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);

    run(
        sqlStep(
            "INSERT INTO data.orders (amount) SELECT amount FROM data.src_orders",
            OUTPUT_ID,
            "APPEND"),
        false);

    verify(dataTableService, never()).ensureRowTriggers(any());
    verifyNoInteractions(dbExecutorClient);
    // PG 실행 경로(로컬 실행기)가 실제로 불렸다 — 이 테스트가 PG 경로를 지났다는 증거.
    verify(sqlExecutor).execute(anyList(), anyString(), any());
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private record SentSql(String query, List<String> preStatements) {}

  private String run(PipelineStepResponse step, boolean executorEnabled) {
    return runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, executorEnabled);
  }

  @SuppressWarnings("unchecked")
  private SentSql captureSent() {
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> pre = ArgumentCaptor.forClass(List.class);
    verify(dbExecutorClient).executeSql(query.capture(), pre.capture(), nullable(Long.class));
    return new SentSql(query.getValue(), pre.getValue());
  }

  /** 단계 실패로 저장된 오류 문구. */
  private String failureMessage() {
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    return error.getValue();
  }

  /** PG 실행 경로(외부 실행기·로컬 실행기·로컬 probe) 어느 것도 쓰지 않았다. */
  private void verifyNoPgExecution() {
    verifyNoInteractions(executorClient, sqlExecutor, sqlColumnProbe);
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
    when(dbExecutorClient.executeSql(anyString(), anyList(), nullable(Long.class)))
        .thenReturn(
            new SqlExecuteResult(true, List.of(), List.of(), 2, "2 rows", null, null, null));
  }

  private static SqlExecuteResult failure(String error, DbError dbError) {
    return new SqlExecuteResult(false, null, null, 0, "", error, null, dbError);
  }

  private static PipelineStepResponse sqlStep(String sql, Long outputDatasetId, String strategy) {
    return step("SQL", sql, outputDatasetId, strategy, List.of(), 0, "s1");
  }

  private static PipelineStepResponse step(
      String scriptType,
      String content,
      Long outputDatasetId,
      String strategy,
      List<String> dependsOn,
      int order,
      String name) {
    return new PipelineStepResponse(
        100L + order,
        name,
        null,
        scriptType,
        content,
        outputDatasetId,
        null,
        List.of(),
        dependsOn,
        order,
        strategy,
        null,
        null,
        null,
        null);
  }

  private static DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
