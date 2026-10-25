package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
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
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.sql.Types;
import java.util.List;
import java.util.Map;
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
 * Tibero 테넌트의 출력 없는 단계(임시 데이터셋 자동 생성) 러너 단위 테스트(WD-77 Task 7, 스펙 §4.5).
 *
 * <p>고정하는 것: 출력 없는 SELECT 가 db-executor probe → TEMP 생성(probe 타입 역매핑·30자 이름) → {@code INSERT …
 * SELECT} 로 실행되고, 같은 SQL 두 번째 실행은 TEMP 를 다시 만들지 않으며(실제 {@link
 * TempDatasetService#hasSchemaChanged}), 예약어 열은 별칭으로 적재된다. API 수집·AI 분류도 출력이 없으면 PG 와 같이 TEMP 를
 * 만든다(Task 6 의 사전 거부 해제).
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoOutputlessTest {

  private static final long TENANT_ID = 5L;
  private static final String SCHEMA = "GENIA_T5";
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long STEP_ID = 100L;
  private static final Long TEMP_ID = 55L;

  /** ptmp_<파이프라인 77>_<단계 100 의 36진수 2s>. */
  private static final String TEMP_TABLE = "ptmp_77_2s";

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
  @Mock PermissionChecker permissionChecker;
  @Mock ExecutorClient executorClient;
  @Mock DbExecutorClient dbExecutorClient;
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

  /** 실제 JSON 변환기 — API_CALL·AI_CLASSIFY 설정 맵을 설정 레코드로 바꾸는 데 쓴다. */
  @Spy ObjectMapper objectMapper = new ObjectMapper();

  private final DataStoreResolver dataStoreResolver = mock(DataStoreResolver.class);
  private final SqlValidator pgValidator = org.mockito.Mockito.spy(new SqlValidator("data", true));

  @Spy
  PipelineDataSchema pipelineDataSchema = new PipelineDataSchema(dataStoreResolver, pgValidator);

  /**
   * 목 세 개를 레코드 하나에 담는다 — 목을 필드로 직접 들고 있으면 {@code @InjectMocks} 가 같은 타입의 러너 의존성(출력 데이터셋 저장소 등)에 이 목을
   * 섞어 넣어, 어느 목이 쓰일지 실행마다 달라진다.
   */
  private record TempMocks(
      DatasetRepository repo, DatasetService datasets, DatasetColumnRepository columns) {}

  private final TempMocks tmp =
      new TempMocks(
          mock(DatasetRepository.class),
          mock(DatasetService.class),
          mock(DatasetColumnRepository.class));

  /**
   * 실제 {@link TempDatasetService}(감시용 스파이) — {@code @InjectMocks} 의 타입 매칭에 섞이지 않게 @BeforeEach 에서 직접
   * 꽂는다.
   */
  private TempDatasetService realTempDatasetService;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void tiberoTenant() {
    realTempDatasetService =
        org.mockito.Mockito.spy(
            new TempDatasetService(
                tmp.repo(),
                tmp.datasets(),
                tmp.columns(),
                new DataTableService(null),
                pipelineDataSchema));
    org.springframework.test.util.ReflectionTestUtils.setField(
        runner, "tempDatasetService", realTempDatasetService);
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
        .when(datasetRepository.findTableNameById(any()))
        .thenAnswer(
            inv -> TEMP_ID.equals(inv.getArgument(0)) ? Optional.of(TEMP_TABLE) : Optional.empty());
    DatasetDetailResponse created =
        new DatasetDetailResponse(
            TEMP_ID, "d", "t", null, null, "TABLE", "TEMP", null, List.of(), 0L, null, null, null,
            false, List.of(), null, null, null, null, List.of(), null);
    lenient().when(tmp.datasets().createPipelineTempDataset(any(), any())).thenReturn(created);
    lenient().when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.empty());
    lenient()
        .when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new SqlExecuteResult(true, List.of(), List.of(), 2, "2 rows", null, null, null));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void outputlessSelect_probesCreatesTempWithMappedTypes_andInsertsSelect() {
    String sql = "SELECT amount, cnt, name, flag FROM genia_t5.src";
    stubProbe(
        new ProbeColumn("AMOUNT", Types.NUMERIC, "NUMBER", 18, 6),
        new ProbeColumn("CNT", Types.NUMERIC, "NUMBER", 10, 0),
        new ProbeColumn("NAME", Types.VARCHAR, "VARCHAR", 100, 0),
        new ProbeColumn("FLAG", Types.NUMERIC, "NUMBER", 1, 0));
    stubTempColumns("amount", "cnt", "name", "flag");

    String status = run(sqlStep(sql, "REPLACE"));

    assertThat(status).as(lastError).isEqualTo("COMPLETED");
    CreateDatasetRequest created = captureCreated();
    assertThat(created.tableName()).isEqualTo(TEMP_TABLE);
    assertThat(created.originType()).isEqualTo("TEMP");
    assertThat(created.sourcePipelineStepId()).isEqualTo(STEP_ID);
    assertThat(created.columns())
        .extracting(DatasetColumnRequest::columnName, DatasetColumnRequest::dataType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("amount", "DECIMAL"),
            org.assertj.core.groups.Tuple.tuple("cnt", "INTEGER"),
            org.assertj.core.groups.Tuple.tuple("name", "TEXT"),
            org.assertj.core.groups.Tuple.tuple("flag", "BOOLEAN"));
    // probe 는 한 번 — TEMP 생성과 적재가 같은 결과를 쓴다. PG probe·실행기는 쓰지 않는다.
    verify(dbExecutorClient, times(1)).probe(sql);
    verifyNoInteractions(sqlColumnProbe, executorClient, sqlExecutor);
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> pre = captor();
    verify(dbExecutorClient).executeSql(query.capture(), pre.capture());
    assertThat(query.getValue())
        .isEqualTo(
            TiberoPipelineSql.insertSelect(
                SCHEMA, TEMP_TABLE, List.of("amount", "cnt", "name", "flag"), sql));
    assertThat(pre.getValue())
        .containsExactly("DELETE FROM \"GENIA_T5\".\"" + TEMP_TABLE.toUpperCase() + "\"");
  }

  @Test
  void outputlessSelect_existingTempWithSameColumns_ensureStepTempReusesIt() {
    String sql = "SELECT amount, name FROM genia_t5.src";
    stubProbe(
        new ProbeColumn("AMOUNT", Types.NUMERIC, "NUMBER", 18, 6),
        new ProbeColumn("NAME", Types.VARCHAR, "VARCHAR", 100, 0));
    // outputDatasetId 가 null 인 단계에 TEMP 가 이미 있고 컬럼이 같다 — ensureStepTemp 의 재사용 분기만 본다. 실제 재실행은
    // PipelineStepRepository 의 coalesce 로 outputDatasetId 가 TEMP id 로 채워져 이 분기를 타지 않는다(별도 이슈).
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));
    when(tmp.columns().findByDatasetId(TEMP_ID))
        .thenReturn(List.of(col("amount", "DECIMAL"), col("name", "TEXT")));
    stubTempColumns("amount", "name");

    String status = run(sqlStep(sql, "REPLACE"));

    assertThat(status).as(lastError).isEqualTo("COMPLETED");
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
    verify(tmp.datasets(), never()).deleteDataset(anyLong());
    verify(realTempDatasetService).hasSchemaChanged(eq(TEMP_ID), anyList());
    verify(dbExecutorClient).executeSql(anyString(), anyList());
    // 재사용 경로도 크래시 흔적 맞춤이 스키마 비교보다 먼저다(WD-256).
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(realTempDatasetService);
    order.verify(realTempDatasetService).reconcileTempDataset(TEMP_ID);
    order.verify(realTempDatasetService).hasSchemaChanged(eq(TEMP_ID), anyList());
  }

  /**
   * 열 타입이 바뀐 재실행은 같은 데이터셋 id 로 제자리 재생성된다(WD-256 — 예전 422 거부 대체). 재생성 뒤 사용자 SQL 이 실행되고, 성공하면 보관본을
   * 버린다. 기존 TEMP 를 쓰기 전에 크래시 흔적 맞춤이 스키마 비교보다 먼저 불린다.
   */
  @Test
  void outputlessSelect_changedColumnType_rebuiltUnderSameIdAndRuns_reconciledFirst() {
    String sql = "SELECT amount FROM genia_t5.src";
    stubProbe(new ProbeColumn("AMOUNT", Types.NUMERIC, "NUMBER", 18, 6));
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));
    // 기존 TEMP 는 amount 가 TEXT — 타입이 바뀌었으니 다시 만들어야 한다.
    when(tmp.columns().findByDatasetId(TEMP_ID)).thenReturn(List.of(col("amount", "TEXT")));
    DatasetService.PipelineTempRebuild rebuild = rebuildOf();
    when(tmp.datasets().rebuildPipelineTempTable(eq(TEMP_ID), anyList())).thenReturn(rebuild);
    stubTempColumns("amount");

    String status = run(sqlStep(sql, "REPLACE"));

    assertThat(status).as(lastError).isEqualTo("COMPLETED");
    verify(tmp.datasets(), never()).deleteDataset(anyLong());
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
    verify(dbExecutorClient).executeSql(anyString(), anyList());
    verify(realTempDatasetService).commitRebuild(rebuild);
    verify(realTempDatasetService, never()).rollbackRebuild(any(), anyLong());
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(realTempDatasetService);
    order.verify(realTempDatasetService).reconcileTempDataset(TEMP_ID);
    order.verify(realTempDatasetService).hasSchemaChanged(eq(TEMP_ID), anyList());
    order.verify(realTempDatasetService).rebuildTempDataset(eq(TEMP_ID), anyList());
  }

  /** 재생성한 TEMP 의 단계가 실패하면 이전 테이블·메타를 되돌린다(WD-238 의 되돌리기가 Tibero 에서도 불린다). */
  @Test
  void outputlessSelect_changedColumnType_stepFails_rollsBackRebuild() {
    String sql = "SELECT amount FROM genia_t5.src";
    stubProbe(new ProbeColumn("AMOUNT", Types.NUMERIC, "NUMBER", 18, 6));
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));
    when(tmp.columns().findByDatasetId(TEMP_ID)).thenReturn(List.of(col("amount", "TEXT")));
    DatasetService.PipelineTempRebuild rebuild = rebuildOf();
    when(tmp.datasets().rebuildPipelineTempTable(eq(TEMP_ID), anyList())).thenReturn(rebuild);
    stubTempColumns("amount");
    when(dbExecutorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new SqlExecuteResult(false, List.of(), List.of(), 0, "", "0 으로 나눔", null, null));

    String status = run(sqlStep(sql, "REPLACE"));

    assertThat(status).isEqualTo("FAILED");
    verify(realTempDatasetService).rollbackRebuild(rebuild, USER_ID);
    verify(realTempDatasetService, never()).commitRebuild(any());
  }

  /** 단계 출력이 이 단계 소유 TEMP 인 비SELECT(사용자가 직접 쓰는 DML)도 ensureStepTemp 를 거치지 않으므로 쓰기 전에 맞춘다. */
  @Test
  void ownedTempAsDmlOutput_reconciledBeforeWrite() {
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));

    run(step("SQL", "DELETE FROM genia_t5.src WHERE 1 = 0", null, null, "APPEND", TEMP_ID));

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(realTempDatasetService, dbExecutorClient);
    order.verify(realTempDatasetService).reconcileTempDataset(TEMP_ID);
    order.verify(dbExecutorClient).executeSql(anyString(), anyList());
  }

  private static DatasetService.PipelineTempRebuild rebuildOf() {
    return new DatasetService.PipelineTempRebuild(
        TEMP_ID, TEMP_TABLE, "rtmp_bak_x", 77L, List.of(), null);
  }

  @Test
  void outputlessSelect_schemaChangeWithTooLongTiberoColumn_failsAndKeepsExistingTemp() {
    // 컬럼 이름 31자 — 옛 TEMP 를 지우기 전에 Tibero 이름 규칙으로 걸러져야 한다.
    String longName = "C" + "X".repeat(30);
    stubProbe(new ProbeColumn(longName, Types.VARCHAR, "VARCHAR", 100, 0));
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));
    when(tmp.columns().findByDatasetId(TEMP_ID)).thenReturn(List.of(col("amount", "TEXT")));

    String status = run(sqlStep("SELECT 1 AS " + longName + " FROM dual", "REPLACE"));

    assertThat(status).isEqualTo("FAILED");
    assertThat(lastError).contains("30자를 넘을 수 없습니다");
    verify(tmp.datasets(), never()).deleteDataset(anyLong());
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList());
  }

  @Test
  void outputlessSelectStar_reservedColumns_loadedUnderAliasesFromOriginalSourceColumns() {
    String sql = "SELECT * FROM genia_t5.src";
    stubProbe(
        new ProbeColumn("ID", Types.NUMERIC, "NUMBER", 19, 0),
        new ProbeColumn("NAME", Types.VARCHAR, "VARCHAR", 100, 0),
        new ProbeColumn("_UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP", 0, 6));
    stubTempColumns("id_1", "name", "updated_at_1");

    String status = run(sqlStep(sql, "APPEND"));

    assertThat(status).as(lastError).isEqualTo("COMPLETED");
    assertThat(captureCreated().columns())
        .extracting(DatasetColumnRequest::columnName, DatasetColumnRequest::dataType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("id_1", "INTEGER"),
            org.assertj.core.groups.Tuple.tuple("name", "TEXT"),
            org.assertj.core.groups.Tuple.tuple("updated_at_1", "TIMESTAMP"));
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(dbExecutorClient).executeSql(query.capture(), anyList());
    // 대상은 별칭 이름, 읽기는 원본 열 이름(src."ID", src."_UPDATED_AT").
    assertThat(query.getValue())
        .contains("(\"ID\", \"ID_1\", \"NAME\", \"UPDATED_AT_1\", \"_UPDATED_AT\") SELECT")
        .contains("src.\"ID\", src.\"NAME\", src.\"_UPDATED_AT\", SYSTIMESTAMP");
  }

  /**
   * 재실행 회귀 고정(WD-229): 스텝 조회가 출력 id 를 TEMP id 로 채워 주는 두 번째 실행부터도, 소유한 TEMP 는 미지정과 똑같이 취급돼 예약어 열 별칭
   * 경로(id → id_1)를 탄다. 예전에는 출력 id 가 채워져 이 경로가 죽어 id_1 이 NULL 로 남았다.
   */
  @Test
  void outputlessSelectStar_rerunWithExistingTemp_stillLoadsAliasesFromSourceColumns() {
    String sql = "SELECT * FROM genia_t5.src";
    stubProbe(
        new ProbeColumn("ID", Types.NUMERIC, "NUMBER", 19, 0),
        new ProbeColumn("NAME", Types.VARCHAR, "VARCHAR", 100, 0),
        new ProbeColumn("_UPDATED_AT", Types.TIMESTAMP, "TIMESTAMP", 0, 6));
    // 기존 TEMP 가 같은 별칭 열로 이미 있다 — 재사용돼야 한다.
    when(tmp.repo().findBySourcePipelineStepId(STEP_ID)).thenReturn(Optional.of(TEMP_ID));
    when(tmp.columns().findByDatasetId(TEMP_ID))
        .thenReturn(
            List.of(col("id_1", "INTEGER"), col("name", "TEXT"), col("updated_at_1", "TIMESTAMP")));
    stubTempColumns("id_1", "name", "updated_at_1");

    // 재실행: 스텝 조회가 출력 id 를 TEMP id 로 채운 상태.
    PipelineStepResponse rerun = step("SQL", sql, null, null, "APPEND", TEMP_ID);

    String status = run(rerun);

    assertThat(status).as(lastError).isEqualTo("COMPLETED");
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
    ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
    verify(dbExecutorClient).executeSql(query.capture(), anyList());
    assertThat(query.getValue())
        .contains("(\"ID\", \"ID_1\", \"NAME\", \"UPDATED_AT_1\", \"_UPDATED_AT\") SELECT")
        .contains("src.\"ID\", src.\"NAME\", src.\"_UPDATED_AT\", SYSTIMESTAMP");
  }

  @Test
  void outputlessSelect_unaliasedExpression_failsWithGuidanceBeforeCreatingTemp() {
    stubProbe(new ProbeColumn("COUNT(*)", Types.NUMERIC, "NUMBER", 0, 0));

    String status = run(sqlStep("SELECT COUNT(*) FROM genia_t5.src", "REPLACE"));

    assertThat(status).isEqualTo("FAILED");
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
    verify(dbExecutorClient, never()).executeSql(anyString(), anyList());
  }

  @Test
  void outputlessSelect_quotedLowercaseAlias_failsBeforeCreatingTemp() {
    stubProbe(new ProbeColumn("amount", Types.NUMERIC, "NUMBER", 10, 0));

    String status = run(sqlStep("SELECT 1 AS \"amount\" FROM dual", "REPLACE"));

    assertThat(status).isEqualTo("FAILED");
    verify(tmp.datasets(), never()).createPipelineTempDataset(any(), any());
  }

  @Test
  void outputlessApiCall_onTibero_createsStableTempLikePg() {
    PipelineStepResponse step =
        step(
            "API_CALL",
            null,
            Map.of(
                "url",
                "https://example.com/api",
                "method",
                "GET",
                "fieldMappings",
                List.of(Map.of("sourceField", "a", "targetColumn", "a", "dataType", "TEXT"))),
            null,
            "APPEND");

    run(step);

    // Task 6 의 사전 거부는 없다 — 임시 데이터셋 자동 생성이 PG 와 같이 이어진다.
    CreateDatasetRequest created = captureCreated();
    assertThat(created.tableName()).isEqualTo(TEMP_TABLE);
    assertThat(created.originType()).isEqualTo("TEMP");
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private String run(PipelineStepResponse step) {
    String status = runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, false);
    if ("FAILED".equals(status)) {
      // 실패 원인을 단언 메시지에 싣는다 — 문구 단언이 없는 테스트의 진단용.
      ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
      verify(executionRepository)
          .updateStepExecution(
              eq(STEP_EXEC_ID), eq("FAILED"), any(), any(), error.capture(), any(), any());
      lastError = error.getValue();
    }
    return status;
  }

  private String lastError;

  private CreateDatasetRequest captureCreated() {
    ArgumentCaptor<CreateDatasetRequest> req = ArgumentCaptor.forClass(CreateDatasetRequest.class);
    verify(tmp.datasets()).createPipelineTempDataset(req.capture(), any());
    return req.getValue();
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<List<String>> captor() {
    return ArgumentCaptor.forClass(List.class);
  }

  private void stubProbe(ProbeColumn... columns) {
    when(dbExecutorClient.probe(anyString()))
        .thenReturn(new ProbeResult(true, List.of(columns), null, null));
  }

  /** 러너가 TEMP 의 컬럼을 읽어 적재 대상과 맞추므로, 만들어진 TEMP 의 컬럼 이름을 돌려주게 한다. */
  private void stubTempColumns(String... names) {
    lenient()
        .when(columnRepository.findByDatasetId(TEMP_ID))
        .thenReturn(java.util.Arrays.stream(names).map(n -> col(n, "TEXT")).toList());
  }

  private static DatasetColumnResponse col(String name, String type) {
    return new DatasetColumnResponse(null, name, null, type, null, true, false, null, 0, false);
  }

  private static PipelineStepResponse sqlStep(String sql, String strategy) {
    return step("SQL", sql, null, null, strategy);
  }

  private static PipelineStepResponse step(
      String scriptType,
      String content,
      Map<String, Object> apiConfig,
      Map<String, Object> aiConfig,
      String strategy) {
    return step(scriptType, content, apiConfig, aiConfig, strategy, null);
  }

  private static PipelineStepResponse step(
      String scriptType,
      String content,
      Map<String, Object> apiConfig,
      Map<String, Object> aiConfig,
      String strategy,
      Long outputDatasetId) {
    return new PipelineStepResponse(
        STEP_ID,
        "s1",
        null,
        scriptType,
        content,
        outputDatasetId,
        null,
        List.of(),
        List.of(),
        0,
        strategy,
        apiConfig,
        aiConfig,
        null,
        null);
  }
}
