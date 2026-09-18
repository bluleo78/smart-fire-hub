package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.AiClassifyConfig.OutputColumn;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.InsertSetStep;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.SelectWhereStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AiClassifyExecutor 단위 테스트. Spring 컨텍스트 없이 Mockito 로 실행한다. jOOQ DSLContext 는 fluent chain 이 많아
 * deep stub 으로 처리한다.
 */
class AiClassifyExecutorTest {

  private AiAgentClient aiAgentClient;
  private DataTableRowService dataTableRowService;
  private DataTableService dataTableService;
  private DatasetRepository datasetRepository;
  private ObjectMapper objectMapper;
  private DSLContext dsl;
  private TransactionTemplate transactionTemplate;

  private AiClassifyExecutor executor;

  @BeforeEach
  void setUp() {
    aiAgentClient = mock(AiAgentClient.class);
    dataTableRowService = mock(DataTableRowService.class);
    dataTableService = mock(DataTableService.class);
    datasetRepository = mock(DatasetRepository.class);
    objectMapper = new ObjectMapper();
    dsl = mock(DSLContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

    // TransactionTemplate 은 mock 이 아니라 실물을 쓴다. mock 이면 execute/executeWithoutResult 가
    // 콜백을 아예 실행하지 않아 캐시 조회·쓰기가 통째로 사라지고, 아래 캐시 단언들이 조용히
    // 무의미해진다 — 이 태스크가 잡으려는 결함과 같은 모양이다. 트랜잭션 매니저만 mock 이라
    // 실제 트랜잭션은 열리지 않지만(getTransaction → null, commit(null) → no-op) 콜백은 돈다.
    transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

    // processBatch 는 테넌트 컨텍스트가 없으면 즉시 실패한다(캐시가 테넌트별 파티션이므로).
    TenantContext.set(1L);

    executor =
        new AiClassifyExecutor(
            aiAgentClient,
            dataTableRowService,
            dataTableService,
            datasetRepository,
            objectMapper,
            dsl,
            transactionTemplate);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  /**
   * 캐시 조회 목 체인을 전부 미스로 세운다.
   *
   * <p>jOOQ 빌더 체인이 길어 테스트마다 복제하면 V103 같은 술어 추가 때 손볼 곳이 그만큼 늘어난다.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubCacheMiss() {
    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(null);
  }

  private PipelineStepResponse buildStep(String loadStrategy, List<Long> inputDatasetIds) {
    return buildStep(loadStrategy, inputDatasetIds, 20);
  }

  private PipelineStepResponse buildStep(
      String loadStrategy, List<Long> inputDatasetIds, int batchSize) {
    AiClassifyConfig config =
        new AiClassifyConfig(
            "Classify rows",
            List.of(new OutputColumn("category", "TEXT"), new OutputColumn("score", "NUMERIC")),
            List.of("id", "text"),
            batchSize,
            "CONTINUE");
    Map<String, Object> aiConfig = objectMapper.convertValue(config, Map.class);
    return new PipelineStepResponse(
        10L,
        "AI Step",
        "desc",
        "AI_CLASSIFY",
        null,
        200L,
        "output_table",
        inputDatasetIds,
        List.of(),
        1,
        loadStrategy,
        null,
        aiConfig,
        null,
        null);
  }

  // -----------------------------------------------------------------------
  // execute() — early exits
  // -----------------------------------------------------------------------

  @Test
  void execute_withMissingOutputDataset_throwsRuntime() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.empty());

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L));

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Output dataset table not found");
  }

  @Test
  void execute_withNullInputDatasetIds_returnsZeroRows() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    PipelineStepResponse step = buildStep("APPEND", null);

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(0);
    assertThat(result.executionLog()).contains("No input rows");
    verify(aiAgentClient, never()).classify(any(), anyLong());
    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
  }

  @Test
  void execute_withEmptyInputDatasetIds_returnsZeroRows() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    PipelineStepResponse step = buildStep("APPEND", List.of());

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(0);
    assertThat(result.executionLog()).contains("No input rows");
  }

  @Test
  void execute_withInputDatasetButNoRows_returnsZeroAndSkipsAi() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(0L);

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(0);
    verify(aiAgentClient, never()).classify(any(), anyLong());
  }

  @Test
  void execute_withInputDatasetNotFound_skipsSource() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(99L)).thenReturn(Optional.empty());

    PipelineStepResponse step = buildStep("APPEND", List.of(99L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(0);
    verify(dataTableRowService, never()).countRows(anyString());
  }

  // -----------------------------------------------------------------------
  // execute() — cache hit path (dsl.select chain returns a cached result)
  // -----------------------------------------------------------------------

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withAllCacheHits_doesNotCallAiAgent() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 42L);
    sourceRow.put("text", "hello");
    when(dataTableRowService.queryData(eq("source_table"), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    // Mock jOOQ select -> cache hit with cached result JSON
    Record1<JSONB> cachedRecord = mock(Record1.class);
    JSONB cachedJson = JSONB.valueOf("{\"category\":\"A\",\"score\":0.9,\"source_id\":42}");
    when(cachedRecord.get(any(org.jooq.Field.class))).thenReturn(cachedJson);

    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectWhereStep whereStep = mock(SelectWhereStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    // V103 이후 캐시 조회에 tenant_id 술어가 하나 더 붙는다 — 체인이 한 단계 길어졌으므로
    // 자기 자신을 돌려주게 해 마지막 fetchOne() 스텁이 계속 유효하도록 한다.
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(cachedRecord);

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(1);
    assertThat(result.executionLog()).contains("1 cached");
    verify(aiAgentClient, never()).classify(any(), anyLong());
    verify(dataTableRowService, atLeastOnce())
        .insertBatch(eq("output_table"), anyList(), anyList(), anyMap());
    // APPEND → no swap/temp table
    verify(dataTableService, never()).createTempTable(anyString());
    verify(dataTableService, never()).swapTable(anyString());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withReplaceStrategy_createsAndSwapsTempTable() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 7L);
    sourceRow.put("text", "world");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    Record1<JSONB> cachedRecord = mock(Record1.class);
    JSONB cachedJson = JSONB.valueOf("{\"category\":\"B\",\"score\":0.5,\"source_id\":7}");
    when(cachedRecord.get(any(org.jooq.Field.class))).thenReturn(cachedJson);

    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectWhereStep whereStep = mock(SelectWhereStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    // V103 이후 캐시 조회에 tenant_id 술어가 하나 더 붙는다 — 체인이 한 단계 길어졌으므로
    // 자기 자신을 돌려주게 해 마지막 fetchOne() 스텁이 계속 유효하도록 한다.
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(cachedRecord);

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(1);
    // REPLACE → 임시 테이블 생성 + 스왑
    verify(dataTableService).createTempTable("output_table");
    verify(dataTableService).swapTable("output_table");
    verify(dataTableRowService).insertBatch(eq("output_table_tmp"), anyList(), anyList(), anyMap());
  }

  // -----------------------------------------------------------------------
  // execute() — cache miss path calls AiAgentClient
  // -----------------------------------------------------------------------

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withCacheMiss_callsAiAgentAndStoresCache() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 5L);
    sourceRow.put("text", "cats");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    // select chain → fetchOne() returns null (cache miss)
    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    // V103 이후 캐시 조회에 tenant_id 술어가 하나 더 붙는다 — 체인이 한 단계 길어졌으므로
    // 자기 자신을 돌려주게 해 마지막 fetchOne() 스텁이 계속 유효하도록 한다.
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(null);

    // insert chain — return a deep-stubbed chain that always resolves
    InsertSetStep insertSetStep = mock(InsertSetStep.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(dsl.insertInto(any(org.jooq.Table.class))).thenReturn(insertSetStep);

    // AI agent response
    AiAgentClient.ClassifyRowResult aiRow =
        new AiAgentClient.ClassifyRowResult(
            Map.of("source_id", 5L, "category", "animal", "score", 0.95));
    AiAgentClient.ClassifyResponse aiResponse =
        new AiAgentClient.ClassifyResponse(List.of(aiRow), 1, "claude");
    when(aiAgentClient.classify(any(), eq(1L))).thenReturn(aiResponse);

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(1);
    assertThat(result.executionLog()).contains("1 AI-processed");
    verify(aiAgentClient).classify(any(), eq(1L));
    verify(dataTableRowService).insertBatch(eq("output_table"), anyList(), anyList(), anyMap());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withAllBatchesFailed_andOnErrorContinue_failsStepInsteadOfReportingSuccess() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 99L);
    sourceRow.put("text", "oops");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    // cache miss
    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong())).thenThrow(new RuntimeException("AI agent down"));

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    // 배치가 하나뿐이고 그것이 실패했다 = 전량 실패. onError=CONTINUE 라도 성공이 아니다.
    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0행")
        .hasMessageContaining("배치 실패 1개")
        // 사유가 cause 로 실려야 한다 — 없으면 pipeline_step_execution.error_message 를 보고도
        // 왜 실패했는지 알 수 없고 로그를 따로 뒤져야 한다.
        .hasRootCauseMessage("AI agent down");

    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
  }

  /**
   * 전량 실패 시 <b>기존 출력 테이블이 살아남아야</b> 한다(#685).
   *
   * <p>2026-09-18 운영에서 실제로 잃은 것이 이것이다. 배치가 전부 실패해 결과가 0행인데도 REPLACE
   * 스왑이 그대로 실행돼 <b>빈 임시 테이블이 원본을 덮었고</b>, 직전 실행이 적재한 10건이 사라졌다.
   * 그러고도 스텝은 COMPLETED 였다.
   *
   * <p>Python 경로({@code PipelineAsyncRunner:538})는 {@code rowsLoaded() > 0} 일 때만 스왑한다 —
   * "빈 결과는 기존 데이터를 파괴하지 않는다"가 플랫폼 관례이고 AI_CLASSIFY 만 이를 어기고 있었다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withAllBatchesFailed_andReplaceStrategy_preservesExistingTable() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 99L);
    sourceRow.put("text", "oops");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong())).thenThrow(new RuntimeException("timeout"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L));

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class);

    // 임시 테이블은 버리고 원본은 건드리지 않는다
    verify(dataTableService).createTempTable("output_table");
    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
  }

  /**
   * 예외 없이 <b>조용히</b> 0행이 된 경우도 실패다(#685).
   *
   * <p>{@code processBatch} 는 응답에 {@code source_id} 가 맞는 항목이 없으면 그 행을 warn 한 줄
   * 남기고 버린다. LLM 이 형식은 멀쩡하되 {@code source_id} 를 어긋나게 돌려주면 <b>예외 0건 ·
   * 결과 0행</b>이 된다 — 배치 오류 수로 판정했다면 이 경우가 다시 "성공"이 됐을 것이다.
   *
   * <p>입력이 비었으면 임시 테이블을 만들기도 전에 조기 반환하므로, 여기까지 와서 결과가 비었다는
   * 것은 언제나 "행이 있었는데 하나도 분류되지 못했다"는 뜻이다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withSilentlyDroppedRows_failsStepAndPreservesExistingTable() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 99L);
    sourceRow.put("text", "hello");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    stubCacheMiss();
    // 예외 없이 결과만 비어 있다 — 그 행에 대한 응답이 없었던 경우
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(new AiAgentClient.ClassifyResponse(List.of(), 0, "test-model"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L));

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0행");

    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
  }

  /**
   * 일부 배치만 실패하면 {@code CONTINUE} 는 <b>계속 간다</b> — 성공한 배치의 행은 남는다.
   *
   * <p>#685 의 수정이 여기까지 번지면 안 된다. 전량 실패만 막는 것이지, 부분 실패를 감내하겠다는
   * 사용자의 선택({@code onError=CONTINUE})을 뒤집는 것이 아니다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withPartialBatchFailure_andOnErrorContinue_keepsSucceededRows() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);

    Map<String, Object> row1 = new HashMap<>();
    row1.put("id", 1L);
    row1.put("text", "first");
    Map<String, Object> row2 = new HashMap<>();
    row2.put("id", 2L);
    row2.put("text", "second");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(row1, row2));

    stubCacheMiss();

    // batchSize=1 → 배치 2개. 첫 배치는 실패, 둘째 배치는 성공.
    Map<String, Object> okValues = new HashMap<>();
    okValues.put("source_id", 2);
    okValues.put("category", "A");
    when(aiAgentClient.classify(any(), anyLong()))
        .thenThrow(new RuntimeException("first batch down"))
        .thenReturn(
            new AiAgentClient.ClassifyResponse(
                List.of(new AiAgentClient.ClassifyRowResult(okValues)), 1, "test-model"));

    PipelineStepResponse step = buildStep("APPEND", List.of(1L), 1);

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows())
        .as("성공한 배치의 행은 살아남아야 한다 — CONTINUE 의 존재 이유다")
        .isEqualTo(1);
    assertThat(result.executionLog()).contains("1 batch errors");
    verify(dataTableRowService).insertBatch(anyString(), anyList(), anyList(), anyMap());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withAiAgentError_andOnErrorFailStep_throwsAndDropsTempTable() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 99L);
    sourceRow.put("text", "oops");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    // V103 이후 캐시 조회에 tenant_id 술어가 하나 더 붙는다 — 체인이 한 단계 길어졌으므로
    // 자기 자신을 돌려주게 해 마지막 fetchOne() 스텁이 계속 유효하도록 한다.
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(null);

    when(aiAgentClient.classify(any(), anyLong())).thenThrow(new RuntimeException("AI agent down"));

    // onError=FAIL_STEP 설정으로 Step을 재구성
    AiClassifyConfig failConfig =
        new AiClassifyConfig(
            "Classify rows",
            List.of(new OutputColumn("category", "TEXT")),
            List.of("id", "text"),
            20,
            "FAIL_STEP");
    Map<String, Object> aiConfig = objectMapper.convertValue(failConfig, Map.class);
    PipelineStepResponse step =
        new PipelineStepResponse(
            10L,
            "AI Step",
            "desc",
            "AI_CLASSIFY",
            null,
            200L,
            "output_table",
            List.of(1L),
            List.of(),
            1,
            "REPLACE",
            null,
            aiConfig,
            null,
            null);

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("AI_CLASSIFY batch");

    // REPLACE 였으므로 예외 시 임시 테이블을 드롭
    verify(dataTableService).createTempTable("output_table");
    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
  }

  // -----------------------------------------------------------------------
  // Private coerceRowValues() via reflection — type coercion branches
  // -----------------------------------------------------------------------

  @Test
  void coerceRowValues_coercesAllKnownTypes() throws Exception {
    Method m = AiClassifyExecutor.class.getDeclaredMethod("coerceRowValues", Map.class, Map.class);
    m.setAccessible(true);

    Map<String, Object> row = new HashMap<>();
    row.put("ts_str", "1700000000000");
    row.put("ts_num", 1700000000000L);
    row.put("bool_str", "true");
    row.put("bigint_str", "42");
    row.put("bigint_num", 42);
    row.put("int_str", "10");
    row.put("int_num", 10.5);
    row.put("num_str", "3.14");
    row.put("null_value", null);

    Map<String, String> types = new HashMap<>();
    types.put("ts_str", "TIMESTAMP");
    types.put("ts_num", "TIMESTAMP WITH TIME ZONE");
    types.put("bool_str", "BOOLEAN");
    types.put("bigint_str", "BIGINT");
    types.put("bigint_num", "BIGINT");
    types.put("int_str", "INTEGER");
    types.put("int_num", "INT");
    types.put("num_str", "NUMERIC");
    types.put("null_value", "TEXT");

    m.invoke(null, row, types);

    assertThat(row.get("ts_str")).isInstanceOf(Timestamp.class);
    assertThat(row.get("ts_num")).isInstanceOf(Timestamp.class);
    assertThat(row.get("bool_str")).isEqualTo(true);
    assertThat(row.get("bigint_str")).isEqualTo(42L);
    assertThat(row.get("bigint_num")).isEqualTo(42L);
    assertThat(row.get("int_str")).isEqualTo(10);
    assertThat(row.get("int_num")).isEqualTo(10);
    assertThat(row.get("num_str")).isEqualTo(new BigDecimal("3.14"));
    assertThat(row.get("null_value")).isNull();
  }

  @Test
  void coerceRowValues_withInvalidNumber_swallowsNumberFormatException() throws Exception {
    Method m = AiClassifyExecutor.class.getDeclaredMethod("coerceRowValues", Map.class, Map.class);
    m.setAccessible(true);

    Map<String, Object> row = new HashMap<>();
    row.put("bigint_bad", "not_a_number");
    Map<String, String> types = Map.of("bigint_bad", "BIGINT");

    // 예외는 내부에서 catch 되어야 함 — 값은 변환 실패 후 원본 유지
    m.invoke(null, row, types);

    assertThat(row.get("bigint_bad")).isEqualTo("not_a_number");
  }
}
