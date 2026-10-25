package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ScriptTokenIssuer;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tibero 테넌트의 PYTHON 단계 러너 흐름(WD-78, 스펙 §5).
 *
 * <ul>
 *   <li>순서: 출력 등급 판정 → {@code prepareForRun}(슬롯·GRANT) → 세대 읽기 → 스크립트 토큰 발급 → 스테이징 → 실행 → 옮기기. 세대는
 *       {@code prepareForRun} 뒤에 읽는다(등급 변경이 GRANT 전에 세대를 올리므로).
 *   <li>실행기 켬: 요청에 {@code script_token} 과 스테이징 이름이 간다. REPLACE {@code finishReplace}, APPEND 도
 *       스테이징을 거쳐 {@code finishAppend}. 실패하면 스테이징만 버린다.
 *   <li>실행기 끔: 자식 프로세스는 {@code runTibero}(토큰) 로 띄우고, stdout 출력은 스테이징 적재({@code loadStaging}) 뒤 옮긴다.
 *   <li>세대가 없거나 토큰을 발급하지 못하면 실행하지 않는다(fail-closed, 한국어 문구).
 *   <li>PG 테넌트 요청 본문에는 {@code script_token} 키 자체가 없다(D4).
 *   <li>출력 등급 규칙(e6fac3dc: 선언 입력 + 자기 출력의 rank 하나로 출력 등급과 슬롯을 함께 정함)은 Tibero 에서도 같다(D5).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoPythonTest {

  private static final long TENANT_ID = 5L;
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long STEP_ID = 300L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final Long INPUT_ID = 11L;
  private static final String OUTPUT_TABLE = "orders";
  private static final String STG = "STG_0123456789ABCDEF01234567";
  private static final String SCRIPT = "import genia_db\nprint(1)";
  private static final String TOKEN = "v1.5.4.9.1999999999.c2lnbmF0dXJl";
  private static final int SLOT = 4;
  private static final int READ_RANK = 2;
  private static final long EPOCH = 9L;

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
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrants;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;
  @Mock ScriptTokenIssuer scriptTokenIssuer;
  @Mock TenantDataStoreRepository tenantDataStoreRepository;

  @InjectMocks PipelineAsyncRunner runner;

  private PipelineSecurityGate.RunAs runAs;

  @BeforeEach
  void setUp() {
    TenantContext.set(TENANT_ID);
    lenient().when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    runAs = new PipelineSecurityGate.RunAs(USER_ID, Clearance.none(USER_ID, TENANT_ID));
    lenient().when(pipelineSecurityGate.runAs(USER_ID)).thenReturn(runAs);
    lenient()
        .when(pipelineSecurityGate.pythonReadRank(eq(OUTPUT_ID), any(), eq(runAs)))
        .thenReturn(READ_RANK);
    lenient()
        .when(permissionChecker.hasPermission(USER_ID, "pipeline:python_execute"))
        .thenReturn(true);
    lenient()
        .when(datasetRepository.findTableNameById(OUTPUT_ID))
        .thenReturn(Optional.of(OUTPUT_TABLE));
    lenient()
        .when(columnRepository.findByDatasetId(OUTPUT_ID))
        .thenReturn(
            List.of(
                new DatasetColumnResponse(
                    null, "name", null, "TEXT", null, true, false, null, 0, false)));
    lenient().when(dataTableService.createTempTable(OUTPUT_TABLE)).thenReturn(STG);
    lenient().when(pythonReadGrants.prepareForRun(any(), anyInt())).thenReturn(SLOT);
    lenient()
        .when(tenantDataStoreRepository.findScriptTokenEpoch(TENANT_ID))
        .thenReturn(Optional.of(EPOCH));
    lenient()
        .when(scriptTokenIssuer.issue(eq(TENANT_ID), eq(SLOT), eq(EPOCH), any()))
        .thenReturn(TOKEN);
    lenient()
        .when(outputTableSessionLock.callLocked(anyString(), any()))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ── 실행기 켬 ─────────────────────────────────────────────────────────────────────────

  @Test
  void executorReplace_preparesSlot_thenReadsEpoch_thenIssuesToken_thenStagesAndMoves() {
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 3));

    String status = run(pyStep("REPLACE", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    InOrder order =
        inOrder(
            pythonReadGrants,
            tenantDataStoreRepository,
            scriptTokenIssuer,
            dataTableService,
            executorClient);
    order.verify(pythonReadGrants).prepareForRun(runAs.clearance(), READ_RANK);
    order.verify(tenantDataStoreRepository).findScriptTokenEpoch(TENANT_ID);
    // 만료 = 실행기 Python 시간 제한(1800s) + 여유 60s.
    order.verify(scriptTokenIssuer).issue(TENANT_ID, SLOT, EPOCH, Duration.ofSeconds(1860));
    order.verify(dataTableService).createTempTable(OUTPUT_TABLE);
    order.verify(executorClient).executePython(anyMap(), eq(SLOT));
    order.verify(dataTableService).finishReplace(OUTPUT_TABLE, STG, 3L);
    Map<String, Object> request = sentRequest();
    assertThat(request)
        .containsEntry("script_token", TOKEN)
        .containsEntry("output_table", STG)
        .containsEntry("script", SCRIPT);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
    verifyNoInteractions(pythonExecutor, localPythonOutputLoader, dbExecutorClient);
  }

  @Test
  void executorAppend_alsoGoesThroughStaging_thenFinishAppend() {
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 7));

    String status = run(pyStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).createTempTable(OUTPUT_TABLE);
    verify(dataTableService).finishAppend(OUTPUT_TABLE, STG, 7L);
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    assertThat(sentRequest()).containsEntry("output_table", STG);
  }

  @Test
  void executorFailure_dropsStaging_neverMoves() {
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(
            new ExecutorClient.PythonExecuteResult(false, "", 1, "QueryError: 권한 없음", 10L, 0));

    String status = run(pyStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).contains("Python 실행 실패: QueryError: 권한 없음");
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
  }

  /**
   * 코드리뷰 7: 옮기기(finishAppend)가 실패하면 저장소는 스테이징을 지우지 않고 예외만 올린다 — 정리는 호출자(러너 loadPythonOutput 의
   * catch)가 한 번 한다.
   */
  @Test
  void executorMoveFails_callerDropsStagingOnce() {
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 7));
    doThrow(new IllegalStateException("move boom"))
        .when(dataTableService)
        .finishAppend(OUTPUT_TABLE, STG, 7L);

    String status = run(pyStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService, times(1)).dropTempTable(OUTPUT_TABLE, STG);
  }

  @Test
  void executorCancelledBeforeCall_dropsStagingWithoutCallingExecutor() {
    String status =
        runner.executeStep(
            STEP_EXEC_ID, pyStep("APPEND", OUTPUT_ID), PIPELINE_ID, "P", USER_ID, true, () -> true);

    assertThat(status).isEqualTo("CANCELLED");
    verify(executorClient, never()).executePython(anyMap(), anyInt());
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
  }

  // ── fail-closed ──────────────────────────────────────────────────────────────────────

  @Test
  void missingEpoch_failsInKorean_beforeTokenStagingOrRun() {
    when(tenantDataStoreRepository.findScriptTokenEpoch(TENANT_ID)).thenReturn(Optional.empty());

    String status = run(pyStep("REPLACE", OUTPUT_ID), true);

    assertThat(status).isEqualTo("FAILED");
    assertThat(failureMessage()).contains("스크립트 토큰").contains("Python");
    verifyNoInteractions(scriptTokenIssuer, executorClient, pythonExecutor);
    verify(dataTableService, never()).createTempTable(anyString());
  }

  @Test
  void tokenIssueFailure_failsInKorean_beforeStagingOrRun() {
    when(scriptTokenIssuer.issue(anyLong(), anyInt(), anyLong(), any()))
        .thenThrow(
            new IllegalStateException(
                "app.db-executor.script-token-key(DB_EXECUTOR_SCRIPT_TOKEN_KEY)가 설정되지 않아 스크립트 토큰을 발급할 수 없습니다."));

    String status = run(pyStep("REPLACE", OUTPUT_ID), false);

    assertThat(status).isEqualTo("FAILED");
    // 코드리뷰 3: 발급기 예외 문구(설정 키·환경 변수 이름)를 스텝 오류에 싣지 않는다 — 일반 안내만, 자세한 사유는 서버 WARN 로그.
    assertThat(failureMessage())
        .isEqualTo(PythonScriptExecutor.TIBERO_SETUP_MISSING)
        .doesNotContain("app.db-executor")
        .doesNotContain("DB_EXECUTOR_");
    verifyNoInteractions(executorClient, pythonExecutor, localPythonOutputLoader);
    verify(dataTableService, never()).createTempTable(anyString());
  }

  // ── 실행기 끔(로컬) ───────────────────────────────────────────────────────────────────

  @Test
  void localReplace_runsWithTokenEnv_loadsStaging_thenFinishReplace() {
    when(pythonExecutor.runTibero(SCRIPT, TOKEN))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"name\":\"a\"}]", "err-log"));
    when(localPythonOutputLoader.loadStaging(eq(STG), eq("[{\"name\":\"a\"}]"), anyMap()))
        .thenReturn(1L);

    String status = run(pyStep("REPLACE", OUTPUT_ID), false);

    assertThat(status).isEqualTo("COMPLETED");
    // 로컬 만료 = 자식 프로세스 시간 제한(300s) + 여유 60s.
    verify(scriptTokenIssuer).issue(TENANT_ID, SLOT, EPOCH, Duration.ofSeconds(360));
    InOrder order = inOrder(dataTableService, localPythonOutputLoader);
    order.verify(dataTableService).createTempTable(OUTPUT_TABLE);
    order.verify(localPythonOutputLoader).loadStaging(eq(STG), anyString(), anyMap());
    order.verify(dataTableService).finishReplace(OUTPUT_TABLE, STG, 1L);
    verify(pythonExecutor, never()).run(anyString(), anyInt());
    verify(localPythonOutputLoader, never()).load(anyString(), anyString(), any());
    verifyNoInteractions(executorClient);
  }

  @Test
  void executorTokenTtl_followsConfiguredPythonTimeoutPlusMargin() {
    // M2: 실행기 Python 시간 제한을 설정(app.executor.python-timeout-seconds)에서 받는다 — 실행기 쪽을 늘리면 같이 늘린다.
    org.springframework.test.util.ReflectionTestUtils.setField(
        runner, "executorPythonTimeoutSeconds", 3600L);
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 1));

    String status = run(pyStep("REPLACE", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(scriptTokenIssuer).issue(TENANT_ID, SLOT, EPOCH, Duration.ofSeconds(3660));
  }

  @Test
  void localReplace_onTibero_doesNotTakePgOutputTableSessionLock() {
    // M3: PG 세션 잠금은 같은 출력의 PG SQL 비우기+적재와 직렬화하려는 것이라 Tibero 에는 해당하지 않는다(Tibero SQL 은 그 키를 잡지 않는다).
    when(pythonExecutor.runTibero(SCRIPT, TOKEN))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"name\":\"a\"}]", ""));
    when(localPythonOutputLoader.loadStaging(eq(STG), anyString(), anyMap())).thenReturn(1L);

    String status = run(pyStep("REPLACE", OUTPUT_ID), false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).finishReplace(OUTPUT_TABLE, STG, 1L);
    verifyNoInteractions(outputTableSessionLock);
  }

  @Test
  void localAppend_stagesThenFinishAppend() {
    when(pythonExecutor.runTibero(SCRIPT, TOKEN))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"name\":\"a\"}]", ""));
    when(localPythonOutputLoader.loadStaging(eq(STG), anyString(), anyMap())).thenReturn(1L);

    String status = run(pyStep("APPEND", OUTPUT_ID), false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).finishAppend(OUTPUT_TABLE, STG, 1L);
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
  }

  @Test
  void localLoadFailure_dropsStaging() {
    when(pythonExecutor.runTibero(SCRIPT, TOKEN))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"name\":\"a\"}]", ""));
    when(localPythonOutputLoader.loadStaging(eq(STG), anyString(), anyMap()))
        .thenThrow(new com.smartfirehub.pipeline.exception.ScriptExecutionException("적재 실패"));

    String status = run(pyStep("APPEND", OUTPUT_ID), false);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
  }

  // ── PG 불변(D4) ──────────────────────────────────────────────────────────────────────

  @Test
  void pgExecutorRequest_hasNoScriptTokenKey_andNoTokenIsIssued() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 2));

    String status = run(pyStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    Map<String, Object> request = sentRequest();
    assertThat(request).doesNotContainKey("script_token");
    // PG APPEND 는 출력에 직접 적재한다(스테이징·finishAppend 없음).
    assertThat(request).containsEntry("output_table", OUTPUT_TABLE);
    verify(dataTableService, never()).createTempTable(anyString());
    verifyNoInteractions(scriptTokenIssuer, tenantDataStoreRepository);
  }

  @Test
  void pgLocal_runsWithSlotRole_notTokenEnv() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(pythonExecutor.run(SCRIPT, SLOT))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "", ""));

    String status = run(pyStep("APPEND", null), false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(pythonExecutor, never()).runTibero(anyString(), anyString());
    verifyNoInteractions(scriptTokenIssuer, tenantDataStoreRepository);
  }

  // ── 출력 등급 규칙 동일(D5, e6fac3dc) ──────────────────────────────────────────────────

  @Test
  void outputLevelRule_sameRankDrivesOutputLevelAndSlot_beforeAnyWrite_onTibero() {
    when(executorClient.executePython(anyMap(), eq(SLOT)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "log", 0, null, 10L, 1));

    String status = run(pyStep("REPLACE", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    // 읽기 rank 는 실행 주체 자격이 아니라 선언 입력 + 자기 출력으로 정해지고(pythonReadRank), 같은 값이 출력 등급 판정과 슬롯 선택에 쓰인다.
    // 둘 다 스테이징 생성·실행보다 먼저다.
    InOrder order =
        inOrder(pipelineSecurityGate, pythonReadGrants, dataTableService, executorClient);
    order.verify(pipelineSecurityGate).pythonReadRank(OUTPUT_ID, List.of(INPUT_ID), runAs);
    order
        .verify(pipelineSecurityGate)
        .enforcePythonOutputLevel(OUTPUT_ID, STEP_ID, false, runAs, READ_RANK);
    order.verify(pythonReadGrants).prepareForRun(runAs.clearance(), READ_RANK);
    order.verify(dataTableService).createTempTable(OUTPUT_TABLE);
    order.verify(executorClient).executePython(anyMap(), eq(SLOT));
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private String run(PipelineStepResponse step, boolean executorEnabled) {
    return runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, executorEnabled);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> sentRequest() {
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executePython(captor.capture(), eq(SLOT));
    return captor.getValue();
  }

  private String failureMessage() {
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    return error.getValue();
  }

  private static PipelineStepResponse pyStep(String strategy, Long outputDatasetId) {
    return new PipelineStepResponse(
        STEP_ID,
        "py",
        null,
        "PYTHON",
        SCRIPT,
        outputDatasetId,
        null,
        List.of(INPUT_ID),
        List.of(),
        0,
        strategy,
        null,
        null,
        null,
        null);
  }
}
