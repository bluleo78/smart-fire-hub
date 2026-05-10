package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
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

/**
 * PipelineAsyncRunner 단위 테스트.
 *
 * <p>실제 스텝 실행 로직(SQL 래핑, 임시 데이터셋 생성, 의존성 스킵 등)을 검증한다. @Async AOP 프록시는 단위 테스트에서 적용되지 않으므로
 * executeAsync는 동기적으로 실행되어 동작을 직접 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTest {

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
  @Mock SqlValidator sqlValidator;

  @InjectMocks PipelineAsyncRunner runner;

  // ------------------------------------------------------------------ //
  // executeAsync — 전체 파이프라인 흐름 테스트
  // ------------------------------------------------------------------ //

  @Test
  void executeAsync_singleSqlStep_completesSuccessfully() {
    // given
    Long pipelineId = 1L;
    Long executionId = 42L;
    Long stepId = 100L;
    Long stepExecId = 200L;
    Long userId = 10L;

    PipelineStepResponse sqlStep =
        stepResponse(stepId, "step1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());

    Map<Long, List<Long>> depMap = Map.of(stepId, List.of());
    Map<Long, Long> stepExecMap = Map.of(stepId, stepExecId);

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("1 row affected");

    // when
    runner.executeAsync(
        pipelineId, executionId, List.of(sqlStep), depMap, stepExecMap, userId, false);

    // then: 실행 레코드 RUNNING → COMPLETED 갱신
    verify(executionRepository).updateExecutionStatus(eq(executionId), eq("RUNNING"), any(), any());
    verify(executionRepository)
        .updateExecutionStatus(eq(executionId), eq("COMPLETED"), any(), any());

    // 완료 이벤트 발행
    ArgumentCaptor<PipelineCompletedEvent> eventCaptor =
        ArgumentCaptor.forClass(PipelineCompletedEvent.class);
    verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().pipelineId()).isEqualTo(pipelineId);
    assertThat(eventCaptor.getValue().executionId()).isEqualTo(executionId);
    assertThat(eventCaptor.getValue().status()).isEqualTo("COMPLETED");
  }

  @Test
  void executeAsync_dependentStepAfterFailedStep_dependentStepSkipped() {
    // given: step1(의존성 없음)이 FAIL → step2(step1에 의존)는 SKIPPED
    Long pipelineId = 2L;
    Long executionId = 50L;
    Long step1Id = 101L;
    Long step2Id = 102L;
    Long stepExec1Id = 201L;
    Long stepExec2Id = 202L;
    Long userId = 10L;

    PipelineStepResponse step1 = stepResponse(step1Id, "step1", "SQL", "BAD SQL", null, List.of());
    PipelineStepResponse step2 =
        stepResponse(step2Id, "step2", "SQL", "SELECT 2", null, List.of("step1"));

    Map<Long, List<Long>> depMap = Map.of(step1Id, List.of(), step2Id, List.of(step1Id));
    Map<Long, Long> stepExecMap = Map.of(step1Id, stepExec1Id, step2Id, stepExec2Id);

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(sqlExecutor.execute("BAD SQL")).thenThrow(new RuntimeException("SQL error"));

    // when
    runner.executeAsync(
        pipelineId, executionId, List.of(step1, step2), depMap, stepExecMap, userId, false);

    // then: step2는 SKIPPED 처리
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
  void executeAsync_exceptionInExecution_marksExecutionFailed() {
    // given: executionRepository 자체가 예외를 던져 전체 실행 실패
    Long pipelineId = 3L;
    Long executionId = 60L;
    Long userId = 10L;

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    doThrow(new RuntimeException("DB error"))
        .when(executionRepository)
        .updateExecutionStatus(eq(executionId), eq("RUNNING"), any(), any());

    // when
    runner.executeAsync(pipelineId, executionId, List.of(), Map.of(), Map.of(), userId, false);

    // then: 실패 상태로 갱신 및 실패 이벤트 발행
    verify(executionRepository).updateExecutionStatus(eq(executionId), eq("FAILED"), any(), any());
    ArgumentCaptor<PipelineCompletedEvent> captor =
        ArgumentCaptor.forClass(PipelineCompletedEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("FAILED");
  }

  // ------------------------------------------------------------------ //
  // SQL 자동 적재 테스트
  // ------------------------------------------------------------------ //

  @Test
  void executeStep_selectWithOutputDataset_wrapsAsInsertIntoSelect() {
    // given
    Long pipelineId = 10L;
    Long userId = 1L;
    Long stepId = 200L;
    Long stepExecId = 300L;
    Long outputDatasetId = 50L;

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "sql-step", "SQL", selectSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_table"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(
            List.of(col("pk_id", true), col("id", false), col("name", false), col("extra", false)));

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"), DSL.field("name"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(sqlExecutor.execute(anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
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
  void executeStep_withCteAndOutputDataset_wrapsAsInsertIntoSelect() {
    // given
    Long pipelineId = 11L;
    Long userId = 1L;
    Long stepId = 201L;
    Long stepExecId = 301L;
    Long outputDatasetId = 51L;

    String cteSql = "WITH t AS (SELECT 1 AS val) SELECT val FROM t";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-step", "SQL", cteSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_cte"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));

    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("val"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(sqlExecutor.execute(anyString())).thenReturn("1 row affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    String executedSql = sqlCaptor.getValue();
    assertThat(executedSql).startsWith("INSERT INTO data.\"output_cte\"");
    assertThat(executedSql).contains("\"val\"");
    assertThat(executedSql).endsWith(cteSql);
  }

  // ------------------------------------------------------------------ //
  // isSelectStatement / CTE DML 오분류 방지 테스트 (#159)
  // ------------------------------------------------------------------ //

  @Test
  void executeStep_withCteUpdateAndOutputDataset_doesNotWrapAsInsert() {
    // given: WITH ... UPDATE DML — INSERT INTO 래핑 없이 SQL을 그대로 실행해야 한다
    Long pipelineId = 13L;
    Long userId = 1L;
    Long stepId = 203L;
    Long stepExecId = 303L;
    Long outputDatasetId = 53L;

    String cteDml =
        "WITH x AS (SELECT id FROM data.\"source\" WHERE condition = true)"
            + " UPDATE data.\"target\" SET col = 1 WHERE id IN (SELECT id FROM x)";

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-update", "SQL", cteDml, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("target_tbl"));
    when(sqlExecutor.execute(anyString())).thenReturn("3 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: INSERT INTO 래핑 없이 원래 SQL 그대로 실행
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+UPDATE DML은 INSERT INTO 래핑 없이 그대로 실행되어야 한다")
        .doesNotStartWith("INSERT INTO");
    assertThat(sqlCaptor.getValue()).isEqualTo(cteDml);
    verify(pipelineDsl, never()).fetch(anyString());
  }

  @Test
  void executeStep_withCteDeleteAndOutputDataset_doesNotWrapAsInsert() {
    // given: WITH ... DELETE DML
    Long pipelineId = 14L;
    Long userId = 1L;
    Long stepId = 204L;
    Long stepExecId = 304L;
    Long outputDatasetId = 54L;

    String cteDml =
        "WITH obsolete AS (SELECT id FROM data.\"logs\" WHERE created_at < '2024-01-01')"
            + " DELETE FROM data.\"logs\" WHERE id IN (SELECT id FROM obsolete)";

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-delete", "SQL", cteDml, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("logs_tbl"));
    when(sqlExecutor.execute(anyString())).thenReturn("5 rows deleted");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+DELETE DML은 INSERT INTO 래핑 없이 그대로 실행되어야 한다")
        .doesNotStartWith("INSERT INTO");
    verify(pipelineDsl, never()).fetch(anyString());
  }

  @Test
  void executeStep_withCteSelectAndOutputDataset_wrapsAsInsert() {
    // given: WITH ... SELECT (CTE SELECT) — INSERT INTO 래핑 되어야 한다
    Long pipelineId = 15L;
    Long userId = 1L;
    Long stepId = 205L;
    Long stepExecId = 305L;
    Long outputDatasetId = 55L;

    String cteSelect =
        "WITH summary AS (SELECT category, COUNT(*) AS cnt FROM data.\"items\" GROUP BY category)"
            + " SELECT category, cnt FROM summary";

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-select", "SQL", cteSelect, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_summary"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("category", false), col("cnt", false)));

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("category"), DSL.field("cnt"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(sqlExecutor.execute(anyString())).thenReturn("10 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: CTE+SELECT는 INSERT INTO로 래핑
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+SELECT는 INSERT INTO 래핑이 되어야 한다")
        .startsWith("INSERT INTO data.\"output_summary\"");
    assertThat(sqlCaptor.getValue()).endsWith(cteSelect);
  }

  @Test
  void executeStep_withCteInsertAndOutputDataset_doesNotWrapDmlAsInsert() {
    // given: CTE 내부에 SELECT가 있어도 최종 문장이 INSERT이면 DML
    Long pipelineId = 16L;
    Long userId = 1L;
    Long stepId = 206L;
    Long stepExecId = 306L;
    Long outputDatasetId = 56L;

    String cteInsert =
        "WITH src AS (SELECT id, val FROM data.\"source\")"
            + " INSERT INTO data.\"dest\" (id, val) SELECT id, val FROM src";

    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "cte-insert", "SQL", cteInsert, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("dest_tbl"));
    when(sqlExecutor.execute(anyString())).thenReturn("7 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: CTE+INSERT DML은 추가 INSERT INTO 래핑 없이 그대로 실행
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+INSERT DML은 이중 INSERT INTO 래핑 없이 원본 SQL 그대로 실행되어야 한다")
        .isEqualTo(cteInsert);
    verify(pipelineDsl, never()).fetch(anyString());
  }

  @Test
  void executeStep_selectWithoutOutputDataset_createsTempDataset() {
    // given: SELECT이지만 outputDatasetId 없음 → 임시 데이터셋 자동 생성
    Long pipelineId = 12L;
    Long userId = 1L;
    Long stepId = 202L;
    Long stepExecId = 302L;
    Long tempDatasetId = 999L;

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "plain-select", "SQL", selectSql, null, List.of());

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"), DSL.field("name"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId))
        .thenReturn(Optional.of("ptmp_12_plain_select_abcd"));

    when(columnRepository.findByDatasetId(tempDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 임시 데이터셋 생성 후 INSERT INTO 래핑
    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).startsWith("INSERT INTO data.\"ptmp_12_plain_select_abcd\"");
  }

  @Test
  void executeStep_selectWithoutOutputDataset_reusesTempDatasetWhenSchemaUnchanged() {
    // given: 기존 임시 데이터셋이 있고 스키마가 변경되지 않은 경우
    Long pipelineId = 15L;
    Long userId = 1L;
    Long stepId = 205L;
    Long stepExecId = 305L;
    Long existingTempDatasetId = 888L;

    String selectSql = "SELECT id FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "reuse-step", "SQL", selectSql, null, List.of());

    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(existingTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(existingTempDatasetId), any())).thenReturn(false);
    when(datasetRepository.findTableNameById(existingTempDatasetId))
        .thenReturn(Optional.of("ptmp_15_reuse_step_1234"));
    when(columnRepository.findByDatasetId(existingTempDatasetId))
        .thenReturn(List.of(col("id", false)));
    when(sqlExecutor.execute(anyString())).thenReturn("1 row affected");

    // when
    runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 새 임시 데이터셋 생성하지 않음
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
  }

  @Test
  void executeStep_selectWithoutOutputDataset_recreatesTempDatasetWhenSchemaChanged() {
    // given: 기존 임시 데이터셋이 있지만 스키마가 변경된 경우
    Long pipelineId = 16L;
    Long userId = 1L;
    Long stepId = 206L;
    Long stepExecId = 306L;
    Long oldTempDatasetId = 777L;
    Long newTempDatasetId = 778L;

    String selectSql = "SELECT id, name, extra FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "schema-change-step", "SQL", selectSql, null, List.of());

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES)
            .newResult(DSL.field("id"), DSL.field("name"), DSL.field("extra"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

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
    runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 기존 데이터셋 삭제 후 새로 생성
    verify(tempDatasetService).deleteTempDataset(oldTempDatasetId);
    verify(tempDatasetService)
        .createTempDataset(any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId));
  }

  @Test
  void executeStep_insertSqlWithOutputDataset_executesAsIs() {
    // given: INSERT SQL — SELECT 래핑 없이 그대로 실행
    Long pipelineId = 13L;
    Long userId = 1L;
    Long stepId = 203L;
    Long stepExecId = 303L;
    Long outputDatasetId = 53L;

    String insertSql = "INSERT INTO data.\"target\" (val) VALUES (1)";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "insert-step", "SQL", insertSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("target"));
    when(sqlExecutor.execute(insertSql)).thenReturn("1 row affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 원본 INSERT 그대로 실행 (래핑 없음)
    assertThat(status).isEqualTo("COMPLETED");
    verify(sqlExecutor).execute(insertSql);
    verify(pipelineDsl, never()).fetch(anyString());
  }

  @Test
  void executeStep_selectNoMatchingColumns_returnsFailed() {
    // given: SELECT 컬럼이 출력 데이터셋 컬럼과 일치하지 않음
    Long pipelineId = 14L;
    Long userId = 1L;
    Long stepId = 204L;
    Long stepExecId = 304L;
    Long outputDatasetId = 54L;

    String selectSql = "SELECT foo, bar FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "no-match", "SQL", selectSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_nomatch"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", true), col("name", false)));

    Result<?> mockResult =
        DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("foo"), DSL.field("bar"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: FAILED 반환, 에러 메시지 포함
    assertThat(status).isEqualTo("FAILED");
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
  void executeStep_apiCallWithoutOutputDataset_createsTempDataset() {
    // given: API_CALL 스텝, outputDatasetId 없음 → fieldMappings에서 임시 데이터셋 자동 생성
    Long pipelineId = 20L;
    Long userId = 1L;
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
    String status =
        runner.executeStep(stepExecId, apiStep, pipelineId, "TestPipeline", userId, false);

    // then: 임시 데이터셋 생성, apiCallExecutor 호출 확인
    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("api-step"), eq(userId));
    verify(apiCallExecutor).execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any(), any());
  }

  @Test
  void executeStep_aiClassifyWithoutOutputDataset_createsTempDataset() {
    // given: AI_CLASSIFY 스텝, outputDatasetId 없음 → 고정 스키마로 임시 데이터셋 자동 생성
    Long pipelineId = 21L;
    Long userId = 1L;
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
    String status =
        runner.executeStep(stepExecId, aiStep, pipelineId, "TestPipeline", userId, false);

    // then: 임시 데이터셋 생성, aiClassifyExecutor 호출 확인
    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("ai-step"), eq(userId));
    verify(aiClassifyExecutor)
        .execute(
            argThat(s -> tempDatasetId.equals(s.outputDatasetId())), eq(stepExecId), eq(userId));
  }

  @Test
  void executeStep_aiClassifyWithDependencyAndNoInputDatasetIds_autoResolvesInputFromDepStep() {
    // given: SQL 스텝(step1)이 명시적 outputDatasetId를 가짐; AI_CLASSIFY(step2)는
    // inputDatasetIds가 비어있지만 step1에 의존 → 자동 해결
    Long pipelineId = 22L;
    Long userId = 1L;
    Long step1Id = 222L;
    Long step2Id = 223L;
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

    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            step2Id,
            "ai-classify",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(), // inputDatasetIds 비어있음
            List.of("sql-source"), // sql-source에 의존
            1,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep, aiStep));
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
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
    runner.executeStep(stepExec2Id, aiStep, pipelineId, "TestPipeline", userId, false);

    // then: aiClassifyExecutor가 step1 출력을 inputDatasetIds에 포함해 호출됨
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
  void executeStep_pythonWithoutPermission_returnsFailed() {
    // given: python_execute 권한 없음
    Long pipelineId = 43L;
    Long userId = 1L;
    Long stepId = 243L;
    Long stepExecId = 343L;

    PipelineStepResponse pythonStep =
        stepResponse(stepId, "py-denied", "PYTHON", "print('x')", null, List.of());

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(false);

    // when
    String status =
        runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, false);

    // then: FAILED 반환, 권한 오류 메시지 포함
    assertThat(status).isEqualTo("FAILED");
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

  @Test
  void executeStep_pythonWithExecutorEnabled_sendsMapRequestWithOutputTable() {
    // given
    Long pipelineId = 40L;
    Long userId = 1L;
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

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("output_py"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("col1", false), col("col2", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "hello\n", 0, null, 100L, 0));

    // when — executorEnabled=true
    String status =
        runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: executorClient.executePython이 script + output_table + column_type_map 포함 Map으로 호출됨
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executePython(captor.capture());
    Map<String, Object> sentRequest = captor.getValue();
    assertThat(sentRequest).containsKey("script");
    assertThat(sentRequest).containsKey("output_table");
    assertThat(sentRequest.get("output_table")).isEqualTo("output_py");
    assertThat(sentRequest).containsKey("column_type_map");
  }

  @Test
  void executeStep_pythonWithExecutorEnabled_replaceStrategy_swapsTableWhenRowsLoaded() {
    // given
    Long pipelineId = 41L;
    Long userId = 1L;
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

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_replace"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "done", 0, null, 200L, 10));

    // when
    runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: createTempTable + swapTable (rows_loaded=10 > 0)
    verify(dataTableService).createTempTable("output_replace");
    verify(dataTableService).swapTable("output_replace");
    verify(dataTableService, never()).dropTempTable(any());
  }

  @Test
  void executeStep_pythonWithExecutorEnabled_replaceStrategy_dropsTableWhenNoRows() {
    // given
    Long pipelineId = 42L;
    Long userId = 1L;
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

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_norows"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("val", false)));
    when(executorClient.executePython(any()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "", 0, null, 100L, 0));

    // when
    runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: createTempTable 후 rows_loaded=0 → dropTempTable (swap 없음)
    verify(dataTableService).createTempTable("output_norows");
    verify(dataTableService).dropTempTable("output_norows");
    verify(dataTableService, never()).swapTable(any());
  }

  // ------------------------------------------------------------------ //
  // resolveStepReferences 테스트 (executeAsync를 통해 간접 검증)
  // ------------------------------------------------------------------ //

  @Test
  void executeAsync_resolveStepReferences_singleReference_replacedWithTableName() {
    // given: pipeline의 step2 SQL이 {{#1}}을 참조
    Long pipelineId = 30L;
    Long userId = 1L;
    Long executionId = 130L;
    Long step1Id = 230L;
    Long step2Id = 231L;
    Long step1ExecId = 330L;
    Long step2ExecId = 331L;
    Long outputDatasetId = 50L;
    Long tempDsId = 999L;

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

    Map<Long, List<Long>> depMap = Map.of(step1Id, List.of(), step2Id, List.of());
    Map<Long, Long> stepExecMap = Map.of(step1Id, step1ExecId, step2Id, step2ExecId);

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("table1"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");

    // step2: SELECT → 임시 데이터셋 자동 생성
    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    Result<?> mockResult = DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(DSL.field("id"));
    doReturn(mockResult).when(pipelineDsl).fetch(anyString());
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_step2"));
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("id", false)));
    when(sqlExecutor.execute(contains("data.\"table1\""))).thenReturn("1 row");

    // when
    runner.executeAsync(
        pipelineId, executionId, List.of(step1, step2), depMap, stepExecMap, userId, false);

    // then: step2 SQL에 {{#1}}이 data."table1"로 치환됨
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor, atLeastOnce()).execute(captor.capture());
    assertThat(captor.getAllValues()).anyMatch(s -> s.contains("data.\"table1\""));
  }

  @Test
  void executeAsync_resolveStepReferences_selfReference_stepFails() {
    // given: step3(stepOrder=2)이 {{#3}}(자기 자신)을 참조
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

    Map<Long, List<Long>> depMap =
        Map.of(step1Id, List.of(), step2Id, List.of(), step3Id, List.of());
    Map<Long, Long> stepExecMap = Map.of(step1Id, 360L, step2Id, 361L, step3Id, stepExec3Id);

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(datasetRepository.findTableNameById(ds1Id)).thenReturn(Optional.of("tbl1"));
    when(datasetRepository.findTableNameById(ds2Id)).thenReturn(Optional.of("tbl2"));
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(sqlExecutor.execute("INSERT INTO data.\"t\" VALUES (2)")).thenReturn("ok");
    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2, step3));

    // when
    runner.executeAsync(
        pipelineId, executionId, List.of(step1, step2, step3), depMap, stepExecMap, userId, false);

    // then: step3는 자기 참조 오류로 FAILED
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

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

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
