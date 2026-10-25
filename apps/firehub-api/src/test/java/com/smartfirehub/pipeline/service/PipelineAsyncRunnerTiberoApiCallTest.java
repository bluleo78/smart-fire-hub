package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Tibero 테넌트의 API_CALL 단계 러너 라우팅(WD-77 Task 6).
 *
 * <ul>
 *   <li>실행기 켬: REPLACE·APPEND 모두 스테이징({@code createTempTable}) → 실행기 적재 → REPLACE {@code
 *       finishReplace} / APPEND {@code finishAppend}, 실패하면 {@code dropTempTable}. 실행기에는 스테이징 이름이 적재
 *       대상으로 간다.
 *   <li>실행기 끔: {@code ApiCallExecutor} 에 그대로 위임(러너는 임시 테이블을 만지지 않는다 — 결정 3).
 *   <li>PG 테넌트의 실행기 켬 APPEND 는 출력에 직접 적재 — 스테이징·{@code finishAppend} 없음(PG 불변).
 *   <li>출력 데이터셋이 없는 Tibero API 수집·AI 분류는 임시 데이터셋 자동 생성이 없어 시작 즉시 거부한다.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTiberoApiCallTest {

  private static final long TENANT_ID = 5L;
  private static final Long STEP_EXEC_ID = 900L;
  private static final Long PIPELINE_ID = 77L;
  private static final Long USER_ID = 1L;
  private static final Long OUTPUT_ID = 10L;
  private static final String OUTPUT_TABLE = "orders";
  private static final String STG = "STG_0123456789ABCDEF01234567";

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
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock PipelineSecurityGate pipelineSecurityGate;
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

  @InjectMocks PipelineAsyncRunner runner;

  @BeforeEach
  void setUp() {
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
    lenient()
        .when(objectMapper.convertValue(any(), eq(ApiCallConfig.class)))
        .thenReturn(apiConfig());
    lenient()
        .when(columnRepository.findByDatasetId(OUTPUT_ID))
        .thenReturn(
            List.of(
                new DatasetColumnResponse(
                    null, "name", null, "TEXT", null, true, false, null, 0, false)));
    lenient().when(dataTableService.createTempTable(OUTPUT_TABLE)).thenReturn(STG);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ── 실행기 켬 ─────────────────────────────────────────────────────────────────────────

  @Test
  void tiberoAppend_usesStagingThenFinishAppend_andSendsStagingNameToExecutor() {
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 7, 1, "ok", null, 20L));

    String status = run(apiStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    InOrder order = inOrder(dataTableService, executorClient);
    order.verify(dataTableService).createTempTable(OUTPUT_TABLE);
    order.verify(executorClient).executeApiCall(any());
    order.verify(dataTableService).finishAppend(OUTPUT_TABLE, STG, 7L);
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    verify(dataTableService, never()).dropTempTable(anyString(), anyString());
    assertThat(sentRequest()).containsEntry("output_table", STG);
    verifyNoInteractions(apiCallExecutor, dbExecutorClient);
  }

  @Test
  void tiberoReplace_usesStagingThenFinishReplace() {
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 3, 1, "ok", null, 20L));

    String status = run(apiStep("REPLACE", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).createTempTable(OUTPUT_TABLE);
    verify(dataTableService).finishReplace(OUTPUT_TABLE, STG, 3L);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
    assertThat(sentRequest()).containsEntry("output_table", STG);
  }

  @Test
  void tiberoAppend_executorFailure_dropsStagingAndNeverMoves() {
    when(executorClient.executeApiCall(any()))
        .thenReturn(
            new ExecutorClient.ApiCallExecuteResult(false, 0, 0, null, "upstream 500", 10L));

    String status = run(apiStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
  }

  @Test
  void tiberoAppend_dropFailureDuringCleanup_doesNotMaskTheOriginalError() {
    when(executorClient.executeApiCall(any()))
        .thenReturn(
            new ExecutorClient.ApiCallExecuteResult(false, 0, 0, null, "upstream 500", 10L));
    doThrow(new IllegalStateException("스테이징 삭제 실패"))
        .when(dataTableService)
        .dropTempTable(OUTPUT_TABLE, STG);

    String status = run(apiStep("APPEND", OUTPUT_ID), true);

    // 정리 실패는 삼키고 원래 오류(실행기 실패 사유)가 단계 오류로 남는다.
    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(STEP_EXEC_ID), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    assertThat(error.getValue()).contains("upstream 500").doesNotContain("스테이징 삭제 실패");
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
  }

  @Test
  void tiberoAppend_moveFailure_failsTheStep_andTriesToDropStaging() {
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 7, 1, "ok", null, 20L));
    doThrow(new ScriptExecutionException("출력 테이블 적재 — 시간 초과"))
        .when(dataTableService)
        .finishAppend(OUTPUT_TABLE, STG, 7L);

    String status = run(apiStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("FAILED");
    // 저장소가 이미 지웠더라도 한 번 더 시도한다(없으면 무시되는 연산) — 원본은 건드리지 않는다.
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
  }

  @Test
  void tiberoAppend_cancelledBeforeCall_dropsStagingWithoutCallingExecutor() {
    String status =
        runner.executeStep(
            STEP_EXEC_ID,
            apiStep("APPEND", OUTPUT_ID),
            PIPELINE_ID,
            "P",
            USER_ID,
            true,
            () -> true);

    assertThat(status).isEqualTo("CANCELLED");
    verify(executorClient, never()).executeApiCall(any());
    verify(dataTableService).dropTempTable(OUTPUT_TABLE, STG);
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
  }

  // ── 실행기 끔 · PG 불변 ──────────────────────────────────────────────────────────────

  @Test
  void tiberoLocalMode_delegatesToApiCallExecutor_runnerTouchesNoTempTable() {
    when(apiCallExecutor.execute(
            any(), eq(OUTPUT_TABLE), isNull(), eq("REPLACE"), any(), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(2, "log"));

    String status = run(apiStep("REPLACE", OUTPUT_ID), false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService, never()).createTempTable(anyString());
    verifyNoInteractions(executorClient, dbExecutorClient);
  }

  @Test
  void pgAppendWithExecutor_stillWritesDirectlyToOutput_noStagingNoFinishAppend() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 3, 1, "ok", null, 20L));

    String status = run(apiStep("APPEND", OUTPUT_ID), true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService, never()).createTempTable(anyString());
    verify(dataTableService, never()).finishAppend(anyString(), anyString(), anyLong());
    verify(dataTableService, never()).dropTempTable(anyString(), anyString());
    assertThat(sentRequest()).containsEntry("output_table", OUTPUT_TABLE);
  }

  // ── 출력 없는 단계 ─────────────────────────────────────────────────────────────────────

  @Test
  void tiberoApiCallWithoutOutputDataset_createsTempLikePg_thenLoadsThroughStaging() {
    // Task 7: 출력 없는 API 수집도 PG 와 같이 임시 데이터셋을 자동 생성한다(Task 6 의 사전 거부 해제).
    when(tempDatasetService.createTempDataset(
            anyList(), eq(PIPELINE_ID), anyString(), eq(100L), anyString(), eq(USER_ID)))
        .thenReturn(OUTPUT_ID);
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 3, 1, "ok", null, 20L));

    String status = run(apiStep("REPLACE", null), true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(
            anyList(), eq(PIPELINE_ID), anyString(), eq(100L), anyString(), eq(USER_ID));
    verify(dataTableService).finishReplace(OUTPUT_TABLE, STG, 3L);
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────────────

  private String run(PipelineStepResponse step, boolean executorEnabled) {
    return runner.executeStep(STEP_EXEC_ID, step, PIPELINE_ID, "P", USER_ID, executorEnabled);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> sentRequest() {
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executeApiCall(captor.capture());
    return captor.getValue();
  }

  private static PipelineStepResponse apiStep(String strategy, Long outputDatasetId) {
    return new PipelineStepResponse(
        100L,
        "api",
        null,
        "API_CALL",
        null,
        outputDatasetId,
        null,
        List.of(),
        List.of(),
        0,
        strategy,
        Map.of(),
        null,
        null,
        null);
  }

  private static ApiCallConfig apiConfig() {
    ApiCallConfig.FieldMapping fm =
        new ApiCallConfig.FieldMapping("src_name", "name", "TEXT", null, null, null);
    return new ApiCallConfig(
        "http://api.example.com",
        "GET",
        null,
        null,
        null,
        null,
        "$.data",
        List.of(fm),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
