package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PipelineExecutionServiceTest {

  @Mock PipelineStepRepository stepRepository;
  @Mock PipelineExecutionRepository executionRepository;
  @Mock PipelineRepository pipelineRepository;
  @Mock DataTableService dataTableService;
  @Mock DataTableRowService dataTableRowService;
  @Mock DatasetRepository datasetRepository;
  @Mock DatasetColumnRepository columnRepository;
  @Mock DSLContext pipelineDsl;
  @Mock SqlScriptExecutor sqlExecutor;
  @Mock PythonScriptExecutor pythonExecutor;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @Mock ApiCallExecutor apiCallExecutor;
  @Mock AiClassifyExecutor aiClassifyExecutor;
  @Mock ApiConnectionService apiConnectionService;
  @Mock ObjectMapper objectMapper;
  @Mock PermissionChecker permissionChecker;
  @Mock TempDatasetService tempDatasetService;

  @InjectMocks PipelineExecutionService service;

  // ------------------------------------------------------------------ //
  // validateDAG tests
  // ------------------------------------------------------------------ //

  @Test
  void validateDAG_emptySteps_noException() {
    assertThatCode(() -> service.validateDAG(List.of())).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_nullSteps_noException() {
    assertThatCode(() -> service.validateDAG(null)).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_singleStepNoDependencies_noException() {
    List<PipelineStepRequest> steps = List.of(step("A", List.of()));
    assertThatCode(() -> service.validateDAG(steps)).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_linearDependency_noException() {
    // A -> B
    List<PipelineStepRequest> steps = List.of(step("A", List.of()), step("B", List.of("A")));
    assertThatCode(() -> service.validateDAG(steps)).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_threeIndependentSteps_noException() {
    List<PipelineStepRequest> steps =
        List.of(step("A", List.of()), step("B", List.of()), step("C", List.of()));
    assertThatCode(() -> service.validateDAG(steps)).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_diamondDependency_noException() {
    // A -> B, A -> C, B -> D, C -> D
    List<PipelineStepRequest> steps =
        List.of(
            step("A", List.of()),
            step("B", List.of("A")),
            step("C", List.of("A")),
            step("D", List.of("B", "C")));
    assertThatCode(() -> service.validateDAG(steps)).doesNotThrowAnyException();
  }

  @Test
  void validateDAG_cyclicDependency_throwsCyclicDependencyException() {
    // A -> B -> A
    List<PipelineStepRequest> steps = List.of(step("A", List.of("B")), step("B", List.of("A")));
    assertThatThrownBy(() -> service.validateDAG(steps))
        .isInstanceOf(CyclicDependencyException.class)
        .hasMessageContaining("Cyclic dependency");
  }

  @Test
  void validateDAG_selfReferencingStep_throwsCyclicDependencyException() {
    List<PipelineStepRequest> steps = List.of(step("A", List.of("A")));
    assertThatThrownBy(() -> service.validateDAG(steps))
        .isInstanceOf(CyclicDependencyException.class);
  }

  // ------------------------------------------------------------------ //
  // executePipeline tests
  // ------------------------------------------------------------------ //

  @Test
  void executePipeline_singleSqlStep_returnsExecutionIdAndCreatesStepExecution() throws Exception {
    // given
    Long pipelineId = 1L;
    Long userId = 10L;
    Long executionId = 42L;
    Long stepId = 100L;
    Long stepExecId = 200L;

    PipelineStepResponse sqlStep =
        stepResponse(stepId, "step1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("1 row affected");
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    Long result = service.executePipeline(pipelineId, userId);

    // then
    assertThat(result).isEqualTo(executionId);
    verify(executionRepository).createStepExecution(executionId, stepId);
    verify(executionRepository).createExecution(pipelineId, userId, "MANUAL", null);
  }

  @Test
  void executePipeline_dependentStepAfterFailedStep_dependentStepSkipped() throws Exception {
    // given: step1 (no deps) will FAIL, step2 depends on step1 -> SKIPPED
    Long pipelineId = 2L;
    Long userId = 10L;
    Long executionId = 50L;
    Long step1Id = 101L;
    Long step2Id = 102L;
    Long stepExec1Id = 201L;
    Long stepExec2Id = 202L;

    PipelineStepResponse step1 = stepResponse(step1Id, "step1", "SQL", "BAD SQL", null, List.of());
    PipelineStepResponse step2 =
        stepResponse(step2Id, "step2", "SQL", "SELECT 2", null, List.of("step1"));

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(stepExec1Id);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(stepExec2Id);
    when(sqlExecutor.execute("BAD SQL")).thenThrow(new RuntimeException("SQL error"));
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: step2 should be marked SKIPPED
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExec2Id),
            eq("SKIPPED"),
            isNull(),
            isNull(),
            contains("Dependency"),
            isNull(),
            any());
  }

  @Test
  void executePipeline_noSteps_stillCreatesExecutionRecord() {
    // given
    Long pipelineId = 3L;
    Long userId = 10L;
    Long executionId = 60L;

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of());
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    Long result = service.executePipeline(pipelineId, userId);

    // then
    assertThat(result).isEqualTo(executionId);
    verify(executionRepository).createExecution(pipelineId, userId, "MANUAL", null);
    verifyNoMoreInteractions(stepRepository);
  }

  @Test
  void executePipeline_completionEventPublishedAfterExecution() throws Exception {
    // given
    Long pipelineId = 4L;
    Long userId = 10L;
    Long executionId = 70L;
    Long stepId = 110L;
    Long stepExecId = 210L;

    PipelineStepResponse sqlStep =
        stepResponse(
            stepId, "publish-step", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: PipelineCompletedEvent published for this pipeline
    ArgumentCaptor<PipelineCompletedEvent> eventCaptor =
        ArgumentCaptor.forClass(PipelineCompletedEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    PipelineCompletedEvent published = eventCaptor.getValue();
    assertThat(published.pipelineId()).isEqualTo(pipelineId);
    assertThat(published.executionId()).isEqualTo(executionId);
  }

  // ------------------------------------------------------------------ //
  // SQL 자동 적재 테스트
  // ------------------------------------------------------------------ //

  @Test
  void executeStep_selectWithOutputDataset_wrapsAsInsertIntoSelect() throws Exception {
    // given
    Long pipelineId = 10L;
    Long userId = 1L;
    Long executionId = 100L;
    Long stepId = 200L;
    Long stepExecId = 300L;
    Long outputDatasetId = 50L;

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "sql-step", "SQL", selectSql, outputDatasetId, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_table"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(
            List.of(col("pk_id", true), col("id", false), col("name", false), col("extra", false)));

    // pipelineDsl.fetch(probeSql) → Result with fields [id, name]
    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"), DSL.field("name"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(sqlExecutor.execute(anyString())).thenReturn("2 rows affected");
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: sqlExecutor called with wrapped INSERT INTO ... SELECT
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    String executedSql = sqlCaptor.getValue();
    assertThat(executedSql).startsWith("INSERT INTO data.\"output_table\"");
    assertThat(executedSql).contains("\"id\"");
    assertThat(executedSql).contains("\"name\"");
    assertThat(executedSql).doesNotContain("\"pk_id\"");
    assertThat(executedSql).doesNotContain("\"extra\"");
    assertThat(executedSql).endsWith(selectSql);
  }

  @Test
  void executeStep_withCteAndOutputDataset_wrapsAsInsertIntoSelect() throws Exception {
    // given
    Long pipelineId = 11L;
    Long userId = 1L;
    Long executionId = 101L;
    Long stepId = 201L;
    Long stepExecId = 301L;
    Long outputDatasetId = 51L;

    String cteSql = "WITH t AS (SELECT 1 AS val) SELECT val FROM t";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-step", "SQL", cteSql, outputDatasetId, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_cte"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));

    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("val"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(sqlExecutor.execute(anyString())).thenReturn("1 row affected");
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    String executedSql = sqlCaptor.getValue();
    assertThat(executedSql).startsWith("INSERT INTO data.\"output_cte\"");
    assertThat(executedSql).contains("\"val\"");
    assertThat(executedSql).endsWith(cteSql);
  }

  @Test
  void executeStep_selectWithoutOutputDataset_createsTempDataset() throws Exception {
    // given: SELECT but no outputDatasetId → auto-create temp dataset
    Long pipelineId = 12L;
    Long userId = 1L;
    Long executionId = 102L;
    Long stepId = 202L;
    Long stepExecId = 302L;
    Long tempDatasetId = 999L;

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "plain-select", "SQL", selectSql, null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("Test Pipeline"));

    // extractSelectColumnsWithTypes probe
    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"), DSL.field("name"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    // TempDatasetService: no existing temp dataset → create new
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId))
        .thenReturn(Optional.of("ptmp_12_plain_select_abcd"));

    // columns for INSERT INTO wrapping
    when(columnRepository.findByDatasetId(tempDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("2 rows affected");

    // when
    service.executePipeline(pipelineId, userId);

    // then: temp dataset was created and INSERT INTO wrapping used
    verify(tempDatasetService)
        .createTempDataset(any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).startsWith("INSERT INTO data.\"ptmp_12_plain_select_abcd\"");
  }

  @Test
  void executeStep_selectWithoutOutputDataset_reusesTempDatasetWhenSchemaUnchanged()
      throws Exception {
    Long pipelineId = 15L;
    Long userId = 1L;
    Long executionId = 105L;
    Long stepId = 205L;
    Long stepExecId = 305L;
    Long existingTempDatasetId = 888L;

    String selectSql = "SELECT id FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "reuse-step", "SQL", selectSql, null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("Test Pipeline"));

    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    // Existing temp dataset found, schema unchanged
    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(existingTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(existingTempDatasetId), any())).thenReturn(false);
    when(datasetRepository.findTableNameById(existingTempDatasetId))
        .thenReturn(Optional.of("ptmp_15_reuse_step_1234"));
    when(columnRepository.findByDatasetId(existingTempDatasetId))
        .thenReturn(List.of(col("id", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("1 row affected");

    // when
    service.executePipeline(pipelineId, userId);

    // then: no new temp dataset created
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
  }

  @Test
  void executeStep_selectWithoutOutputDataset_recreatesTempDatasetWhenSchemaChanged()
      throws Exception {
    Long pipelineId = 16L;
    Long userId = 1L;
    Long executionId = 106L;
    Long stepId = 206L;
    Long stepExecId = 306L;
    Long oldTempDatasetId = 777L;
    Long newTempDatasetId = 778L;

    String selectSql = "SELECT id, name, extra FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "schema-change-step", "SQL", selectSql, null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("Test Pipeline"));

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES)
            .newResult(DSL.field("id"), DSL.field("name"), DSL.field("extra"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    // Existing dataset found, schema changed
    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(oldTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(oldTempDatasetId), any())).thenReturn(true);
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(newTempDatasetId);
    when(datasetRepository.findTableNameById(newTempDatasetId))
        .thenReturn(Optional.of("ptmp_16_schema_change_step_5678"));
    when(columnRepository.findByDatasetId(newTempDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false), col("extra", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("3 rows affected");

    // when
    service.executePipeline(pipelineId, userId);

    // then: old dataset deleted, new one created
    verify(tempDatasetService).deleteTempDataset(oldTempDatasetId);
    verify(tempDatasetService)
        .createTempDataset(any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId));
  }

  @Test
  void executeStep_insertSqlWithOutputDataset_executesAsIs() throws Exception {
    // given: INSERT SQL with outputDatasetId → NOT wrapped (not a SELECT)
    Long pipelineId = 13L;
    Long userId = 1L;
    Long executionId = 103L;
    Long stepId = 203L;
    Long stepExecId = 303L;
    Long outputDatasetId = 53L;

    String insertSql = "INSERT INTO data.\"target\" (val) VALUES (1)";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "insert-step", "SQL", insertSql, outputDatasetId, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("target"));
    when(sqlExecutor.execute(insertSql)).thenReturn("1 row affected");
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: called with original INSERT (no wrapping)
    verify(sqlExecutor).execute(insertSql);
    verify(pipelineDsl, never()).fetch(anyString());
  }

  @Test
  void executeStep_selectNoMatchingColumns_throwsScriptExecutionException() throws Exception {
    // given: SELECT columns don't match output dataset columns
    Long pipelineId = 14L;
    Long userId = 1L;
    Long executionId = 104L;
    Long stepId = 204L;
    Long stepExecId = 304L;
    Long outputDatasetId = 54L;

    String selectSql = "SELECT foo, bar FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "no-match", "SQL", selectSql, outputDatasetId, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_nomatch"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", true), col("name", false)));

    // SELECT returns [foo, bar] but output has only [name] (non-PK)
    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("foo"), DSL.field("bar"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: step marked FAILED with ScriptExecutionException message
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("SELECT 결과 컬럼이 출력 데이터셋의 컬럼과 일치하지 않습니다"),
            isNull(),
            any());
  }

  @Test
  void executeStep_apiCallWithoutOutputDataset_createsTempDataset() throws Exception {
    // given: API_CALL step with no outputDatasetId → auto-create temp dataset from fieldMappings
    Long pipelineId = 20L;
    Long userId = 1L;
    Long executionId = 120L;
    Long stepId = 220L;
    Long stepExecId = 320L;
    Long tempDatasetId = 420L;

    ApiCallConfig.FieldMapping fm =
        new ApiCallConfig.FieldMapping("src_name", "name", "TEXT", null, null, null);
    ApiCallConfig apiCallConfig =
        new ApiCallConfig(
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
            null);

    PipelineStepResponse apiStep =
        new PipelineStepResponse(
            stepId,
            "api-step",
            null,
            "API_CALL",
            null,
            null,
            null,
            List.of(),
            List.of(),
            0,
            "APPEND",
            Map.of(),
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(apiStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(apiCallConfig);
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("api-step"), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId)).thenReturn(Optional.of("ptmp_api"));
    when(columnRepository.findByDatasetId(tempDatasetId)).thenReturn(List.of(col("name", false)));
    when(apiCallExecutor.execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(5, "log"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: temp dataset created and apiCallExecutor called with resolved table name
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("api-step"), eq(userId));
    verify(apiCallExecutor).execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any());
  }

  @Test
  void executeStep_aiClassifyWithoutOutputDataset_createsTempDataset() throws Exception {
    // given: AI_CLASSIFY step with no outputDatasetId → auto-create temp dataset with fixed schema
    Long pipelineId = 21L;
    Long userId = 1L;
    Long executionId = 121L;
    Long stepId = 221L;
    Long stepExecId = 321L;
    Long tempDatasetId = 421L;

    AiClassifyConfig aiConfig =
        new AiClassifyConfig("text", "id", List.of("A", "B"), null, "ai_", null, null, null, null);

    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            stepId,
            "ai-step",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            Map.of(),
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(aiStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class))).thenReturn(aiConfig);
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("ai-step"), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId)).thenReturn(Optional.of("ptmp_ai"));
    when(aiClassifyExecutor.execute(
            argThat(s -> tempDatasetId.equals(s.outputDatasetId())), eq(stepExecId), eq(userId)))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(10L, "ai log"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: temp dataset created, aiClassifyExecutor called with resolved outputDatasetId
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("ai-step"), eq(userId));
    verify(aiClassifyExecutor)
        .execute(
            argThat(s -> tempDatasetId.equals(s.outputDatasetId())), eq(stepExecId), eq(userId));
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  private PipelineStepRequest step(String name, List<String> dependsOn) {
    return new PipelineStepRequest(name, null, "SQL", null, null, null, dependsOn);
  }

  private PipelineStepResponse stepResponse(
      Long id,
      String name,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      List<String> dependsOnStepNames) {
    return new PipelineStepResponse(
        id,
        name,
        null,
        scriptType,
        scriptContent,
        outputDatasetId,
        null,
        List.of(),
        dependsOnStepNames,
        0,
        "REPLACE",
        null,
        null,
        null);
  }

  private PipelineStepResponse stepResponseWithOutput(
      Long id,
      String name,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      List<String> dependsOnStepNames) {
    return new PipelineStepResponse(
        id,
        name,
        null,
        scriptType,
        scriptContent,
        outputDatasetId,
        null,
        List.of(),
        dependsOnStepNames,
        0,
        "APPEND",
        null,
        null,
        null);
  }

  private DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
