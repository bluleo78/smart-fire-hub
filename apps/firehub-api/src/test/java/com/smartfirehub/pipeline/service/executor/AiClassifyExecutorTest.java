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
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.InsertOnDuplicateSetStep;
import org.jooq.InsertSetMoreStep;
import org.jooq.InsertSetStep;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.SelectSelectStep;
import org.jooq.SelectWhereStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AiClassifyExecutor 단위 테스트. Spring 컨텍스트 없이 Mockito 로 실행한다. jOOQ DSLContext 는 fluent chain 이
 * 많아 deep stub 으로 처리한다.
 */
class AiClassifyExecutorTest {

  private AiAgentClient aiAgentClient;
  private DataTableRowService dataTableRowService;
  private DataTableService dataTableService;
  private DatasetRepository datasetRepository;
  private ObjectMapper objectMapper;
  private DSLContext dsl;

  private AiClassifyExecutor executor;

  @BeforeEach
  void setUp() {
    aiAgentClient = mock(AiAgentClient.class);
    dataTableRowService = mock(DataTableRowService.class);
    dataTableService = mock(DataTableService.class);
    datasetRepository = mock(DatasetRepository.class);
    objectMapper = new ObjectMapper();
    dsl = mock(DSLContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

    executor =
        new AiClassifyExecutor(
            aiAgentClient,
            dataTableRowService,
            dataTableService,
            datasetRepository,
            objectMapper,
            dsl);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private AiClassifyConfig buildConfig() {
    return new AiClassifyConfig(
        "Classify rows",
        List.of(new OutputColumn("category", "TEXT"), new OutputColumn("score", "NUMERIC")),
        List.of("id", "text"),
        20,
        "CONTINUE");
  }

  private PipelineStepResponse buildStep(String loadStrategy, List<Long> inputDatasetIds) {
    Map<String, Object> aiConfig = objectMapper.convertValue(buildConfig(), Map.class);
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
  void execute_withAiAgentError_andOnErrorContinue_skipsBatch() {
    when(datasetRepository.findTableNameById(200L)).thenReturn(Optional.of("output_table"));
    when(datasetRepository.findTableNameById(1L)).thenReturn(Optional.of("source_table"));
    when(dataTableRowService.countRows("source_table")).thenReturn(1L);

    Map<String, Object> sourceRow = new HashMap<>();
    sourceRow.put("id", 99L);
    sourceRow.put("text", "oops");
    when(dataTableRowService.queryData(anyString(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(sourceRow));

    // cache miss
    SelectSelectStep selectStep = mock(SelectSelectStep.class);
    SelectJoinStep joinStep = mock(SelectJoinStep.class);
    SelectConditionStep condStep1 = mock(SelectConditionStep.class);
    SelectConditionStep condStep2 = mock(SelectConditionStep.class);
    when(dsl.select(any(org.jooq.Field.class))).thenReturn(selectStep);
    when(selectStep.from(any(org.jooq.Table.class))).thenReturn(joinStep);
    when(joinStep.where(any(org.jooq.Condition.class))).thenReturn(condStep1);
    when(condStep1.and(any(org.jooq.Condition.class))).thenReturn(condStep2);
    when(condStep2.fetchOne()).thenReturn(null);

    when(aiAgentClient.classify(any(), anyLong()))
        .thenThrow(new RuntimeException("AI agent down"));

    PipelineStepResponse step = buildStep("APPEND", List.of(1L));

    AiClassifyExecutor.ExecutionResult result = executor.execute(step, 100L, 1L);

    // onError=CONTINUE → 배치는 스킵, 출력 0행, 예외 없음
    assertThat(result.outputRows()).isEqualTo(0);
    assertThat(result.executionLog()).contains("1 batch errors");
    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
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
    when(condStep2.fetchOne()).thenReturn(null);

    when(aiAgentClient.classify(any(), anyLong()))
        .thenThrow(new RuntimeException("AI agent down"));

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
    Method m =
        AiClassifyExecutor.class.getDeclaredMethod("coerceRowValues", Map.class, Map.class);
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
    Method m =
        AiClassifyExecutor.class.getDeclaredMethod("coerceRowValues", Map.class, Map.class);
    m.setAccessible(true);

    Map<String, Object> row = new HashMap<>();
    row.put("bigint_bad", "not_a_number");
    Map<String, String> types = Map.of("bigint_bad", "BIGINT");

    // 예외는 내부에서 catch 되어야 함 — 값은 변환 실패 후 원본 유지
    m.invoke(null, row, types);

    assertThat(row.get("bigint_bad")).isEqualTo("not_a_number");
  }
}
