package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.mockito.ArgumentCaptor;
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
  private PipelineExecutionRepository executionRepository;

  private AiClassifyExecutor executor;

  @BeforeEach
  void setUp() {
    aiAgentClient = mock(AiAgentClient.class);
    dataTableRowService = mock(DataTableRowService.class);
    dataTableService = mock(DataTableService.class);
    datasetRepository = mock(DatasetRepository.class);
    objectMapper = new ObjectMapper();
    dsl = mock(DSLContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    executionRepository = mock(PipelineExecutionRepository.class);

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
            transactionTemplate,
            executionRepository);
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
    stubCacheLookup(null);
  }

  /** 캐시 조회 목 체인을 세우고 {@code fetchOne()} 이 돌려줄 값을 지정한다(null 이면 미스). */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void stubCacheLookup(Record1<JSONB> result) {
    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(result);
  }

  private PipelineStepResponse buildStep(String loadStrategy, List<Long> inputDatasetIds) {
    return buildStep(loadStrategy, inputDatasetIds, 20);
  }

  private PipelineStepResponse buildStep(
      String loadStrategy, List<Long> inputDatasetIds, int batchSize) {
    return buildStep(loadStrategy, inputDatasetIds, batchSize, "CONTINUE");
  }

  private PipelineStepResponse buildStep(
      String loadStrategy, List<Long> inputDatasetIds, int batchSize, String onError) {
    AiClassifyConfig config =
        new AiClassifyConfig(
            "Classify rows",
            List.of(new OutputColumn("category", "TEXT"), new OutputColumn("score", "NUMERIC")),
            List.of("id", "text"),
            batchSize,
            onError);
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
    // 정상 응답이면 누락이 0으로 남는다(#694) — source_id 가 전부 맞아떨어진 경우의 회귀 방지.
    assertThat(result.executionLog()).contains("0 rows dropped");
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
  // 배치마다 적재 (#690)
  // -----------------------------------------------------------------------

  /** LLM 이 돌려준 결과 한 건 — {@code source_id} 는 LLM 이 받아 적는 값이라 일부러 틀리게도 쓴다. */
  private AiAgentClient.ClassifyRowResult classifyRow(int sourceId, String category) {
    Map<String, Object> values = new HashMap<>();
    values.put("source_id", sourceId);
    values.put("category", category);
    return new AiAgentClient.ClassifyRowResult(values);
  }

  /** 결과 여러 건을 담은 배치 응답. */
  private AiAgentClient.ClassifyResponse classifyResponse(
      AiAgentClient.ClassifyRowResult... rows) {
    return new AiAgentClient.ClassifyResponse(List.of(rows), rows.length, "test-model");
  }

  /** 분류 결과를 담은 배치 응답 하나를 만든다 — source_id 가 입력 행의 id 와 맞아야 결과로 인정된다. */
  private AiAgentClient.ClassifyResponse classifyResponse(int sourceId, String category) {
    return classifyResponse(classifyRow(sourceId, category));
  }

  /** id/text 두 컬럼짜리 입력 행. */
  private Map<String, Object> sourceRow(long id, String text) {
    Map<String, Object> row = new HashMap<>();
    row.put("id", id);
    row.put("text", text);
    return row;
  }

  /**
   * 배치 결과는 <b>배치마다</b> 적재된다 — 끝까지 모아 두지 않는다(#690).
   *
   * <p>예전에는 모든 배치의 결과를 힙에 모아 두었다가 루프가 끝난 뒤 한 번만 {@code insertBatch} 를
   * 불렀다. 174배치 × 45초 ≈ 2시간짜리 실행이 중간 저장 없이 돌았고, 재시작·OOM·배포 무엇이든
   * 그때까지의 분류가 전부 사라졌다(운영 실행 9 실측: 캐시 342행 vs 스테이징 테이블 0행).
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withMultipleBatches_insertsEachBatchImmediately() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(3L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a"), sourceRow(2L, "b"), sourceRow(3L, "c")));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(classifyResponse(1, "A"))
        .thenReturn(classifyResponse(2, "B"))
        .thenReturn(classifyResponse(3, "C"));

    // batchSize=1 → 배치 3개
    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 1);

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(3);
    // 핵심 단언: 루프 끝의 한 번이 아니라 배치마다 한 번씩
    verify(dataTableRowService, times(3))
        .insertBatch(eq("output_table_tmp"), anyList(), anyList(), anyMap());
    verify(dataTableService).swapTable("output_table");
  }

  /**
   * 뒤 배치가 실패해도 <b>앞 배치가 쓴 행은 이미 커밋돼 있다</b>(#690).
   *
   * <p>모아 두던 시절에는 마지막 한 번의 적재 전에 예외가 나면 앞의 모든 배치 결과가 통째로
   * 증발했다. 이제는 실패 시점까지의 적재가 {@code t_tmp} 에 남는다 — REPLACE 이므로 그 임시
   * 테이블은 드롭되고 원본은 그대로다(#685 의 보장은 유지된다).
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withLaterBatchFailure_andFailStep_writesEarlierBatchBeforeThrowing() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "ok"), sourceRow(2L, "boom")));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(classifyResponse(1, "A"))
        .thenThrow(new RuntimeException("AI agent down"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 1, "FAIL_STEP");

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("AI_CLASSIFY batch 2");

    // 첫 배치는 예외 전에 이미 적재됐다 — 모아 두던 구현에서는 0회였다.
    verify(dataTableRowService, times(1))
        .insertBatch(eq("output_table_tmp"), anyList(), anyList(), anyMap());
    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
  }

  /**
   * 배치마다 진척(적재 행 수 + 진행 메시지)이 {@code pipeline_step_execution} 에 기록된다(#691).
   *
   * <p>기록이 없으면 실행 중 화면의 "출력행"은 스텝이 끝날 때까지 {@code -} 이고, 몇 번째 배치를
   * 돌고 있는지는 서버 로그에만 남는다 — 사용자는 멈춘 것과 도는 것을 구분할 수 없다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_reportsPerBatchProgressToStepExecution() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a"), sourceRow(2L, "b")));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(classifyResponse(1, "A"))
        .thenReturn(classifyResponse(2, "B"));

    PipelineStepResponse step = buildStep("APPEND", List.of(1L), 1);

    executor.execute(step, 100L, 1L);

    // 배치 1 종료 시점 1행, 배치 2 종료 시점 2행 — 누적 카운트가 그대로 올라간다.
    verify(executionRepository).updateStepProgress(eq(100L), eq(1), contains("배치 1/2"));
    verify(executionRepository).updateStepProgress(eq(100L), eq(2), contains("배치 2/2"));
  }

  /**
   * 적재 실패는 <b>배치 실패가 아니다</b> — onError 와 무관하게 스텝을 떨군다.
   *
   * <p>적재를 AI 호출 try 안에 두면 insert 오류(타입 변환·DDL 불일치·DB 장애)가 "배치 실패"로
   * 격하된다. CONTINUE 면 그 배치를 조용히 건너뛰고, RETRY_BATCH 면 LLM 이 고칠 수 없는 오류에
   * 2+4+8초를 자며 분류를 세 번 더 태운다. 그래서 적재는 try 밖에 있다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withInsertFailure_failsStepEvenWhenOnErrorIsContinue() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a"), sourceRow(2L, "b")));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(classifyResponse(1, "A"))
        .thenReturn(classifyResponse(2, "B"));
    doThrow(new RuntimeException("insert failed: column mismatch"))
        .when(dataTableRowService)
        .insertBatch(anyString(), anyList(), anyList(), anyMap());

    // 기본값 onError=CONTINUE — 그래도 스텝이 떨어져야 한다.
    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 1);

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("insert failed");

    // 첫 배치에서 곧바로 떨어졌으므로 둘째 배치의 LLM 호출은 없다.
    verify(aiAgentClient, times(1)).classify(any(), anyLong());
    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
  }

  /**
   * 실패로 끝난 스텝에 "진행 중" 진척이 남지 않는다(#691).
   *
   * <p>러너의 실패 경로는 output_rows/log 에 null 을 넘겨 기존 값을 덮지 않는다. 실행기가 정리하지
   * 않으면 FAILED 스텝이 "출력행 N · 배치 4/174 진행 중" 을 계속 보여준다 — REPLACE 는 임시
   * 테이블을 버렸으니 실제로 남은 행은 0 인데도.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_whenStepFails_overwritesProgressWithFailureLine() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "boom")));

    stubCacheMiss();
    when(aiAgentClient.classify(any(), anyLong())).thenThrow(new RuntimeException("AI agent down"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 1);

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class);

    // "배치 실패 1" 도 '실패' 를 포함하므로 진행 줄과 구분되는 접두사로 단언한다.
    verify(executionRepository).updateStepProgress(eq(100L), eq(0), contains("AI_CLASSIFY 실패:"));
  }

  // -----------------------------------------------------------------------
  // rowContentHash() — 캐시 키 (#687)
  // -----------------------------------------------------------------------

  /**
   * 캐시 키는 <b>내용만</b> 본다 — surrogate {@code id} 가 달라도 같은 키여야 한다(#687).
   *
   * <p>운영에서 캐시가 한 번도 히트하지 않던 이유다. 입력이 임시 데이터셋이면 {@code id} 는 매 실행
   * {@code TRUNCATE} 뒤 다시 채워지는 {@code BIGSERIAL} 값이라 실행마다 달라진다. 그 값이 해시에
   * 섞여 있으면 같은 보도자료도 매번 새 키가 되고, LLM 을 처음부터 다시 태운다.
   */
  @Test
  void rowContentHash_ignoresSurrogateId() {
    Map<String, Object> run1 = new HashMap<>();
    run1.put("id", 101L);
    run1.put("text", "같은 내용");
    Map<String, Object> run2 = new HashMap<>();
    run2.put("id", 999L);
    run2.put("text", "같은 내용");

    assertThat(AiClassifyExecutor.rowContentHash(run1, "p1"))
        .as("id 는 내용이 아니다 — 실행마다 바뀌는 값이 캐시를 무효화하면 안 된다")
        .isEqualTo(AiClassifyExecutor.rowContentHash(run2, "p1"));
  }

  /** 내용이나 프롬프트가 다르면 키도 달라야 한다 — 위 테스트만 있으면 상수 반환도 통과한다. */
  @Test
  void rowContentHash_differsOnContentOrPrompt() {
    Map<String, Object> row = new HashMap<>();
    row.put("id", 1L);
    row.put("text", "원본");
    Map<String, Object> other = new HashMap<>();
    other.put("id", 1L);
    other.put("text", "수정됨");

    assertThat(AiClassifyExecutor.rowContentHash(row, "p1"))
        .isNotEqualTo(AiClassifyExecutor.rowContentHash(other, "p1"));
    assertThat(AiClassifyExecutor.rowContentHash(row, "p1"))
        .as("프롬프트가 바뀌면 이전 결과를 재사용하면 안 된다")
        .isNotEqualTo(AiClassifyExecutor.rowContentHash(row, "p2"));
  }

  /**
   * 맵 순회 순서가 키를 가르면 안 된다.
   *
   * <p>{@code HashMap} 의 순서는 보장이 없어서, 같은 내용이 다른 순서로 직렬화되면 캐시가 조용히
   * 갈린다. 삽입 순서를 뒤집어도 같은 키가 나오는지 본다.
   */
  @Test
  void rowContentHash_isStableRegardlessOfKeyOrder() {
    Map<String, Object> forward = new LinkedHashMap<>();
    forward.put("a", "1");
    forward.put("b", "2");
    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("b", "2");
    reversed.put("a", "1");

    assertThat(AiClassifyExecutor.rowContentHash(forward, "p"))
        .isEqualTo(AiClassifyExecutor.rowContentHash(reversed, "p"));
  }

  /**
   * 캐시 히트가 <b>자기 행의</b> {@code source_id} 를 갖는지 본다(#687).
   *
   * <p>키에서 {@code id} 를 뺀 대가로 내용이 같은 여러 행이 한 캐시 항목을 공유하게 됐다. 저장된
   * {@code source_id} 를 그대로 쓰면 그 행들이 전부 남의 조인 키를 물려받는다 — 캐시 키 수정이
   * 만들어낼 수 있었던 새 결함이고, 이 단언이 그것을 막는다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withCacheHit_overridesSourceIdWithCurrentRowId() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 77L);
    sourceRow.put("text", "hello");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    // 캐시에는 **다른 행**이 남긴 source_id 가 들어 있다(예전 형식으로 저장된 항목).
    Record1<JSONB> cachedRecord = mock(Record1.class);
    JSONB cachedJson = JSONB.valueOf("{\"category\":\"B\",\"source_id\":11}");
    when(cachedRecord.get(any(org.jooq.Field.class))).thenReturn(cachedJson);

    stubCacheLookup(cachedRecord);

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    executor.execute(step, 100L, 1L);

    ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(dataTableRowService).insertBatch(anyString(), anyList(), rowsCaptor.capture(), anyMap());

    assertThat(rowsCaptor.getValue()).hasSize(1);
    assertThat(rowsCaptor.getValue().get(0).get("source_id"))
        .as("캐시에 저장돼 있던 남의 source_id(11) 가 아니라 이 행의 id(77) 여야 한다")
        .isEqualTo(77L);
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

  // -----------------------------------------------------------------------
  // source_id 대조 (#694)
  //
  // 배치가 1행이면 "결과 하나는 그 행의 것"이라 식별자가 필요 없다. 10행을 한 번에 보내는
  // 순간 "3번째 결과는 누구 것인가"에 답해야 하고, 이 구현은 이름표(source_id)를 골랐다.
  // 그런데 그 이름표를 **LLM 이 받아 적는다** — 위험은 지면서 이름표 방식의 유일한 이점인
  // 검증 가능성은 쓰지 않고 있었다. 아래 셋이 그 검증이다.
  //
  // onError 를 FAIL_STEP 으로 두는 이유: RETRY_BATCH 는 2+4+8초를 실제로 자므로 테스트가 14초
  // 느려진다. 검사 자체는 정책과 무관하게 processBatch 안에서 던진다.
  // -----------------------------------------------------------------------

  /**
   * 보낸 번호 중 하나가 응답에 없으면 배치를 실패시킨다 — 예전에는 warn 한 줄 남기고 버렸다.
   *
   * <p>같은 시나리오로 <b>캐시 순서</b>도 함께 못박는다. {@code verifySourceIds} 는 캐시 쓰기
   * <b>뒤</b>에 불린다 — 대조에 실패할 배치라도 제대로 분류된 행(여기서는 1번)이 먼저 캐시에 남아야
   * 재시도가 싸다. 검사를 캐시 쓰기 앞으로 옮기면 {@code insertInto} 가 아예 일어나지 않아 이
   * 테스트가 깨진다.
   *
   * <p>결과를 버리면서 캐시는 남기는 것이 모순이 아닌 이유: 캐시는 "이 내용 + 이 프롬프트의 분류
   * 결과"이지 "어느 행의 것인가"가 아니다(#687 로 {@code source_id} 를 싣지 않는다). 틀린 것은 행에
   * 되짚는 부분이지 분류 자체가 아니다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withMissingSourceIdInResponse_failsBatchButKeepsCacheForMatchedRows() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a"), sourceRow(2L, "b")));

    stubCacheMiss();
    // 2행을 보냈는데 1번 행의 결과만 돌아왔다.
    when(aiAgentClient.classify(any(), anyLong())).thenReturn(classifyResponse(1, "A"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 2, "FAIL_STEP");

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .hasMessageContaining("source_id")
        .hasMessageContaining("보낸 2행")
        .hasMessageContaining("누락 [2]");

    verify(dataTableService).dropTempTable("output_table");
    verify(dataTableService, never()).swapTable(anyString());
    // 대조 실패로 배치를 버렸어도 캐시 쓰기는 이미 시도됐다.
    verify(dsl)
        .insertInto(
            any(org.jooq.Table.class),
            any(org.jooq.Field.class),
            any(org.jooq.Field.class),
            any(org.jooq.Field.class));
  }

  /** 보낸 적 없는 번호를 돌려주면 배치를 실패시킨다. */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withUnknownSourceIdInResponse_failsBatch() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a")));

    stubCacheMiss();
    // 1번 행을 보냈는데 999번의 결과가 돌아왔다 — 예전에는 조회가 null 이라 그 행만 조용히 빠졌다.
    when(aiAgentClient.classify(any(), anyLong())).thenReturn(classifyResponse(999, "A"));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 2, "FAIL_STEP");

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .hasMessageContaining("누락 [1]")
        .hasMessageContaining("모르는 번호 [999]");
  }

  /**
   * 같은 번호를 두 번 돌려주면 배치를 실패시킨다.
   *
   * <p>{@code toMap} 의 병합 함수 {@code (a,b)->a} 가 중복을 삼키므로, 이 경우 다른 한 행은 짝을
   * 잃고 조용히 사라졌다. 집합 비교가 그 손실을 드러낸다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withDuplicateSourceIdInResponse_failsBatch() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(2L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "a"), sourceRow(2L, "b")));

    stubCacheMiss();
    // 두 결과가 모두 1번이라고 주장한다 — 2번 행은 짝을 잃는다.
    when(aiAgentClient.classify(any(), anyLong()))
        .thenReturn(classifyResponse(classifyRow(1, "A"), classifyRow(1, "B")));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L), 2, "FAIL_STEP");

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .hasMessageContaining("source_id")
        .hasMessageContaining("누락 [2]");
  }

  /**
   * 버려진 <b>행</b> 수가 결과에 남는다(#694).
   *
   * <p>{@code batch errors} 는 배치 수라, 10행짜리 배치가 통째로 빠져도 "1"로 보인다. 몇 행이
   * 사라졌는지가 결과 문자열에 없으면 같은 행이 매 실행 결정적으로 실패하는 영구 루프가 조용히
   * 굳는다 — 2026-09-19 운영의 guid 사건이 정확히 그 모양이었다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withDroppedRows_reportsRowCountNotJustBatchCount() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(4L);
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                sourceRow(1L, "a"), sourceRow(2L, "b"), sourceRow(3L, "c"), sourceRow(4L, "d")));

    stubCacheMiss();
    // batchSize=2 → 배치 2개. 첫 배치가 통째로 실패하고 CONTINUE 가 건너뛴다.
    // **배치 크기를 1보다 크게 잡는 것이 이 테스트의 요점**이다 — 1이면 "배치 1개 실패"와
    // "1행 누락"이 같은 숫자라 두 지표가 구분되는지를 증명하지 못한다.
    when(aiAgentClient.classify(any(), anyLong()))
        .thenThrow(new RuntimeException("boom"))
        .thenReturn(classifyResponse(classifyRow(3, "C"), classifyRow(4, "D")));

    PipelineStepResponse step = buildStep("APPEND", List.of(1L), 2);

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    assertThat(result.outputRows()).isEqualTo(2);
    assertThat(result.executionLog())
        .as("배치 1개가 실패했지만 사라진 것은 2행이다 — 두 숫자가 달라야 한다")
        .contains("1 batch errors")
        .contains("2 rows dropped");
  }

  /**
   * 입력 행의 id 가 겹치면 <b>LLM 을 태우기 전에</b> 진짜 원인을 말하고 멈춘다(#694).
   *
   * <p>입력 데이터셋이 둘 이상이면 각 출력 테이블이 자기 {@code BIGSERIAL} 을 쓰므로 id 가 1 부터
   * 다시 시작해 반드시 겹친다({@code PipelineAsyncRunner} 가 의존 스텝마다 입력 데이터셋을 하나씩
   * 쌓는다). 그 상태에서는 "어느 결과가 어느 행의 것인가"를 판별할 방법이 없다 — 겹친 두 행이 같은
   * 결과를 물려받아 한쪽의 분류가 다른 쪽에 조용히 저장된다.
   *
   * <p>배치 루프 <b>밖</b>에서 던져야 한다. 안에서 던지면 {@code onError=CONTINUE} 가 삼켜 "배치가
   * 모두 실패했다"로 격하되고, {@code RETRY_BATCH} 는 절대 성공할 수 없는 재시도로 14초를 잔다.
   */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void execute_withDuplicateInputRowIds_failsBeforeCallingAi() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table_a"));
    when(datasetRepository.findTableNameById(2L)).thenReturn(Optional.of("source_table_b"));
    when(dataTableRowService.countRows(anyString())).thenReturn(1L);
    // 두 데이터셋이 각자 id=1 을 돌려준다 — 서로 다른 테이블의 BIGSERIAL 이라 겹친다.
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow(1L, "from A")))
        .thenReturn(List.of(sourceRow(1L, "from B")));

    PipelineStepResponse step = buildStep("REPLACE", List.of(1L, 2L));

    assertThatThrownBy(() -> executor.execute(step, 100L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("id 가 중복")
        .hasMessageContaining("겹친 id [1]")
        .hasMessageContaining("입력 데이터셋 2개");

    verify(aiAgentClient, never()).classify(any(), anyLong());
    // 임시 테이블을 만들기 전에 멈춘다 — 정리할 것을 남기지 않는다.
    verify(dataTableService, never()).createTempTable(anyString());
  }
}
