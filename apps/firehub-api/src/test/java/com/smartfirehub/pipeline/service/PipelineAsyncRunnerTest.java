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
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.time.OffsetDateTime;
import java.util.Arrays;
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
  @Mock SqlValidator sqlValidator;
  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;

  @InjectMocks PipelineAsyncRunner runner;

  /**
   * SQL probe 가 돌려줄 컬럼 목록을 고정한다.
   *
   * <p>타입은 이 테스트가 검증하는 대상이 아니라 TEXT 로 고정한다(타입 매핑 자체는 probe 의 몫이다).
   */
  private void stubProbeColumns(String... names) {
    doReturn(Arrays.stream(names).map(n -> new ColumnInfo(n, "TEXT")).toList())
        .when(sqlColumnProbe)
        .columnsWithTypes(anyString());
  }

  /**
   * 스텝 SQL 조립이 {@code DataSchema.current()} 를 거치면서 테넌트 컨텍스트를 요구하게 됐다.
   * 운영에서는 {@code pipelineExecutor} 에 붙은 {@code TenantContextTaskDecorator} 가 호출 스레드의
   * 컨텍스트를 승계하므로(AsyncConfig) 이 클래스가 세우는 것은 그 승계 결과를 흉내 내는 것이다.
   *
   * <p>DB 를 쓰지 않는 순수 목(mock) 테스트이므로 GUC·트랜잭션과는 무관하다 — 즉 "프로덕션 경로가
   * 스스로 컨텍스트를 세우지 못한다"는 배선 결함을 가리지 않는다(승계 주체는 executor 데코레이터).
   */
  @BeforeEach
  void setTenantContext() {
    TenantContext.set(1L);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

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
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (1)"))
        .thenReturn("1 row affected");

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
    when(sqlExecutor.execute(List.of(), "BAD SQL")).thenThrow(new RuntimeException("SQL error"));

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

    // then: 실패 상태로 갱신 및 실패 이벤트 발행 — 예외 메시지도 함께 저장돼야 한다(#517)
    verify(executionRepository)
        .updateExecutionStatus(eq(executionId), eq("FAILED"), any(), any(), eq("DB error"));
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

    stubProbeColumns("id", "name");

    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    String executedSql = sqlCaptor.getValue();
    assertThat(executedSql).startsWith("INSERT INTO data.\"output_table\"");
    assertThat(executedSql).contains("\"id\"");
    assertThat(executedSql).contains("\"name\"");
    assertThat(executedSql).doesNotContain("\"pk_id\"");
    assertThat(executedSql).doesNotContain("\"extra\"");
    assertThat(executedSql).endsWith(selectSql);
  }

  /**
   * 출력 데이터셋이 <b>업무 키(PK)</b> 컬럼을 선언했고 SELECT 가 그 컬럼을 함께 낼 때, 그 컬럼이
   * INSERT 대상 목록에 살아 있어야 한다(#684).
   *
   * <p>이 경로는 대상 컬럼 목록만 좁히고 SELECT 식 목록은 그대로 둔다. 그래서 대상에서 한 컬럼이라도
   * 빠지면 식 개수가 어긋나 PostgreSQL 이 {@code INSERT has more expressions than target columns} 로
   * 거부한다 — 2026-09-17 운영 장애의 실제 실패 문구다.
   *
   * <p><b>왜 PK 컬럼을 빼면 안 되는가</b>: 물리 PK 는 {@code DataTableService} 가
   * {@code id BIGSERIAL PRIMARY KEY} 로 따로 만든다. {@code id}/{@code import_id}/{@code created_at}
   * 은 시스템 예약어라 {@code dataset_column} 에 들어올 수 없다. 즉 {@code is_primary_key} 가 붙은
   * 컬럼은 시스템이 채우는 대리키가 아니라 <b>사용자가 값을 넣어야 하는 업무 키</b>이고(기본값 없음),
   * {@code DatasetService} 가 거기에 NOT NULL 까지 강제한다. 빼면 넣을 방법이 사라진다.
   *
   * <p>그래서 개수까지 본다 — 컬럼 이름만 확인하면 목록이 한 칸 어긋나는 회귀를 놓친다.
   */
  @Test
  void executeStep_selectIncludingPrimaryKeyColumn_keepsItAmongInsertTargets() {
    // given: 출력 데이터셋의 post_id 가 업무 키로 선언돼 있고, SELECT 가 그것까지 낸다
    Long pipelineId = 10L;
    Long userId = 1L;
    Long stepId = 201L;
    Long stepExecId = 301L;
    Long outputDatasetId = 51L;

    String selectSql = "SELECT post_id, summary, urgency FROM data.\"src\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "load-step", "SQL", selectSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("analysis_table"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("post_id", true), col("summary", false), col("urgency", false)));

    stubProbeColumns("post_id", "summary", "urgency");

    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("3 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    String executedSql = sqlCaptor.getValue();

    String columnList =
        executedSql.substring(executedSql.indexOf('(') + 1, executedSql.indexOf(')'));
    assertThat(columnList.split(","))
        .as("SELECT 식 3개와 INSERT 대상 3개가 정확히 맞아야 한다 — 어긋나면 운영에서 개수 불일치로 거부된다")
        .hasSize(3);
    assertThat(columnList)
        .as("업무 키 컬럼은 시스템이 채워주지 않는다 — 대상에서 빠지면 넣을 방법이 없다")
        .contains("\"post_id\"");
    assertThat(executedSql).startsWith("INSERT INTO data.\"analysis_table\"");
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

    stubProbeColumns("val");

    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("1 row affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
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
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("3 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: INSERT INTO 래핑 없이 원래 SQL 그대로 실행
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+UPDATE DML은 INSERT INTO 래핑 없이 그대로 실행되어야 한다")
        .doesNotStartWith("INSERT INTO");
    assertThat(sqlCaptor.getValue()).isEqualTo(cteDml);
    verifyNoInteractions(sqlColumnProbe);
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
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("5 rows deleted");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+DELETE DML은 INSERT INTO 래핑 없이 그대로 실행되어야 한다")
        .doesNotStartWith("INSERT INTO");
    verifyNoInteractions(sqlColumnProbe);
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

    stubProbeColumns("category", "cnt");

    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("10 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: CTE+SELECT는 INSERT INTO로 래핑
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
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
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("7 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: CTE+INSERT DML은 추가 INSERT INTO 래핑 없이 그대로 실행
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .as("CTE+INSERT DML은 이중 INSERT INTO 래핑 없이 원본 SQL 그대로 실행되어야 한다")
        .isEqualTo(cteInsert);
    verifyNoInteractions(sqlColumnProbe);
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

    stubProbeColumns("id", "name");

    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId))
        .thenReturn(Optional.of("ptmp_12_plain_select_abcd"));

    // SELECT 컬럼 id는 시스템 예약어이므로 임시 데이터셋에는 id_1로 별칭 처리되어 저장된다(#645)
    when(columnRepository.findByDatasetId(tempDatasetId))
        .thenReturn(List.of(col("id_1", false), col("name", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 임시 데이터셋 생성 후 INSERT INTO 래핑
    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).startsWith("INSERT INTO data.\"ptmp_12_plain_select_abcd\"");
  }

  @Test
  void executeStep_selectStarWithReservedColumnNames_autoRenamesInsteadOfFailing() {
    // given: SELECT * FROM {{#N}} 패턴 재현 — 이전 스텝(또는 실제 데이터셋)의 결과에는 시스템 예약
    // 컬럼(id, created_at)이 이미 포함돼 있다. 예약어를 그대로 새 임시 데이터셋의 사용자 컬럼으로
    // 넘기면 DataTableService의 예약어 가드에 걸려 항상 실패했다(#645). 이 테스트는 PipelineAsyncRunner가
    // 그 경로에 도달하기 전에 예약어 컬럼명을 자동으로 별칭 처리(id → id_1)해 우회하는지 검증한다.
    Long pipelineId = 20L;
    Long userId = 1L;
    Long stepId = 210L;
    Long stepExecId = 310L;
    Long tempDatasetId = 991L;

    String selectSql = "SELECT * FROM data.\"ptmp_1_step1\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "select-star-step", "SQL", selectSql, null, List.of());

    // SELECT * 결과에는 id/created_at/name이 순서대로 포함된다 (실제 데이터셋 물리 테이블의 시스템 컬럼 + 사용자 컬럼)
    stubProbeColumns("id", "created_at", "name");

    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.empty());

    ArgumentCaptor<List<ColumnInfo>> columnsCaptor = ArgumentCaptor.forClass(List.class);
    when(tempDatasetService.createTempDataset(
            columnsCaptor.capture(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(tempDatasetId);
    when(datasetRepository.findTableNameById(tempDatasetId))
        .thenReturn(Optional.of("ptmp_20_select_star_step_abcd"));

    // 임시 데이터셋에 실제로 저장된 컬럼명(별칭 처리된 결과)을 그대로 반영
    when(columnRepository.findByDatasetId(tempDatasetId))
        .thenReturn(List.of(col("id_1", false), col("created_at_1", false), col("name", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("3 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 예약어 가드 진입 전에 별칭 처리되어 정상 완료
    assertThat(status).isEqualTo("COMPLETED");

    List<ColumnInfo> createdColumns = columnsCaptor.getValue();
    assertThat(createdColumns).extracting(ColumnInfo::name).doesNotContain("id", "created_at");
    assertThat(createdColumns)
        .extracting(ColumnInfo::name)
        .containsExactly("id_1", "created_at_1", "name");

    // INSERT 컬럼 목록도 별칭과 동일해야 실제 저장된 컬럼과 매칭된다
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor).execute(anyList(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("\"id_1\"", "\"created_at_1\"", "\"name\"");
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

    stubProbeColumns("id");

    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(existingTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(existingTempDatasetId), any())).thenReturn(false);
    when(datasetRepository.findTableNameById(existingTempDatasetId))
        .thenReturn(Optional.of("ptmp_15_reuse_step_1234"));
    // SELECT 컬럼 id는 시스템 예약어이므로 임시 데이터셋에는 id_1로 별칭 처리되어 저장돼 있다(#645)
    when(columnRepository.findByDatasetId(existingTempDatasetId))
        .thenReturn(List.of(col("id_1", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("1 row affected");

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

    stubProbeColumns("id", "name", "extra");

    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(oldTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(oldTempDatasetId), any())).thenReturn(true);
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), anyString(), eq(stepId), anyString(), eq(userId)))
        .thenReturn(newTempDatasetId);
    when(datasetRepository.findTableNameById(newTempDatasetId))
        .thenReturn(Optional.of("ptmp_16_schema_change_step_5678"));
    // SELECT 컬럼 id는 시스템 예약어이므로 임시 데이터셋에는 id_1로 별칭 처리되어 저장된다(#645)
    when(columnRepository.findByDatasetId(newTempDatasetId))
        .thenReturn(List.of(col("id_1", false), col("name", false), col("extra", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("3 rows affected");

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
    when(sqlExecutor.execute(List.of(), insertSql)).thenReturn("1 row affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 원본 INSERT 그대로 실행 (래핑 없음)
    assertThat(status).isEqualTo("COMPLETED");
    verify(sqlExecutor).execute(List.of(), insertSql);
    verify(dataTableRowService, never()).truncateTable(anyString());
    verifyNoInteractions(sqlColumnProbe);
  }

  // ------------------------------------------------------------------ //
  // REPLACE 원자화(Task 4) — 출력 비우기(DELETE)와 INSERT를 같은 요청/트랜잭션으로 보낸다
  // ------------------------------------------------------------------ //

  /**
   * REPLACE 전략의 SELECT 자동 적재는 더 이상 즉시 truncate 하지 않는다. 대신 DELETE 문을 INSERT 와
   * 같은 executor 요청(preStatements)에 실어, 실행기가 같은 트랜잭션에서 순서대로 실행하게 한다 — 이래야
   * INSERT 가 실패해도 DELETE 까지 같이 롤백되어 출력이 빈 채로 남지 않는다(원래 결함).
   *
   * <p>fixture 는 :213 {@code executeStep_selectWithOutputDataset_wrapsAsInsertIntoSelect} 의
   * given(출력 데이터셋이 이미 지정된 SELECT 스텝)을 그대로 복제하되, loadStrategy=REPLACE ·
   * executorEnabled=true 로 바꿔 원자성 계약을 검증한다.
   */
  @Test
  void REPLACE_SELECT_SQL은_truncate하지_않고_DELETE를_선행문장으로_같은_요청에_보낸다() {
    // given
    Long pipelineId = 40L;
    Long userId = 1L;
    Long stepId = 400L;
    Long stepExecId = 500L;
    Long outputDatasetId = 60L;
    String outputTable = "output_table_replace";

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-replace",
            null,
            "SQL",
            selectSql,
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

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 0, "2 rows affected", null));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then: truncate 는 한 번도 호출되지 않고, DELETE 가 INSERT 와 같은 요청으로 간다
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> preStatementsCaptor = ArgumentCaptor.forClass(List.class);
    verify(executorClient).executeSql(startsWith("INSERT INTO"), preStatementsCaptor.capture());
    // 리터럴로 못박는다 — firehub-executor 의 선행 문장 화이트리스트 정규식
    // (^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"$) 이 요구하는 형태(스키마·테이블 양쪽 인용)와
    // 정확히 같은지, OutputClearStatement 를 거치지 않고 독립적으로 확인한다.
    assertThat(preStatementsCaptor.getValue())
        .containsExactly("DELETE FROM \"data\".\"" + outputTable + "\"");
  }

  /**
   * APPEND 전략은 출력을 비우지 않으므로 선행 문장도 없어야 한다 — REPLACE 전용 경로가 APPEND 까지
   * 잘못 건드리지 않는지 확인하는 회귀 가드.
   */
  @Test
  void APPEND_SELECT_SQL은_선행문장이_없다() {
    // given: stepResponseWithOutput 은 loadStrategy=APPEND 를 쓴다(:213 fixture와 동일 helper)
    Long pipelineId = 41L;
    Long userId = 1L;
    Long stepId = 401L;
    Long stepExecId = 501L;
    Long outputDatasetId = 61L;

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(
            stepId, "sql-step-append", "SQL", selectSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_table_append"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 0, "2 rows affected", null));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(executorClient).executeSql(startsWith("INSERT INTO"), eq(List.of()));
  }

  /** 실행기가 꺼진 경로({@code sqlExecutor})도 같은 원자성 계약을 따라야 한다. */
  @Test
  void REPLACE_SELECT_SQL_실행기_꺼진_경로도_DELETE를_선행문장으로_같은_요청에_보낸다() {
    // given
    Long pipelineId = 42L;
    Long userId = 1L;
    Long stepId = 402L;
    Long stepExecId = 502L;
    Long outputDatasetId = 62L;
    String outputTable = "output_table_replace_offline";

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-replace-offline",
            null,
            "SQL",
            selectSql,
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

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(sqlExecutor)
        .execute(
            eq(List.of("DELETE FROM \"data\".\"" + outputTable + "\"")), startsWith("INSERT INTO"));
  }

  /**
   * Fix round 1, 리뷰 지적 1 — 회귀 가드. loadStrategy 컬럼은 서버에 enum·체크 제약이 없어
   * ({@code PipelineStepRepository} 는 null 만 "REPLACE" 로 매핑) 임의 문자열이 그대로 들어올 수
   * 있다. Task 4 이전에는 상단 switch 의 default 분기가 알 수 없는 값을 REPLACE 로 폴백하며
   * truncate 했는데, SQL 스텝을 그 switch 밖으로 뺀 뒤 "REPLACE".equalsIgnoreCase 만으로 판단하면
   * 알 수 없는 값이 아무것도 비우지 않고 매 실행마다 행이 누적된다 — 이 테스트는 그 회귀를 막는다.
   */
  @Test
  void 알수없는_loadStrategy의_SQL_SELECT_스텝도_REPLACE로_취급해_DELETE_선행문장을_보낸다() {
    // given
    Long pipelineId = 43L;
    Long userId = 1L;
    Long stepId = 403L;
    Long stepExecId = 503L;
    Long outputDatasetId = 63L;
    String outputTable = "output_table_unknown_strategy";

    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-unknown-strategy",
            null,
            "SQL",
            selectSql,
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "GARBAGE", // REPLACE 도 APPEND 도 아닌 알 수 없는 값 — 검증되지 않은 컬럼이므로 실제 도달 가능
            null,
            null,
            null,
            null);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("2 rows affected");

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: REPLACE 로 폴백해 DELETE 선행 문장을 보낸다 — truncate 도, "아무것도 안 비움"도 아니다
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(sqlExecutor)
        .execute(
            eq(List.of("DELETE FROM \"data\".\"" + outputTable + "\"")), startsWith("INSERT INTO"));
  }

  // ------------------------------------------------------------------ //
  // MERGE 로드 전략(Task 5) — 출력 데이터셋 PK 기준 upsert
  // ------------------------------------------------------------------ //

  /**
   * MERGE 는 APPEND 와 마찬가지로 출력을 비우지 않는다(존재 이유 자체가 "기존 행 보존 + upsert") —
   * truncate 도, DELETE 선행 문장도 없어야 한다.
   */
  @Test
  void MERGE_SQL_스텝이면_truncateTable이_호출되지_않는다() {
    // given
    Long pipelineId = 44L;
    Long userId = 1L;
    Long stepId = 404L;
    Long stepExecId = 504L;
    Long outputDatasetId = 64L;
    String outputTable = "output_table_merge";

    String selectSql = "SELECT code, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-merge",
            null,
            "SQL",
            selectSql,
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 0, "2 rows affected", null));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> preStatementsCaptor = ArgumentCaptor.forClass(List.class);
    verify(executorClient).executeSql(sqlCaptor.capture(), preStatementsCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("ON CONFLICT (\"code\")");
    assertThat(preStatementsCaptor.getValue()).isEmpty();
  }

  /**
   * PostgreSQL 이 "ON CONFLICT DO UPDATE command cannot affect row a second time" 를 돌려주면
   * (SELECT 가 같은 PK 를 두 번 이상 낼 때) 스텝이 FAILED 가 되고, 오류 메시지에 "같은 키"가
   * 포함돼야 한다 — 사용자가 원인(PG 내부 문구가 아니라)을 바로 알 수 있어야 한다.
   */
  @Test
  void MERGE_중복키_오류는_같은_키_안내_메시지로_바뀐다() {
    // given
    Long pipelineId = 45L;
    Long userId = 1L;
    Long stepId = 405L;
    Long stepExecId = 505L;
    Long outputDatasetId = 65L;
    String outputTable = "output_table_merge_dup";

    String selectSql = "SELECT code, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-merge-dup",
            null,
            "SQL",
            selectSql,
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                false,
                List.of(),
                List.of(),
                0,
                null,
                "ERROR: ON CONFLICT DO UPDATE command cannot affect row a second time"));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("같은 키"),
            isNull(),
            any());
  }

  /**
   * Fix round 1, must 2 — 위 테스트는 {@code executorEnabled=true}(=result.error() 분기)만 태운다.
   * 실행기를 끈 경로({@code sqlExecutor.execute} 가 {@link ScriptExecutionException}을 던지는 경로)는
   * test 프로필의 기본값이자 Task 6 통합 테스트가 실제로 타는 경로인데, 그 catch 분기(:531-544)가
   * 이 브랜치 전용 테스트 없이는 검증되지 않는다.
   */
  @Test
  void MERGE_중복키_오류는_실행기_꺼진_경로에서도_같은_키_안내_메시지로_바뀐다() {
    // given
    Long pipelineId = 46L;
    Long userId = 1L;
    Long stepId = 406L;
    Long stepExecId = 506L;
    Long outputDatasetId = 66L;
    String outputTable = "output_table_merge_dup_offline";

    String selectSql = "SELECT code, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-merge-dup-offline",
            null,
            "SQL",
            selectSql,
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(sqlExecutor.execute(anyList(), anyString()))
        .thenThrow(
            new ScriptExecutionException(
                "SQL execution failed: ERROR: ON CONFLICT DO UPDATE command cannot affect row a"
                    + " second time"));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(sqlExecutor).execute(eq(List.of()), anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("같은 키"),
            isNull(),
            any());
  }

  /**
   * Fix round 1, must 4 — 중복키 번역과 형제인 {@code NO_UNIQUE_CONSTRAINT_PG_MESSAGE} 번역 분기가
   * 지금까지 테스트 없이 방치돼 있었다. {@code createPrimaryKeyIndexConcurrently} 가 남길 수 있는
   * INVALID {@code ux_<table>_pk} 인덱스처럼, PK 메타데이터는 있는데 실제 유니크 제약이 없을 때
   * PostgreSQL 이 이 문구로 거부한다 — "PK 를 다시 지정하라"는 한국어 안내로 바뀌는지 확인한다.
   */
  @Test
  void MERGE_유니크_제약_없음_오류는_PK_재지정_안내_메시지로_바뀐다() {
    // given
    Long pipelineId = 47L;
    Long userId = 1L;
    Long stepId = 407L;
    Long stepExecId = 507L;
    Long outputDatasetId = 67L;
    String outputTable = "output_table_merge_invalid_pk";

    String selectSql = "SELECT code, name FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        new PipelineStepResponse(
            stepId,
            "sql-step-merge-invalid-pk",
            null,
            "SQL",
            selectSql,
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                false,
                List.of(),
                List.of(),
                0,
                null,
                "ERROR: there is no unique or exclusion constraint matching the ON CONFLICT"
                    + " specification"));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("PK 를 다시 지정"),
            isNull(),
            any());
  }

  /**
   * Fix round 2, should 3 — MERGE+비SQL 거부를 스텝 타입 분기(:304 근방) 전체보다 앞으로 옮긴
   * 변경(Fix round 1, nit 5)이 실제로 API_CALL/AI_CLASSIFY/실행기 켠 PYTHON 조합까지 잡는지 확인한다.
   * 이전 위치(비SQL 타입 전용 switch 안)에서는 이 조합이 switch 진입 조건에서 이미 제외돼 있어
   * 거부되지 않고 조용히 통과했었다.
   */
  @Test
  void MERGE는_실행기_켠_PYTHON_스텝에서도_거부된다() {
    // given
    Long pipelineId = 48L;
    Long userId = 1L;
    Long stepId = 408L;
    Long stepExecId = 508L;
    Long outputDatasetId = 68L;

    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "python-step-merge",
            null,
            "PYTHON",
            "print('hi')",
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null);

    // when: 실행기 켠 경로(executorEnabled=true) — PYTHON 은 executorEnabled 일 때 상단 switch
    // 진입 조건에서 제외되는 타입 중 하나다.
    String status =
        runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("MERGE 는 SQL 스텝 전용입니다"),
            isNull(),
            any());
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

    stubProbeColumns("foo", "bar");

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
  void executeStep_selectProbeQueryFails_usesRootCauseMessageNotJooqInternals() {
    // given: SELECT 컬럼 스키마 추론용 probe 쿼리가 실패(존재하지 않는 테이블 등)하는 상황(#662).
    // jOOQ의 DataAccessException.getMessage()는 "jOOQ; bad SQL grammar [probe SQL 원문]" 형식으로
    // 사용자가 작성하지 않은 내부 구현 세부사항(AS _probe LIMIT 0 래핑)을 그대로 노출하므로,
    // 실제 근본 원인(cause)이 우선 사용돼야 한다.
    Long pipelineId = 15L;
    Long userId = 1L;
    Long stepId = 205L;
    Long stepExecId = 305L;
    Long outputDatasetId = 55L;

    String selectSql = "SELECT * FROM data.\"nonexistent_table\"";
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(stepId, "probe-fail", "SQL", selectSql, outputDatasetId, List.of());

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_probefail"));

    // probe 래핑을 벗겨 근본 원인만 남기는 책임은 SqlColumnProbe 로 옮겼다(SqlColumnProbeTest 가
    // 그 추출 자체를 검증한다). 여기서는 러너가 그 메시지를 스텝 실행 기록에 그대로 실어 보내는지만 본다.
    String rootCauseMessage =
        "ERROR: relation \"data.nonexistent_table\" does not exist\n  Position: 15";
    doThrow(new ScriptExecutionException("SQL 컬럼 타입 분석 실패: " + rootCauseMessage))
        .when(sqlColumnProbe)
        .columnsWithTypes(anyString());

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: FAILED 반환, 근본 원인 메시지 포함 + probe 래핑 내부 구현 디테일은 노출되지 않음
    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), errorCaptor.capture(), isNull(), any());
    String errorMessage = errorCaptor.getValue();
    assertThat(errorMessage).contains("relation \"data.nonexistent_table\" does not exist");
    assertThat(errorMessage).doesNotContain("_probe");
    assertThat(errorMessage).doesNotContain("jOOQ");
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
    // 스왑 여부의 판단은 DataTableService.finishReplace 가 단독으로 갖는다(#685) — 여기서는
    // 실행기가 **적재된 행 수를 정확히 넘겼는지**만 본다. 판단 자체는 DataTableServiceTest 가 본다.
    verify(dataTableService).finishReplace("output_replace", 10L);
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
    // 0행이 그대로 전달돼야 finishReplace 가 원본을 지키는 쪽으로 판단할 수 있다.
    verify(dataTableService).finishReplace("output_norows", 0L);
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
            List.of("step1"), // {{#1}} 참조가 실제 선행 스텝(step1)의 의존성 체인에 포함돼야 하므로 명시 (#531)
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
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");

    // step2: SELECT → 임시 데이터셋 자동 생성
    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    stubProbeColumns("id");
    when(tempDatasetService.findExistingTempDataset(step2Id)).thenReturn(Optional.empty());
    when(tempDatasetService.createTempDataset(
            any(), eq(pipelineId), any(), eq(step2Id), any(), eq(userId)))
        .thenReturn(tempDsId);
    when(datasetRepository.findTableNameById(tempDsId)).thenReturn(Optional.of("ptmp_step2"));
    // "SELECT *"로 시스템 예약 컬럼 id가 그대로 섞여 들어오므로, 임시 데이터셋 생성 시 id_1로
    // 자동 별칭 처리된다(#645) — mock도 실제 저장되는 컬럼명과 일치시킨다.
    when(columnRepository.findByDatasetId(tempDsId)).thenReturn(List.of(col("id_1", false)));
    when(sqlExecutor.execute(anyList(), contains("data.\"table1\""))).thenReturn("1 row");

    // when
    runner.executeAsync(
        pipelineId, executionId, List.of(step1, step2), depMap, stepExecMap, userId, false);

    // then: step2 SQL에 {{#1}}이 data."table1"로 치환됨
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(sqlExecutor, atLeastOnce()).execute(anyList(), captor.capture());
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
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (2)")).thenReturn("ok");
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

  @Test
  void executeAsync_resolveStepReferences_nonAncestorStep_stepFails() {
    // given: s2(stepOrder=1, s1에만 의존)가 아직 실행되지 않은 후행 스텝 s3({{#3}})을 참조한다.
    // "스텝 삽입"으로 DAG가 비선형이 되어 s2가 s3보다 먼저 실행되는 상황을 재현한다 (#531).
    Long pipelineId = 34L;
    Long userId = 1L;
    Long executionId = 134L;
    Long step1Id = 270L;
    Long step2Id = 271L;
    Long step3Id = 272L;
    Long ds1Id = 80L;
    Long ds3Id = 81L;
    Long stepExec2Id = 372L;

    PipelineStepResponse step1 =
        stepResponseWithOutput(
            step1Id, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", ds1Id, List.of());
    PipelineStepResponse step2 =
        new PipelineStepResponse(
            step2Id,
            "s2",
            null,
            "SQL",
            "SELECT * FROM {{#3}}",
            null,
            null,
            List.of(),
            List.of("s1"), // s1에만 의존 — s3는 조상이 아니다
            1,
            "REPLACE",
            null,
            null,
            null,
            null);
    PipelineStepResponse step3 =
        stepResponseWithOutput(
            step3Id, "s3", "SQL", "INSERT INTO data.\"t\" VALUES (3)", ds3Id, List.of("s1"));

    Map<Long, List<Long>> depMap =
        Map.of(step1Id, List.of(), step2Id, List.of(), step3Id, List.of());
    Map<Long, Long> stepExecMap = Map.of(step1Id, 370L, step2Id, stepExec2Id, step3Id, 371L);

    when(pipelineRepository.findCreatedByIdById(pipelineId)).thenReturn(Optional.of(userId));
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));
    when(datasetRepository.findTableNameById(ds1Id)).thenReturn(Optional.of("tbl1"));
    when(datasetRepository.findTableNameById(ds3Id)).thenReturn(Optional.of("tbl3"));
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (1)")).thenReturn("ok");
    when(sqlExecutor.execute(List.of(), "INSERT INTO data.\"t\" VALUES (3)")).thenReturn("ok");
    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2, step3));

    // when: step1 → step2 → step3 순서로 실행 (step2가 아직 산출물 없는 step3을 참조)
    runner.executeAsync(
        pipelineId, executionId, List.of(step1, step2, step3), depMap, stepExecMap, userId, false);

    // then: step2는 "선행 스텝(의존성 체인)이 아닙니다" 오류로 FAILED
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExec2Id),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("선행 스텝(의존성 체인)이 아닙니다"),
            isNull(),
            any());
  }

  // ------------------------------------------------------------------ //
  // 증분 처리({{last_run_at}}) — Task 6
  // ------------------------------------------------------------------ //

  /** 증분 MERGE 스텝 응답. 실제 저장 경로가 허용하는 유일한 조합(MERGE + SELECT + PK 출력)을 흉내낸다. */
  private PipelineStepResponse incrementalMergeStep(
      Long stepId, Long outputDatasetId, String loadStrategy) {
    return new PipelineStepResponse(
        stepId,
        "incremental-step",
        null,
        "SQL",
        "SELECT code, name FROM data.\"source\" WHERE _updated_at >= {{last_run_at}}",
        outputDatasetId,
        null,
        List.of(),
        List.of(),
        0,
        loadStrategy,
        null,
        null,
        null,
        null);
  }

  /**
   * <b>순서 고정(이 Task 의 핵심 불변식)</b> — 출력이 커밋된 <b>뒤에</b>만 책갈피가 전진해야 한다.
   *
   * <p>출력(테넌트 파이프라인 롤 커넥션)과 책갈피(앱 커넥션)는 다른 커넥션이라 한 트랜잭션으로 묶을 수
   * 없다. 그래서 순서가 유일한 안전장치다. 이 테스트는 {@code sqlExecutor.execute} 가 도는 동안
   * {@code advanceCursor} 가 아직 불리지 않았음을 실행 시점에 기록해 확인한다 — 프로덕션에서 두 줄을
   * 맞바꾸면 {@code advancedBeforeOutputCommit} 이 true 가 되어 이 테스트가 깨진다(변이로 확인함).
   */
  @Test
  void 증분_스텝은_출력_커밋_이후에만_책갈피를_전진시킨다() {
    Long pipelineId = 70L;
    Long stepId = 700L;
    Long stepExecId = 800L;
    Long outputDatasetId = 90L;
    Long userId = 1L;
    OffsetDateTime bookmark = OffsetDateTime.parse("2026-09-19T00:00:00.123456Z");
    OffsetDateTime candidate = OffsetDateTime.parse("2026-09-19T01:00:00.654321Z");

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(bookmark, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate()).thenReturn(candidate);

    java.util.concurrent.atomic.AtomicBoolean advanced =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicBoolean advancedBeforeOutputCommit =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    doAnswer(
            inv -> {
              advanced.set(true);
              return null;
            })
        .when(stepRepository)
        .advanceCursor(any(), any(), anyBoolean());
    when(sqlExecutor.execute(anyList(), anyString()))
        .thenAnswer(
            inv -> {
              // 이 시점이 "출력이 커밋되는 순간"이다 — 여기서 이미 책갈피가 전진했다면 순서가 뒤집힌 것.
              advancedBeforeOutputCommit.set(advanced.get());
              return "ok";
            });

    String status = runner.executeStep(stepExecId, step, pipelineId, "P", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    assertThat(advancedBeforeOutputCommit)
        .as("책갈피는 출력이 커밋된 뒤에만 전진해야 한다")
        .isFalse();
    assertThat(advanced).as("성공 실행은 책갈피를 전진시켜야 한다").isTrue();
    verify(stepRepository).advanceCursor(stepId, candidate, false);
  }

  /** 후보값은 SQL 실행 <b>전에</b> 캡처돼야 한다 — 실행 중 커밋된 행을 다음 실행이 놓치지 않기 위해서다. */
  @Test
  void 후보값은_SQL_실행_전에_캡처된다() {
    Long stepId = 701L;
    Long outputDatasetId = 91L;
    OffsetDateTime candidate = OffsetDateTime.parse("2026-09-19T01:00:00Z");

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(null, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate()).thenReturn(candidate);
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    runner.executeStep(801L, step, 71L, "P", 1L, false);

    org.mockito.InOrder inOrder = inOrder(incrementalCursorService, sqlExecutor);
    inOrder.verify(incrementalCursorService).captureCandidate();
    inOrder.verify(sqlExecutor).execute(anyList(), anyString());
    // 정확히 한 번이어야 한다 — 실행 뒤에 다시 잡아 그 값을 쓰면 실행 도중 커밋된 행을 영원히 건너뛴다.
    // (times(1) 이 없으면 "실행 후에도 한 번 더 잡는" 변이를 InOrder 가 놓친다.)
    verify(incrementalCursorService, times(1)).captureCandidate();
  }

  /** 실패한 실행은 책갈피를 전진시키지 않는다 — 전진시키면 읽지 못한 구간이 영원히 사라진다. */
  @Test
  void SQL_실행이_실패하면_책갈피를_전진시키지_않는다() {
    Long stepId = 702L;
    Long stepExecId = 802L;
    Long outputDatasetId = 92L;
    OffsetDateTime bookmark = OffsetDateTime.parse("2026-09-19T00:00:00Z");

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(bookmark, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-09-19T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString()))
        .thenThrow(new ScriptExecutionException("boom"));

    String status = runner.executeStep(stepExecId, step, 72L, "P", 1L, false);

    assertThat(status).isEqualTo("FAILED");
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean());
    // 주입값 기록은 실행 전에 하므로 실패해도 남는다 — "어디서부터 읽으려 했는가"의 진단 근거.
    verify(executionRepository).setInjectedLastRunAt(stepExecId, bookmark);
  }

  /** 책갈피가 없으면(첫 실행) -infinity 로 치환해 전체를 읽고, 주입값은 null 로 기록한다. */
  @Test
  void 책갈피가_없으면_전체를_읽는다() {
    Long stepId = 703L;
    Long stepExecId = 803L;
    Long outputDatasetId = 93L;

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(null, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-09-19T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    runner.executeStep(stepExecId, step, 73L, "P", 1L, false);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> preCaptor = ArgumentCaptor.forClass(List.class);
    verify(sqlExecutor).execute(preCaptor.capture(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("'-infinity'::timestamptz");
    assertThat(preCaptor.getValue()).as("전체 재생성 예약이 없으면 출력을 비우지 않는다").isEmpty();
    verify(executionRepository).setInjectedLastRunAt(stepExecId, null);
  }

  /** 전체 재생성 예약이면 책갈피를 무시하고 전체를 읽으며, 출력을 비우는 선행 문장이 같은 트랜잭션으로 들어간다. */
  @Test
  void 전체_재생성_예약이면_전체를_읽고_출력을_비운다() {
    Long stepId = 704L;
    Long stepExecId = 804L;
    Long outputDatasetId = 94L;
    OffsetDateTime bookmark = OffsetDateTime.parse("2026-09-19T00:00:00Z");

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(bookmark, true, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-09-19T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    runner.executeStep(stepExecId, step, 74L, "P", 1L, false);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> preCaptor = ArgumentCaptor.forClass(List.class);
    verify(sqlExecutor).execute(preCaptor.capture(), sqlCaptor.capture());
    assertThat(sqlCaptor.getValue()).contains("'-infinity'::timestamptz");
    assertThat(preCaptor.getValue())
        .as("전체 재생성은 출력을 비우는 DELETE 를 본 INSERT 와 같은 트랜잭션으로 보낸다")
        .containsExactly(OutputClearStatement.deleteAll("incremental_out"));
    verify(executionRepository).setInjectedLastRunAt(stepExecId, null);
    // 예약을 소비한 실행만 예약을 해제한다.
    verify(stepRepository).advanceCursor(eq(stepId), any(), eq(true));
  }

  /**
   * <b>비SELECT 증분 스텝은 전체 재생성 예약이 걸려도 출력을 비우지 않는다.</b>
   *
   * <p>출력을 비우는 DELETE 뒤에 출력을 다시 채우는 것은 SELECT 자동 적재 경로(INSERT INTO ... SELECT)
   * 뿐이다. 사용자가 직접 쓴 INSERT/UPDATE/DELETE 스텝(APPEND + {{last_run_at}} 은 저장 시점에
   * 거부되지 않는다)에 그 DELETE 를 얹으면 출력이 빈 채로 COMPLETED 가 되고 책갈피까지 전진한다 —
   * 조용한 전량 손실이다. 비SELECT 의 "전체 재생성"은 <b>전체 읽기</b>(-infinity)까지만을 뜻한다.
   */
  @Test
  void 비SELECT_증분_스텝은_전체_재생성_예약에도_출력을_비우지_않는다() {
    Long stepId = 706L;
    Long stepExecId = 806L;
    Long outputDatasetId = 96L;

    PipelineStepResponse step =
        new PipelineStepResponse(
            stepId,
            "incremental-dml-step",
            null,
            "SQL",
            "UPDATE data.\"target\" SET flag = true WHERE _updated_at >= {{last_run_at}}",
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

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(stepRepository.findCursor(stepId))
        .thenReturn(
            Optional.of(
                new StepCursor(
                    OffsetDateTime.parse("2026-09-19T00:00:00Z"), true, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-09-19T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    String status = runner.executeStep(stepExecId, step, 76L, "P", 1L, false);

    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> preCaptor = ArgumentCaptor.forClass(List.class);
    verify(sqlExecutor).execute(preCaptor.capture(), sqlCaptor.capture());
    assertThat(preCaptor.getValue())
        .as("비SELECT 스텝에 출력 비우기 DELETE 를 얹으면 사용자 DML 이 그 자리를 채운다는 보장이 없다")
        .isEmpty();
    assertThat(sqlCaptor.getValue())
        .as("예약이 걸렸으므로 전체 읽기(-infinity)는 그대로 적용된다")
        .contains("'-infinity'::timestamptz");
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  /** 전체 재생성이 아닌 일반 증분 실행은 예약 플래그를 건드리지 않는다(실행 중 켜진 예약을 잃지 않기 위해). */
  @Test
  void 일반_증분_실행은_전체_재생성_예약을_해제하지_않는다() {
    Long stepId = 707L;
    Long outputDatasetId = 97L;

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "MERGE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", true), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(
            Optional.of(
                new StepCursor(
                    OffsetDateTime.parse("2026-09-19T00:00:00Z"), false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-09-19T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    runner.executeStep(807L, step, 77L, "P", 1L, false);

    verify(stepRepository).advanceCursor(eq(stepId), any(), eq(false));
  }

  /** 레거시 REPLACE + 증분 조합은 실행 시점에도 거부한다(저장 시점 검증 우회에 대한 2차 방어). */
  @Test
  void REPLACE와_증분_조합은_실행_시점에도_거부된다() {
    Long stepId = 705L;
    Long stepExecId = 805L;
    Long outputDatasetId = 95L;

    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "REPLACE");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));

    String status = runner.executeStep(stepExecId, step, 75L, "P", 1L, false);

    assertThat(status).isEqualTo("FAILED");
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), contains("MERGE"), isNull(), any());
  }

  // ------------------------------------------------------------------ //
  // Helpers
  // ------------------------------------------------------------------ //

  // ------------------------------------------------------------------ //
  // 자동 임시 데이터셋(ptmp_*) 선비우기 제거 — API_CALL / AI_CLASSIFY
  //
  // 예전에는 두 분기 모두 임시 데이터셋 테이블명을 알아낸 직후 무조건
  // dataTableRowService.truncateTable 을 불렀다. 그 호출은 (1) 로드 전략을 보지 않았고
  // (2) API 호출·AI 분류보다 먼저 커밋돼, 실패하면 출력이 빈 채로 남았다. REPLACE 비우기는
  // 실행기(t_tmp 맞바꿈)가 이미 원자적으로 처리하므로 선비우기는 중복이자 유해했다.
  // 아래 테스트들은 "truncateTable 이 절대 불리지 않는다"를 네 조합에서 고정한다.
  // ------------------------------------------------------------------ //

  /** 테스트용 최소 API_CALL 설정 — 필드 매핑 하나로 임시 데이터셋 스키마를 추론하게 한다. */
  private ApiCallConfig minimalApiConfig() {
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

  /** 출력 데이터셋을 지정하지 않은(=임시 데이터셋 자동 생성) API_CALL 스텝. */
  private PipelineStepResponse apiStepNoOutput(Long stepId, String name, String loadStrategy) {
    return new PipelineStepResponse(
        stepId, name, null, "API_CALL", null, null, null, List.of(), List.of(), 0, loadStrategy,
        Map.of(), null, null, null);
  }

  /** 출력 데이터셋을 지정하지 않은(=임시 데이터셋 자동 생성) AI_CLASSIFY 스텝. */
  private PipelineStepResponse aiStepNoOutput(Long stepId, String name, String loadStrategy) {
    return new PipelineStepResponse(
        stepId, name, null, "AI_CLASSIFY", null, null, null, List.of(), List.of(), 0, loadStrategy,
        null, Map.of(), null, null);
  }

  /** 스키마가 바뀌지 않은 기존 임시 데이터셋을 재사용하도록 스텁한다(=이전 실행 행이 남아 있는 상황). */
  private void stubReusedTempDataset(Long stepId, Long dsId, String tableName) {
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(dsId));
    when(tempDatasetService.hasSchemaChanged(eq(dsId), any())).thenReturn(false);
    when(datasetRepository.findTableNameById(dsId)).thenReturn(Optional.of(tableName));
  }

  @Test
  void 실행기_꺼진_API_CALL이_실패해도_임시_데이터셋을_비우지_않는다() {
    // given: 재사용 임시 데이터셋(이전 행 보유) + apiCallExecutor 가 던지는 실패
    Long pipelineId = 90L, userId = 1L, stepId = 901L, stepExecId = 951L, dsId = 991L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_fail");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    when(apiCallExecutor.execute(any(), eq("ptmp_api_fail"), isNull(), eq("REPLACE"), any(), any()))
        .thenThrow(new ScriptExecutionException("API 호출 실패"));

    // when
    String status =
        runner.executeStep(
            stepExecId, apiStepNoOutput(stepId, "api-fail", "REPLACE"), pipelineId,
            "TestPipeline", userId, false);

    // then: 실패했지만 출력 테이블은 건드리지 않았다 — 이전 행이 그대로 남는다
    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  @Test
  void 실행기_켜진_API_CALL이_실패하면_임시_테이블만_버리고_원본을_비우지_않는다() {
    // given
    Long pipelineId = 91L, userId = 1L, stepId = 902L, stepExecId = 952L, dsId = 992L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_exec_fail");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    when(executorClient.executeApiCall(any()))
        .thenReturn(
            new ExecutorClient.ApiCallExecuteResult(false, 0, 0, null, "upstream 500", 10L));

    // when
    String status =
        runner.executeStep(
            stepExecId, apiStepNoOutput(stepId, "api-exec-fail", "REPLACE"), pipelineId,
            "TestPipeline", userId, true);

    // then: 원본 truncate 없음 + 스테이징만 정리 + 맞바꿈 없음
    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(dataTableService).dropTempTable("ptmp_api_exec_fail");
    verify(dataTableService, never()).finishReplace(anyString(), anyLong());
  }

  @Test
  void AI_CLASSIFY가_실패해도_임시_데이터셋을_비우지_않는다() {
    // given
    Long pipelineId = 92L, userId = 1L, stepId = 903L, stepExecId = 953L, dsId = 993L;
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class)))
        .thenReturn(
            new AiClassifyConfig(
                "Classify",
                List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
                List.of("text"),
                null,
                null));
    stubReusedTempDataset(stepId, dsId, "ptmp_ai_fail");
    when(aiClassifyExecutor.execute(any(), eq(stepExecId), eq(userId)))
        .thenThrow(new ScriptExecutionException("AI 분류 실패"));

    // when
    String status =
        runner.executeStep(
            stepExecId, aiStepNoOutput(stepId, "ai-fail", "REPLACE"), pipelineId,
            "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  @Test
  void APPEND_API_CALL은_재사용_임시_데이터셋을_비우지_않는다() {
    // given: APPEND 인데도 예전에는 매 실행 통째로 비워졌다
    Long pipelineId = 93L, userId = 1L, stepId = 904L, stepExecId = 954L, dsId = 994L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_append");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    when(apiCallExecutor.execute(
            any(), eq("ptmp_api_append"), isNull(), eq("APPEND"), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(3, "log"));

    // when
    String status =
        runner.executeStep(
            stepExecId, apiStepNoOutput(stepId, "api-append", "APPEND"), pipelineId,
            "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  @Test
  void APPEND_AI_CLASSIFY는_재사용_임시_데이터셋을_비우지_않는다() {
    // given
    Long pipelineId = 94L, userId = 1L, stepId = 905L, stepExecId = 955L, dsId = 995L;
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class)))
        .thenReturn(
            new AiClassifyConfig(
                "Classify",
                List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
                List.of("text"),
                null,
                null));
    stubReusedTempDataset(stepId, dsId, "ptmp_ai_append");
    when(aiClassifyExecutor.execute(any(), eq(stepExecId), eq(userId)))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(4L, "ai log"));

    // when
    String status =
        runner.executeStep(
            stepExecId, aiStepNoOutput(stepId, "ai-append", "APPEND"), pipelineId,
            "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());

    // 선비우기를 없앤 뒤로 "APPEND 가 비우지 않는다"는 보장 전체가 AiClassifyExecutor 에 넘어갔고,
    // 그 실행기는 넘겨받은 resolvedStep 의 loadStrategy 하나만 보고 판단한다(AiClassifyExecutor:128).
    // 그런데 resolvedStep 을 만드는 래퍼는 15개 위치 인자이고 String outputDatasetName 이
    // String loadStrategy 바로 옆에 있다 — 둘이 뒤바뀌어도 컴파일은 통과하고, 그러면 null →
    // "REPLACE" 기본값이 걸려 APPEND 스텝이 조용히 파괴적 REPLACE 가 된다. 그 이음매를 못박는다.
    // (API_CALL 쪽은 execute(..., eq("REPLACE"|"APPEND"), ...) 스텁이 이미 같은 역할을 한다.)
    ArgumentCaptor<PipelineStepResponse> stepCaptor =
        ArgumentCaptor.forClass(PipelineStepResponse.class);
    verify(aiClassifyExecutor).execute(stepCaptor.capture(), eq(stepExecId), eq(userId));
    assertThat(stepCaptor.getValue().loadStrategy())
        .as("APPEND 스텝의 로드 전략이 실행기까지 그대로 도달해야 한다")
        .isEqualTo("APPEND");
  }

  /**
   * 성공한 REPLACE 의 최종 결과가 그대로인지 고정하는 가드 테스트. 선비우기 제거 전에도 통과했다
   * (그때는 truncate 가 추가로 불렸을 뿐 맞바꿈 결과는 같았다) — 회귀 방지용이다.
   */
  @Test
  void REPLACE_API_CALL_성공은_여전히_임시테이블_맞바꿈으로_전량_교체한다() {
    // given — 실행기 켠 경로
    Long pipelineId = 95L, userId = 1L, stepId = 906L, stepExecId = 956L, dsId = 996L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_replace");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    when(executorClient.executeApiCall(any()))
        .thenReturn(new ExecutorClient.ApiCallExecuteResult(true, 7, 1, "ok", null, 20L));

    // when
    String status =
        runner.executeStep(
            stepExecId, apiStepNoOutput(stepId, "api-replace", "REPLACE"), pipelineId,
            "TestPipeline", userId, true);

    // then: 스테이징 생성 → 7행 맞바꿈. 원본 truncate 는 없다.
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).createTempTable("ptmp_api_replace");
    verify(dataTableService).finishReplace("ptmp_api_replace", 7L);
    verify(dataTableService, never()).dropTempTable(anyString());
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  /** 실행기 끈 REPLACE 도 로드 전략을 그대로 실행기에 넘겨 위임한다(가드 테스트). */
  @Test
  void REPLACE_API_CALL은_실행기_꺼진_경로에서도_전략을_그대로_위임한다() {
    Long pipelineId = 96L, userId = 1L, stepId = 907L, stepExecId = 957L, dsId = 997L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_replace_off");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    when(apiCallExecutor.execute(
            any(), eq("ptmp_api_replace_off"), isNull(), eq("REPLACE"), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(9, "log"));

    String status =
        runner.executeStep(
            stepExecId, apiStepNoOutput(stepId, "api-replace-off", "REPLACE"), pipelineId,
            "TestPipeline", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(apiCallExecutor)
        .execute(any(), eq("ptmp_api_replace_off"), isNull(), eq("REPLACE"), any(), any());
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  /**
   * 코드리뷰 LOW — 비SQL·비API 스텝의 로드 전략 분기(상단 {@code switch})가 대소문자를 가리면
   * 소문자 레거시 행("append")이 {@code default} 로 떨어져 <b>REPLACE 처럼 출력을 truncate</b> 한다.
   * 같은 메서드의 SQL 분기는 전부 {@code equalsIgnoreCase} 라 같은 값이 경로에 따라 다르게 해석되는
   * 비대칭이었다. 실행기 끈 PYTHON 스텝이 그 switch 로 들어가는 유일한 경로라 그것으로 고정한다.
   */
  @Test
  void 소문자_append_는_비SQL_스텝에서도_truncate하지_않는다() {
    Long pipelineId = 97L, userId = 1L, stepId = 908L, stepExecId = 958L, outputDatasetId = 998L;
    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId, "py-lower-append", null, "PYTHON", "print('x')", outputDatasetId, null,
            List.of(), List.of(), 0, "append", null, null, null, null);

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("output_lower_append"));

    // 실행기 끈 경로(executorEnabled=false) — 스크립트 실행 자체의 성패는 이 테스트의 관심이 아니다.
    runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, false);

    verify(dataTableRowService, never()).truncateTable(anyString());
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
