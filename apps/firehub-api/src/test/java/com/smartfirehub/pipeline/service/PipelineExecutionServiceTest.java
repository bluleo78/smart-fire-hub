package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.audit.service.AuditLogService;
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
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.user.repository.UserRepository;
import java.lang.reflect.Field;
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
  @Mock ExecutorClient executorClient;
  @Mock AuditLogService auditLogService;
  @Mock UserRepository userRepository;

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
    when(apiCallExecutor.execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(5, "log"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: temp dataset created and apiCallExecutor called with resolved table name
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("api-step"), eq(userId));
    verify(apiCallExecutor).execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any(), any());
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
        new AiClassifyConfig(
            "Classify into A or B",
            List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
            List.of("text"),
            null,
            null);

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
            null,
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

  @Test
  void executeStep_aiClassifyWithDependencyAndNoInputDatasetIds_autoResolvesInputFromDepStep()
      throws Exception {
    // given: SQL step (step1) produces a real outputDatasetId; AI_CLASSIFY step (step2) has
    // empty inputDatasetIds but depends on step1 → backend should auto-resolve step1's output
    Long pipelineId = 22L;
    Long userId = 1L;
    Long executionId = 122L;
    Long step1Id = 222L;
    Long step2Id = 223L;
    Long stepExec1Id = 322L;
    Long stepExec2Id = 323L;
    Long step1OutputDatasetId = 501L;
    Long aiTempDatasetId = 502L;

    AiClassifyConfig aiConfig =
        new AiClassifyConfig(
            "Classify",
            List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
            List.of("text"),
            null,
            null);

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(
            step1Id,
            "sql-source",
            "SQL",
            "INSERT INTO data.\"src\" VALUES (1)",
            step1OutputDatasetId,
            List.of());

    // AI_CLASSIFY step: no inputDatasetIds, but dependsOn sql-source
    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            step2Id,
            "ai-classify",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(), // empty inputDatasetIds
            List.of("sql-source"), // depends on sql-source
            1,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep, aiStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(stepExec1Id);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(stepExec2Id);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(step1OutputDatasetId))
        .thenReturn(Optional.of("src_table"));
    when(sqlExecutor.execute("INSERT INTO data.\"src\" VALUES (1)")).thenReturn("ok");

    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class))).thenReturn(aiConfig);
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(step2Id), eq("ai-classify"), eq(userId)))
        .thenReturn(aiTempDatasetId);
    when(datasetRepository.findTableNameById(aiTempDatasetId))
        .thenReturn(Optional.of("ptmp_ai_classify"));
    when(aiClassifyExecutor.execute(
            argThat(
                s ->
                    s.inputDatasetIds() != null
                        && s.inputDatasetIds().contains(step1OutputDatasetId)),
            eq(stepExec2Id),
            eq(userId)))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(5L, "ok log"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: aiClassifyExecutor was called with inputDatasetIds containing step1's output
    verify(aiClassifyExecutor)
        .execute(
            argThat(
                s ->
                    s.inputDatasetIds() != null
                        && s.inputDatasetIds().contains(step1OutputDatasetId)),
            eq(stepExec2Id),
            eq(userId));
  }

  @Test
  void executeStep_aiClassifyWithDependencyOnTempStep_autoResolvesInputViaSourcePipelineStepId()
      throws Exception {
    // given: SQL step (step1) has no outputDatasetId (temp); AI_CLASSIFY step (step2) depends
    // on step1 with empty inputDatasetIds → should resolve via findBySourcePipelineStepId
    Long pipelineId = 23L;
    Long userId = 1L;
    Long executionId = 123L;
    Long step1Id = 224L;
    Long step2Id = 225L;
    Long stepExec1Id = 324L;
    Long stepExec2Id = 325L;
    Long tempStep1DatasetId = 601L;
    Long aiTempDatasetId = 602L;

    AiClassifyConfig aiConfig =
        new AiClassifyConfig(
            "Classify",
            List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
            List.of("text"),
            null,
            null);

    // SQL step with no outputDatasetId → temp dataset
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            step1Id,
            "temp-sql",
            null,
            "SQL",
            "SELECT id, text FROM data.\"src\"",
            null, // no explicit outputDatasetId
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            null,
            null);

    // AI_CLASSIFY step: no inputDatasetIds, depends on temp-sql
    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            step2Id,
            "ai-on-temp",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(), // empty inputDatasetIds
            List.of("temp-sql"), // depends on temp step
            1,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep, aiStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(stepExec1Id);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(stepExec2Id);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));

    // step1 SQL execution → auto creates temp dataset
    Result<?> step1Result =
        org.jooq
            .impl
            .DSL
            .using(org.jooq.SQLDialect.POSTGRES)
            .newResult(org.jooq.impl.DSL.field("id"), org.jooq.impl.DSL.field("text"));
    doReturn(step1Result).when(pipelineDsl).fetch(anyString());
    when(tempDatasetService.findExistingTempDataset(step1Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step1Id), any(), eq(userId)))
        .thenReturn(tempStep1DatasetId);
    when(datasetRepository.findTableNameById(tempStep1DatasetId))
        .thenReturn(Optional.of("ptmp_temp_sql"));
    when(columnRepository.findByDatasetId(tempStep1DatasetId))
        .thenReturn(List.of(col("id", false), col("text", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("2 rows");

    // AI_CLASSIFY auto-resolve: step1.outputDatasetId=null → findBySourcePipelineStepId
    when(datasetRepository.findBySourcePipelineStepId(step1Id))
        .thenReturn(Optional.of(tempStep1DatasetId));

    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class))).thenReturn(aiConfig);
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(aiTempDatasetId);
    when(datasetRepository.findTableNameById(aiTempDatasetId))
        .thenReturn(Optional.of("ptmp_ai_on_temp"));
    when(aiClassifyExecutor.execute(
            argThat(
                s ->
                    s.inputDatasetIds() != null
                        && s.inputDatasetIds().contains(tempStep1DatasetId)),
            eq(stepExec2Id),
            eq(userId)))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(3L, "ok"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: findBySourcePipelineStepId called to resolve temp step's output
    verify(datasetRepository).findBySourcePipelineStepId(step1Id);
    // and aiClassifyExecutor called with the resolved temp dataset id in inputDatasetIds
    verify(aiClassifyExecutor)
        .execute(
            argThat(
                s ->
                    s.inputDatasetIds() != null
                        && s.inputDatasetIds().contains(tempStep1DatasetId)),
            eq(stepExec2Id),
            eq(userId));
  }

  @Test
  void executeStep_aiClassifyWithExistingInputDatasetIds_doesNotOverride() throws Exception {
    // given: AI_CLASSIFY step already has inputDatasetIds set → should NOT auto-resolve
    Long pipelineId = 24L;
    Long userId = 1L;
    Long executionId = 124L;
    Long step1Id = 226L;
    Long step2Id = 227L;
    Long stepExec1Id = 326L;
    Long stepExec2Id = 327L;
    Long explicitInputDatasetId = 701L;
    Long step1OutputDatasetId = 702L;
    Long aiTempDatasetId = 703L;

    AiClassifyConfig aiConfig =
        new AiClassifyConfig(
            "Classify",
            List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
            List.of("text"),
            null,
            null);

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(
            step1Id,
            "sql-step",
            "SQL",
            "INSERT INTO data.\"t\" VALUES (1)",
            step1OutputDatasetId,
            List.of());

    // AI_CLASSIFY step with explicit inputDatasetIds AND dependsOn
    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            step2Id,
            "ai-explicit",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(explicitInputDatasetId), // already set
            List.of("sql-step"),
            1,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep, aiStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(stepExec1Id);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(stepExec2Id);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(step1OutputDatasetId))
        .thenReturn(Optional.of("sql_out"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");

    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class))).thenReturn(aiConfig);
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(aiTempDatasetId);
    when(datasetRepository.findTableNameById(aiTempDatasetId))
        .thenReturn(Optional.of("ptmp_ai_explicit"));
    when(aiClassifyExecutor.execute(any(), eq(stepExec2Id), eq(userId)))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(2L, "ok"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: aiClassifyExecutor called with the ORIGINAL explicitInputDatasetId (not overridden)
    verify(aiClassifyExecutor)
        .execute(
            argThat(
                s ->
                    s.inputDatasetIds() != null
                        && s.inputDatasetIds().contains(explicitInputDatasetId)
                        && !s.inputDatasetIds().contains(step1OutputDatasetId)),
            eq(stepExec2Id),
            eq(userId));
  }

  // ------------------------------------------------------------------ //
  // resolveStepReferences tests
  // ------------------------------------------------------------------ //

  @Test
  void resolveStepReferences_singleReference_replacedWithTableName() throws Exception {
    // given: pipeline with 2 steps, step2 SQL references {{#1}}
    Long pipelineId = 30L;
    Long userId = 1L;
    Long executionId = 130L;
    Long step1Id = 230L;
    Long step2Id = 231L;
    Long stepExec1Id = 330L;
    Long stepExec2Id = 331L;
    Long outputDatasetId = 50L;

    PipelineStepResponse step1 =
        stepResponseWithOutput(
            step1Id,
            "step1",
            "SQL",
            "INSERT INTO data.\"t\" VALUES (1)",
            outputDatasetId,
            List.of());
    PipelineStepResponse step2 =
        new PipelineStepResponse(
            step2Id,
            "step2",
            null,
            "SQL",
            "SELECT * FROM {{#1}}",
            null,
            null,
            List.of(),
            List.of(),
            1,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(stepExec1Id);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(stepExec2Id);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("table1"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");

    // step2 SELECT with resolved reference
    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    Long tempDsId = 999L;
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_step2"));
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("id", false)));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));

    // capture the SQL passed to sqlExecutor for step2
    when(sqlExecutor.execute(contains("data.\"table1\""))).thenReturn("1 row");

    // when
    service.executePipeline(pipelineId, userId);

    // then: step2 SQL had {{#1}} replaced with data."table1"
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor, atLeastOnce()).execute(captor.capture());
    assertThat(captor.getAllValues()).anyMatch(s -> s.contains("data.\"table1\""));
  }

  @Test
  void resolveStepReferences_multipleReferences_allReplaced() throws Exception {
    // given: 3 steps, step3 references {{#1}} and {{#2}}
    Long pipelineId = 31L;
    Long userId = 1L;
    Long executionId = 131L;
    Long step1Id = 240L;
    Long step2Id = 241L;
    Long step3Id = 242L;
    Long ds1Id = 60L;
    Long ds2Id = 61L;

    PipelineStepResponse step1 =
        stepResponseWithOutput(
            step1Id, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", ds1Id, List.of());
    PipelineStepResponse step2 =
        stepResponseWithOutput(
            step2Id, "s2", "SQL", "INSERT INTO data.\"t\" VALUES (2)", ds2Id, List.of());
    PipelineStepResponse step3 =
        new PipelineStepResponse(
            step3Id,
            "s3",
            null,
            "SQL",
            "SELECT * FROM {{#1}} JOIN {{#2}} ON {{#1}}.id = {{#2}}.id",
            null,
            null,
            List.of(),
            List.of(),
            2,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2, step3));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(340L);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(341L);
    when(executionRepository.createStepExecution(executionId, step3Id)).thenReturn(342L);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(datasetRepository.findTableNameById(ds1Id)).thenReturn(Optional.of("tbl1"));
    when(datasetRepository.findTableNameById(ds2Id)).thenReturn(Optional.of("tbl2"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (2)")).thenReturn("ok");

    // step3 is SELECT — probe for column extraction
    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());
    Long tempDsId = 998L;
    when(tempDatasetService.findExistingTempDataset(step3Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step3Id), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_s3"));
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("id", false)));
    when(sqlExecutor.execute(contains("tbl1"))).thenReturn("ok");

    // when
    service.executePipeline(pipelineId, userId);

    // then: step3 SQL has both references replaced
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor, atLeastOnce()).execute(captor.capture());
    assertThat(captor.getAllValues())
        .anyMatch(s -> s.contains("data.\"tbl1\"") && s.contains("data.\"tbl2\""));
  }

  @Test
  void resolveStepReferences_nonExistentStepNumber_stepFails() throws Exception {
    // given: 1 step, SQL references {{#99}} which doesn't exist
    Long pipelineId = 32L;
    Long userId = 1L;
    Long executionId = 132L;
    Long stepId = 250L;
    Long stepExecId = 350L;

    PipelineStepResponse step =
        new PipelineStepResponse(
            stepId,
            "step1",
            null,
            "SQL",
            "SELECT * FROM {{#99}}",
            null,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));

    // when
    service.executePipeline(pipelineId, userId);

    // then: step marked FAILED with appropriate message
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("스텝 번호 99"),
            isNull(),
            any());
  }

  @Test
  void resolveStepReferences_selfReference_stepFails() throws Exception {
    // given: step3 (stepOrder=2) references {{#3}} (itself)
    Long pipelineId = 33L;
    Long userId = 1L;
    Long executionId = 133L;
    Long step1Id = 260L;
    Long step2Id = 261L;
    Long step3Id = 262L;
    Long ds1Id = 70L;
    Long ds2Id = 71L;
    Long stepExec3Id = 362L;

    PipelineStepResponse step1 =
        stepResponseWithOutput(
            step1Id, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", ds1Id, List.of());
    PipelineStepResponse step2 =
        stepResponseWithOutput(
            step2Id, "s2", "SQL", "INSERT INTO data.\"t\" VALUES (2)", ds2Id, List.of());
    PipelineStepResponse step3 =
        new PipelineStepResponse(
            step3Id,
            "s3",
            null,
            "SQL",
            "SELECT * FROM {{#3}}",
            null,
            null,
            List.of(),
            List.of(),
            2,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2, step3));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(360L);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(361L);
    when(executionRepository.createStepExecution(executionId, step3Id)).thenReturn(stepExec3Id);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(datasetRepository.findTableNameById(ds1Id)).thenReturn(Optional.of("tbl1"));
    when(datasetRepository.findTableNameById(ds2Id)).thenReturn(Optional.of("tbl2"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (2)")).thenReturn("ok");

    // when
    service.executePipeline(pipelineId, userId);

    // then: step3 marked FAILED with self-reference message
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExec3Id),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("자기 자신을 참조"),
            isNull(),
            any());
  }

  @Test
  void resolveStepReferences_noReference_sqlPassedThrough() throws Exception {
    // given: SQL with no {{#N}} references — should pass through unchanged
    Long pipelineId = 34L;
    Long userId = 1L;
    Long executionId = 134L;
    Long stepId = 270L;
    Long stepExecId = 370L;

    String plainSql = "SELECT 1 AS val";
    PipelineStepResponse step =
        new PipelineStepResponse(
            stepId, "plain", null, "SQL", plainSql, null, null, List.of(), List.of(), 0, "REPLACE",
            null, null, null, null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));

    // SELECT → auto temp dataset
    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("val"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());
    Long tempDsId = 997L;
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(stepId), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_plain"));
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("val", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("1 row");

    // when
    service.executePipeline(pipelineId, userId);

    // then: stepRepository.findByPipelineId called only ONCE (not for resolveStepReferences)
    // and sqlExecutor receives the unmodified SELECT
    verify(stepRepository, times(1)).findByPipelineId(pipelineId);
  }

  @Test
  void resolveStepReferences_tempDatasetReference_resolvedViaSourcePipelineStepId()
      throws Exception {
    // given: step1 has outputDatasetId=null (temp), step2 references {{#1}}
    Long pipelineId = 35L;
    Long userId = 1L;
    Long executionId = 135L;
    Long step1Id = 280L;
    Long step2Id = 281L;
    Long step1ExecId = 380L;
    Long step2ExecId = 381L;
    Long tempDsId = 990L;

    // step1: no explicit outputDatasetId (temp dataset created at runtime, stored with
    // source_pipeline_step_id)
    PipelineStepResponse step1 =
        new PipelineStepResponse(
            step1Id,
            "temp-step",
            null,
            "SQL",
            "SELECT id FROM data.\"src\"",
            null,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            null,
            null);
    PipelineStepResponse step2 =
        new PipelineStepResponse(
            step2Id,
            "ref-step",
            null,
            "SQL",
            "SELECT * FROM {{#1}}",
            null,
            null,
            List.of(),
            List.of(),
            1,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(step1ExecId);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(step2ExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));

    // step1 execution: auto-create temp dataset
    Result<?> step1Result = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(step1Result).when(pipelineDsl).fetch(anyString());
    when(tempDatasetService.findExistingTempDataset(step1Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step1Id), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_temp_step"));
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("id", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("ok");

    // step2 resolveStepReferences: step1.outputDatasetId=null → findBySourcePipelineStepId
    when(datasetRepository.findBySourcePipelineStepId(step1Id)).thenReturn(Optional.of(tempDsId));

    // step2 execution: also SELECT → another temp dataset
    Long tempDs2Id = 991L;
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(tempDs2Id);
    when(datasetRepository.findTableNameById(tempDs2Id)).thenReturn(Optional.of("ptmp_ref_step"));
    when(columnRepository.findByDatasetId(tempDs2Id)).thenReturn(List.of(col("id", false)));

    // when
    service.executePipeline(pipelineId, userId);

    // then: findBySourcePipelineStepId called for step1 during step2's resolveStepReferences
    verify(datasetRepository).findBySourcePipelineStepId(step1Id);
    // and step2's SQL used data."ptmp_temp_step"
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor, atLeastOnce()).execute(captor.capture());
    assertThat(captor.getAllValues()).anyMatch(s -> s.contains("data.\"ptmp_temp_step\""));
  }

  // ------------------------------------------------------------------ //
  // Python 스텝 executor 테스트
  // ------------------------------------------------------------------ //

  @Test
  void executeStep_pythonWithExecutorEnabled_sendsMapRequestWithOutputTable() throws Exception {
    // given
    Long pipelineId = 40L;
    Long userId = 1L;
    Long executionId = 140L;
    Long stepId = 240L;
    Long stepExecId = 340L;
    Long outputDatasetId = 80L;

    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "py-step",
            null,
            "PYTHON",
            "print('hello')",
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "APPEND",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(pythonStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("output_py"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("col1", false), col("col2", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "hello\n", 0, null, 100L, 0));

    // enable executor
    setExecutorEnabled(true);
    try {
      // when
      service.executePipeline(pipelineId, userId);

      // then: executorClient.executePython called with map containing script + output_table
      ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
      verify(executorClient).executePython(captor.capture());
      Map<String, Object> sentRequest = captor.getValue();
      assertThat(sentRequest).containsKey("script");
      assertThat(sentRequest).containsKey("output_table");
      assertThat(sentRequest.get("output_table")).isEqualTo("output_py");
      assertThat(sentRequest).containsKey("column_type_map");
    } finally {
      setExecutorEnabled(false);
    }
  }

  @Test
  void executeStep_pythonWithExecutorEnabled_replaceStrategy_swapsTableWhenRowsLoaded()
      throws Exception {
    // given
    Long pipelineId = 41L;
    Long userId = 1L;
    Long executionId = 141L;
    Long stepId = 241L;
    Long stepExecId = 341L;
    Long outputDatasetId = 81L;

    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "py-replace",
            null,
            "PYTHON",
            "print('rows')",
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(pythonStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_replace"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "done", 0, null, 200L, 10));

    setExecutorEnabled(true);
    try {
      // when
      service.executePipeline(pipelineId, userId);

      // then: createTempTable + swapTable called (rows_loaded=10 > 0)
      verify(dataTableService).createTempTable("output_replace");
      verify(dataTableService).swapTable("output_replace");
      verify(dataTableService, never()).dropTempTable(any());
    } finally {
      setExecutorEnabled(false);
    }
  }

  @Test
  void executeStep_pythonWithExecutorEnabled_replaceStrategy_dropsTableWhenNoRows()
      throws Exception {
    // given
    Long pipelineId = 42L;
    Long userId = 1L;
    Long executionId = 142L;
    Long stepId = 242L;
    Long stepExecId = 342L;
    Long outputDatasetId = 82L;

    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "py-norows",
            null,
            "PYTHON",
            "# no output",
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(pythonStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_norows"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "", 0, null, 100L, 0));

    setExecutorEnabled(true);
    try {
      // when
      service.executePipeline(pipelineId, userId);

      // then: createTempTable called, but rows_loaded=0 → dropTempTable (no swap)
      verify(dataTableService).createTempTable("output_norows");
      verify(dataTableService).dropTempTable("output_norows");
      verify(dataTableService, never()).swapTable(any());
    } finally {
      setExecutorEnabled(false);
    }
  }

  @Test
  void executeStep_pythonWithoutPermission_stepFails() throws Exception {
    Long pipelineId = 43L;
    Long userId = 1L;
    Long executionId = 143L;
    Long stepId = 243L;
    Long stepExecId = 343L;

    PipelineStepResponse pythonStep =
        stepResponse(stepId, "py-denied", "PYTHON", "print('x')", null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(pythonStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(false);

    // when
    service.executePipeline(pipelineId, userId);

    // then: step marked FAILED with permission error
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("pipeline:python_execute"),
            isNull(),
            any());
  }

  /** Utility to set the private executorEnabled field via reflection. */
  private void setExecutorEnabled(boolean value) throws Exception {
    Field field = PipelineExecutionService.class.getDeclaredField("executorEnabled");
    field.setAccessible(true);
    field.set(service, value);
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
        null,
        null);
  }

  private DatasetColumnResponse col(String columnName, boolean isPrimaryKey) {
    return new DatasetColumnResponse(
        null, columnName, null, "TEXT", null, true, false, null, 0, isPrimaryKey);
  }
}
