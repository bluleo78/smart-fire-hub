package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
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
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadAccessException;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * PipelineAsyncRunner 단위 테스트.
 *
 * <p>실제 스텝 실행 로직(SQL 래핑, 임시 데이터셋 생성, 의존성 스킵 등)을 검증한다. @Async AOP 프록시는 단위 테스트에서 적용되지 않으므로
 * executeAsync는 동기적으로 실행되어 동작을 직접 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineAsyncRunnerTest {

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
  // Tibero 테넌트 전용 실행기(WD-77) — 이 클래스는 PG 테넌트만 다루므로 호출되지 않는다(Tibero 경로는
  // PipelineAsyncRunnerTiberoTest).
  @Mock DbExecutorClient dbExecutorClient;

  // 러너는 조회/DML 판정을 검증기 반환값(파싱한 AST 의 문장 종류)으로 받는다(WD-93) — 목이면 판정 규칙을 테스트에 다시 적게 되므로 실제
  // 검증기를 쓴다. 테스트 SQL 이 미한정 테이블을 쓸 수 있게 허용형(data 스키마 + 미한정 허용) 정책이다.
  // 러너는 검증기·{{#N}} 한정 이름을 PipelineDataSchema 에서 얻는다(WD-77) — 저장소 판정기 목의 기본값(null)은 TIBERO 가 아니므로
  // 실제 PipelineDataSchema 가 PG 경로(위 검증기·DataSchema.qualify)를 그대로 고른다.
  @Spy
  PipelineDataSchema pipelineDataSchema =
      new PipelineDataSchema(mock(DataStoreResolver.class), new SqlValidator("data", true));

  @Mock PythonScriptValidator pythonScriptValidator;
  @Mock IncrementalCursorService incrementalCursorService;
  @Mock OutputTableSessionLock outputTableSessionLock;
  @Mock IncrementalStepLock incrementalStepLock;
  // 보안 등급 판정은 PipelineSqlAccessTest(통합)가 검증한다 — 여기서는 통과(목 기본값)로 두고 실행 로직만 본다.
  @Mock PipelineSecurityGate pipelineSecurityGate;
  // S3 AI_CLASSIFY 입력 AI 판정 — 판정 자체는 AiClassifyInputAccessTest(통합)가 검증한다. 여기서는 통과(목 기본값)로 둔다.
  @Mock DatasetAccessGuard datasetAccessGuard;
  @Mock AiHostingResolver aiHostingResolver;
  @Mock PythonReadGrants pythonReadGrantSync;
  @Mock LocalPythonOutputLoader localPythonOutputLoader;

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
   * 스텝 SQL 조립이 {@code DataSchema.current()} 를 거치면서 테넌트 컨텍스트를 요구하게 됐다. 운영에서는 {@code
   * pipelineExecutor} 에 붙은 {@code TenantContextTaskDecorator} 가 호출 스레드의 컨텍스트를 승계하므로(AsyncConfig) 이
   * 클래스가 세우는 것은 그 승계 결과를 흉내 내는 것이다.
   *
   * <p>DB 를 쓰지 않는 순수 목(mock) 테스트이므로 GUC·트랜잭션과는 무관하다 — 즉 "프로덕션 경로가 스스로 컨텍스트를 세우지 못한다"는 배선 결함을 가리지
   * 않는다(승계 주체는 executor 데코레이터).
   */
  @BeforeEach
  void setTenantContext() {
    TenantContext.set(1L);
    // AI_CLASSIFY 분기는 실행 주체 자격(runAs.clearance())을 AI 판정에 넘기고, PYTHON 분기는 같은 자격으로
    // 읽기 슬롯을 준비한다(WD-29). 목 기본값(null RunAs)이면 역참조에서 끊기므로 빈 자격의 RunAs 를 기본으로
    // 둔다 — 특정 RunAs 가 필요한 테스트는 자기 스텁으로 덮어쓴다. 쓰지 않는 테스트가 있어 lenient.
    // 슬롯 준비는 기본 성공(슬롯 2).
    // 사용자 SQL 판정 기본값(WD-426) — 아무 데이터셋도 참조하지 않는 허용 결과. 판정 결과를 쓰는 테스트는 자기 스텁으로 덮어쓴다.
    RunnerSqlAccessStubs.allowSqlWithoutDatasetsByDefault(pipelineSecurityGate);
    lenient()
        .when(pipelineSecurityGate.runAs(any()))
        .thenAnswer(
            inv -> {
              Long u = inv.getArgument(0);
              return new PipelineSecurityGate.RunAs(u, Clearance.none(u == null ? 0L : u, 1L));
            });
    lenient().when(pythonReadGrantSync.prepareForRun(any(), anyInt())).thenReturn(2);
    // 상태 쓰기는 기본적으로 이긴다(WD-230: 반환값 true 일 때만 이벤트 발행) — 지는 경우를 보는 테스트가 자기 스텁으로 덮어쓴다.
    lenient()
        .when(executionRepository.updateExecutionStatus(anyLong(), anyString(), any(), any()))
        .thenReturn(true);
    lenient()
        .when(
            executionRepository.updateExecutionStatus(anyLong(), anyString(), any(), any(), any()))
        .thenReturn(true);
    lenient()
        .when(executionRepository.cancelAndSkipPendingSteps(anyLong(), any()))
        .thenReturn(true);
    // 임시 테이블 생성은 이번 실행 전용 이름을 돌려준다(WD-151) — 러너가 그 이름을 적재 대상·마무리·정리에 그대로 넘기는지 단언하려고
    // 출력별로 정해진 가짜 이름(runTemp)을 쓴다. 쓰지 않는 테스트가 있어 lenient.
    lenient()
        .when(dataTableService.createTempTable(anyString()))
        .thenAnswer(inv -> runTemp(inv.getArgument(0)));
  }

  /**
   * 테스트에서 {@code createTempTable(table)} 목이 돌려주는 이번 실행 임시 테이블 이름 — 실제 모양({@code rtmp_<12
   * hex>_<md5>})과 같되 난수 부분을 고정해 단언할 수 있게 한다.
   */
  private static String runTemp(String table) {
    return DataTableService.TEMP_TABLE_PREFIX
        + "0123456789ab_"
        + com.smartfirehub.dataset.storage.DataTableNames.md5Hex(table);
  }

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void executeAsync_unregistersFromRegistry_whenRunFinishes() {
    // 스텝이 없어도 끝까지 돌고 나면 등록부에서 빠져야 한다(WD-230) — 안 빠지면 하트비트가 끝난 실행을 계속 갱신한다.
    when(pipelineRepository.findCreatedByIdById(1L)).thenReturn(Optional.of(5L));

    runner.executeAsync(1L, 77L, List.of(), Map.of(), Map.of(), 5L, false);

    verify(executionRegistry).unregister(77L);
  }

  @Test
  void executeAsync_unregistersFromRegistry_evenWhenStartupThrows() {
    // 본문 앞쪽에서 예외가 터져도(저장소 장애 등) 해제된다 — 바깥 try/finally 가 막는 누수.
    when(pipelineRepository.findCreatedByIdById(1L))
        .thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> runner.executeAsync(1L, 78L, List.of(), Map.of(), Map.of(), 5L, false))
        .isInstanceOf(IllegalStateException.class);

    verify(executionRegistry).unregister(78L);
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
    // "BAD SQL" 은 실행 직전 재검증(SqlValidator)에서 파싱 실패로 스텝이 실패한다 — 실행기까지 가지 않는다(WD-93: 검증기가 판정도 한다).

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
            eq(PipelineSqlErrorMessages.SKIPPED_MESSAGE),
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
   * 출력 데이터셋이 <b>업무 키(PK)</b> 컬럼을 선언했고 SELECT 가 그 컬럼을 함께 낼 때, 그 컬럼이 INSERT 대상 목록에 살아 있어야 한다(#684).
   *
   * <p>이 경로는 대상 컬럼 목록만 좁히고 SELECT 식 목록은 그대로 둔다. 그래서 대상에서 한 컬럼이라도 빠지면 식 개수가 어긋나 PostgreSQL 이 {@code
   * INSERT has more expressions than target columns} 로 거부한다 — 2026-09-17 운영 장애의 실제 실패 문구다.
   *
   * <p><b>왜 PK 컬럼을 빼면 안 되는가</b>: 물리 PK 는 {@code DataTableService} 가 {@code id BIGSERIAL PRIMARY
   * KEY} 로 따로 만든다. {@code id}/{@code import_id}/{@code created_at} 은 시스템 예약어라 {@code
   * dataset_column} 에 들어올 수 없다. 즉 {@code is_primary_key} 가 붙은 컬럼은 시스템이 채우는 대리키가 아니라 <b>사용자가 값을 넣어야
   * 하는 업무 키</b>이고(기본값 없음), {@code DatasetService} 가 거기에 NOT NULL 까지 강제한다. 빼면 넣을 방법이 사라진다.
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
  // 조회/DML 판정(SqlValidator#validate 의 문장 종류) — CTE DML 오분류 방지 테스트 (#159)
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

  /**
   * WD-95: 스키마가 바뀐 TEMP 는 "옛 것 삭제 → 새로 생성" 이다. 새 컬럼이 생성 검증(예: 63자 초과)에 걸리면 삭제가 먼저 일어나 이전 출력이 사라지던
   * 순서를, 검증을 삭제 전으로 당겨 막는다 — 검증이 실패하면 deleteTempDataset·createTempDataset 이 호출되지 않아야 한다. (검증 규칙 자체는
   * TempDatasetServiceTest 가 실제 DataTableService 로 고정한다.)
   */
  @Test
  void executeStep_schemaChangedTemp_invalidNewColumns_keepsOldTempWithoutDeleting() {
    Long pipelineId = 13L;
    Long userId = 1L;
    Long stepId = 203L;
    Long oldTempId = 500L;
    PipelineStepResponse sqlStep =
        stepResponse(
            stepId, "long-col", "SQL", "SELECT name FROM data.\"source\"", null, List.of());
    stubProbeColumns("name");

    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTempId));
    when(tempDatasetService.hasSchemaChanged(eq(oldTempId), any())).thenReturn(true);
    doThrow(new InvalidTableNameException("필드명은 최대 63자까지 쓸 수 있습니다(입력 64자)"))
        .when(tempDatasetService)
        .validateColumnsForNewTemp(any());

    String status = runner.executeStep(303L, sqlStep, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never())
        .createTempDataset(any(), any(), anyString(), any(), anyString(), any());
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
            columnsCaptor.capture(),
            eq(pipelineId),
            anyString(),
            eq(stepId),
            anyString(),
            eq(userId)))
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
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
  }

  @Test
  void executeStep_selectWithoutOutputDataset_recreatesTempDatasetWhenSchemaChanged() {
    // given: 기존 임시 데이터셋이 있지만 스키마가 변경된 경우
    Long pipelineId = 16L;
    Long userId = 1L;
    Long stepId = 206L;
    Long stepExecId = 306L;
    Long oldTempDatasetId = 777L;

    String selectSql = "SELECT id, name, extra FROM data.\"source\"";
    PipelineStepResponse sqlStep =
        stepResponse(stepId, "schema-change-step", "SQL", selectSql, null, List.of());

    stubProbeColumns("id", "name", "extra");

    when(tempDatasetService.findExistingTempDataset(stepId))
        .thenReturn(Optional.of(oldTempDatasetId));
    when(tempDatasetService.hasSchemaChanged(eq(oldTempDatasetId), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(oldTempDatasetId))
        .thenReturn(Optional.of("ptmp_16_schema_change_step_5678"));
    // SELECT 컬럼 id는 시스템 예약어이므로 임시 데이터셋에는 id_1로 별칭 처리되어 저장된다(#645)
    when(columnRepository.findByDatasetId(oldTempDatasetId))
        .thenReturn(List.of(col("id_1", false), col("name", false), col("extra", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("3 rows affected");

    // when
    runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then: 같은 데이터셋 id 로 테이블·컬럼만 다시 만든다(WD-222) — 삭제 후 생성은 고정 출력 스텝에서 출력 참조 거부로 실패하고 id 가 바뀐다.
    verify(tempDatasetService).rebuildTempDataset(eq(oldTempDatasetId), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
    verify(tempDatasetService, never())
        .createTempDataset(any(), any(), anyString(), any(), anyString(), any());
  }

  /** 실행 주체가 볼 수 없는 출력에 대한 게이트 거부(실제 게이트가 던지는 것과 같은 코드). */
  private static com.smartfirehub.global.exception.CodedApiException outputDenied() {
    return new com.smartfirehub.global.exception.CodedApiException(
        org.springframework.http.HttpStatus.FORBIDDEN, "DATASET_SQL_ACCESS_DENIED", "denied");
  }

  /**
   * 코드리뷰 CR3 — 재사용 TEMP 의 스키마가 바뀌어 다시 만들 때, 실행 주체(B)가 그 TEMP 를 볼 수 없으면 <b>지우기 전에</b> 실패해야 한다. 예전에는
   * 삭제가 먼저였고 등급 판정은 새로 만든 TEMP 만 봤으므로 B 가 이전 실행 주체(A)의 결과 TEMP 를 통째로 지웠다.
   */
  @Test
  void executeStep_sqlSchemaChangedReusedTempHiddenFromRunAs_failsBeforeDeleting() {
    Long pipelineId = 17L, userId = 2L, stepId = 207L, stepExecId = 307L, oldTemp = 779L;
    PipelineStepResponse sqlStep =
        stepResponse(
            stepId, "hidden-temp", "SQL", "SELECT a FROM data.\"source\"", null, List.of());
    stubProbeColumns("a");
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTemp));
    when(tempDatasetService.hasSchemaChanged(eq(oldTemp), any())).thenReturn(true);
    // 실행 주체 자격은 스텝당 한 번 계산돼(RunAs) 판정에 넘어간다 — 이 실행 주체로 판정할 때만 거부한다.
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(oldTemp, runAs);

    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(sqlExecutor, never()).execute(anyList(), anyString());
  }

  /** CR3 — AI_CLASSIFY 도 같다: 볼 수 없는 재사용 TEMP 는 스키마가 바뀌어도 지우지 않고 실패한다. */
  @Test
  void executeStep_aiClassifySchemaChangedReusedTempHiddenFromRunAs_failsBeforeDeleting() {
    Long pipelineId = 18L, userId = 2L, stepId = 208L, stepExecId = 308L, oldTemp = 780L;
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class)))
        .thenReturn(
            new AiClassifyConfig(
                "Classify",
                List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
                List.of("text"),
                null,
                null));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTemp));
    when(tempDatasetService.hasSchemaChanged(eq(oldTemp), any())).thenReturn(true);
    // 실행 주체 자격은 스텝당 한 번 계산돼(RunAs) 판정에 넘어간다 — 이 실행 주체로 판정할 때만 거부한다.
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(oldTemp, runAs);

    String status =
        runner.executeStep(
            stepExecId,
            aiStepNoOutput(stepId, "ai-hidden-temp", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

    assertThat(status).isEqualTo("FAILED");
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(aiClassifyExecutor, never()).execute(any(), any(), any(), any());
  }

  /**
   * 코드리뷰 CR2 — PYTHON(실행기 끈 REPLACE)은 스크립트 실행 전에 지정 출력을 truncate 한다. 실행 주체가 그 출력을 볼 수 없으면 비우기·실행 전에
   * 실패해야 한다. 판정은 enforcePythonOutputLevel 하나가 한다(코드리뷰 8 — 예전 중복 판정 requireOutputVisible 제거).
   */
  @Test
  void executeStep_pythonHiddenExplicitOutput_failsBeforeTruncateOrRun() {
    Long pipelineId = 19L, userId = 2L, stepId = 209L, stepExecId = 309L, out = 781L;
    PipelineStepResponse py =
        stepResponse(stepId, "py-hidden-out", "PYTHON", "print('x')", out, List.of());
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(out)).thenReturn(Optional.of("hidden_out"));
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied())
        .when(pipelineSecurityGate)
        .enforcePythonOutputLevel(out, stepId, false, runAs, 0);

    String status = runner.executeStep(stepExecId, py, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(outputTableSessionLock, never()).callLocked(anyString(), any());
    verify(pythonExecutor, never()).run(anyString(), anyInt());
  }

  /** CR2 — API_CALL(실행기 켠 REPLACE)도 맞바꿈용 임시 테이블 생성·호출 전에 실패한다. */
  @Test
  void executeStep_apiCallHiddenExplicitOutput_failsBeforeReplaceOrCall() {
    Long pipelineId = 20L, userId = 2L, stepId = 210L, stepExecId = 310L, out = 782L;
    PipelineStepResponse api =
        new PipelineStepResponse(
            stepId,
            "api-hidden-out",
            null,
            "API_CALL",
            null,
            out,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            Map.of(),
            null,
            null,
            null);
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    when(datasetRepository.findTableNameById(out)).thenReturn(Optional.of("hidden_api_out"));
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(out, runAs);

    String status = runner.executeStep(stepExecId, api, pipelineId, "TestPipeline", userId, true);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService, never()).createTempTable(anyString());
    verify(executorClient, never()).executeApiCall(any());
    verify(apiCallExecutor, never()).execute(any(), any(), any(), any(), any(), any(), any());
  }

  /**
   * WD-180 — API_CALL 스텝이 참조한 연결이 실행 시점에 없으면(또는 다른 워크스페이스라 보이지 않으면) 실행 기록에 한국어 안내가 남는다. 예전에는
   * 「ApiConnection not found: 3」 영어 원문이 그대로 남았다. 호출·임시 테이블 생성 전에 실패한다.
   */
  @Test
  void executeStep_apiCallConnectionNotFound_recordsKoreanMessage() {
    Long pipelineId = 21L, userId = 2L, stepId = 211L, stepExecId = 311L, connId = 3L;
    PipelineStepResponse api =
        new PipelineStepResponse(
            stepId,
            "api-missing-conn",
            null,
            "API_CALL",
            null,
            null,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            Map.of(),
            null,
            null,
            connId);
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    when(apiConnectionService.getById(connId))
        .thenThrow(new ApiConnectionNotFoundException(connId));

    String status = runner.executeStep(stepExecId, api, pipelineId, "TestPipeline", userId, true);

    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    assertThat(error.getValue())
        .isEqualTo("스텝이 참조한 API 연결을 찾을 수 없습니다. 파이프라인 편집에서 연결을 다시 선택하세요.")
        .doesNotContain("not found");
    verify(executorClient, never()).executeApiCall(any());
    verify(apiCallExecutor, never()).execute(any(), any(), any(), any(), any(), any(), any());
    verify(dataTableService, never()).createTempTable(anyString());
  }

  /**
   * 후속 F1 — PYTHON 도 CR3 와 같다: 스키마가 바뀐 재사용 TEMP 를 실행 주체가 볼 수 없으면 지우기·다시 만들기·실행 전에 실패한다. 예전에는 PYTHON
   * 블록이 판정 없는 자체 삭제·재생성 복사본을 갖고 있었다.
   */
  @Test
  void executeStep_pythonSchemaChangedReusedTempHiddenFromRunAs_failsBeforeDeleting() {
    Long pipelineId = 21L, userId = 2L, stepId = 211L, stepExecId = 311L, oldTemp = 783L;
    PipelineStepResponse py =
        new PipelineStepResponse(
            stepId,
            "py-hidden-temp",
            null,
            "PYTHON",
            "print('x')",
            null,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            Map.of("outputColumns", List.of(Map.of("name", "v", "type", "TEXT"))),
            null);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(com.smartfirehub.pipeline.dto.PythonStepConfig.class)))
        .thenReturn(
            new com.smartfirehub.pipeline.dto.PythonStepConfig(
                List.of(
                    new com.smartfirehub.pipeline.dto.PythonStepConfig.OutputColumn("v", "TEXT"))));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTemp));
    when(tempDatasetService.hasSchemaChanged(eq(oldTemp), any())).thenReturn(true);
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(oldTemp, runAs);

    String status = runner.executeStep(stepExecId, py, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(pythonExecutor, never()).run(anyString(), anyInt());
  }

  /** 후속 F1 — API_CALL 도 같다: 스키마가 바뀐 볼 수 없는 재사용 TEMP 는 지우지 않고, 호출 전에 실패한다. */
  @Test
  void executeStep_apiCallSchemaChangedReusedTempHiddenFromRunAs_failsBeforeDeleting() {
    Long pipelineId = 22L, userId = 2L, stepId = 212L, stepExecId = 312L, oldTemp = 784L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTemp));
    when(tempDatasetService.hasSchemaChanged(eq(oldTemp), any())).thenReturn(true);
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(oldTemp, runAs);

    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-hidden-temp", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

    assertThat(status).isEqualTo("FAILED");
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // WD-222: 재생성은 같은 id 로 테이블만 다시 만든다 — 그 경로도 타지 않아야 한다.
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(apiCallExecutor, never()).execute(any(), any(), any(), any(), any(), any(), any());
  }

  // ── WD-222: 출력 id 가 이 스텝 소유 TEMP 로 들어와도(조회 coalesce·화면 저장 고정) 자동 생성처럼 열 변경 시 재생성 ─────────────

  /** PYTHON — 출력 id 가 소유 TEMP 이고 outputColumns 가 바뀌었으면 같은 id 로 다시 만든다(예전에는 TEMP 준비 분기를 타지 않았다). */
  @Test
  void executeStep_pythonOwnedTempOutput_schemaChanged_rebuildsSameTemp() {
    Long pipelineId = 23L, userId = 2L, stepId = 213L, stepExecId = 313L, ownTemp = 785L;
    PipelineStepResponse py =
        new PipelineStepResponse(
            stepId,
            "py-owned-temp",
            null,
            "PYTHON",
            "print('x')",
            ownTemp,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            null,
            null,
            Map.of("outputColumns", List.of(Map.of("name", "v", "type", "TEXT"))),
            null);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(com.smartfirehub.pipeline.dto.PythonStepConfig.class)))
        .thenReturn(
            new com.smartfirehub.pipeline.dto.PythonStepConfig(
                List.of(
                    new com.smartfirehub.pipeline.dto.PythonStepConfig.OutputColumn("v", "TEXT"))));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(tempDatasetService.hasSchemaChanged(eq(ownTemp), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_23_py"));
    DatasetService.PipelineTempRebuild rebuild = rebuildHandle(ownTemp, "ptmp_23_py");
    when(tempDatasetService.rebuildTempDataset(eq(ownTemp), any())).thenReturn(rebuild);

    String status = runner.executeStep(stepExecId, py, pipelineId, "TestPipeline", userId, false);

    verify(tempDatasetService).rebuildTempDataset(eq(ownTemp), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
    // 크래시 흔적 맞춤은 ensureStepTemp 안에서 한 번만(WD-256) — outputColumns 없음 분기의 맞춤과 겹치지 않는다.
    verify(tempDatasetService, times(1)).reconcileTempDataset(ownTemp);
    assertRebuildSettled(status, rebuild, userId);
  }

  /**
   * PYTHON — outputColumns 가 없어 들어온 소유 TEMP 를 그대로 쓰는 경로도 ensureStepTemp 를 거치지 않으므로 쓰기 전에 크래시 흔적을
   * 맞춘다(WD-256).
   */
  @Test
  void executeStep_pythonOwnedTempWithoutOutputColumns_reconcilesBeforeWrite() {
    Long pipelineId = 40L, userId = 1L, stepId = 241L, stepExecId = 341L, ownTemp = 81L;
    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "py-own-temp",
            null,
            "PYTHON",
            "print('hello')",
            ownTemp,
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
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_40_py"));
    when(columnRepository.findByDatasetId(ownTemp)).thenReturn(List.of(col("col1", false)));
    when(executorClient.executePython(any(), anyInt()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "hello\n", 0, null, 100L, 0));

    String status =
        runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    assertThat(status).isEqualTo("COMPLETED");
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(tempDatasetService, executorClient);
    order.verify(tempDatasetService).reconcileTempDataset(ownTemp);
    order.verify(executorClient).executePython(any(), anyInt());
  }

  /** API_CALL — 출력 id 가 소유 TEMP 이고 추론 열이 바뀌었으면 같은 id 로 다시 만든다. */
  @Test
  void executeStep_apiCallOwnedTempOutput_schemaChanged_rebuildsSameTemp() {
    Long pipelineId = 24L, userId = 2L, stepId = 214L, stepExecId = 314L, ownTemp = 786L;
    PipelineStepResponse api =
        new PipelineStepResponse(
            stepId,
            "api-owned-temp",
            null,
            "API_CALL",
            null,
            ownTemp,
            null,
            List.of(),
            List.of(),
            0,
            "REPLACE",
            Map.of(),
            null,
            null,
            null);
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(tempDatasetService.hasSchemaChanged(eq(ownTemp), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_24_api"));
    DatasetService.PipelineTempRebuild rebuild = rebuildHandle(ownTemp, "ptmp_24_api");
    when(tempDatasetService.rebuildTempDataset(eq(ownTemp), any())).thenReturn(rebuild);

    String status = runner.executeStep(stepExecId, api, pipelineId, "TestPipeline", userId, false);

    verify(tempDatasetService).rebuildTempDataset(eq(ownTemp), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
    assertRebuildSettled(status, rebuild, userId);
  }

  /** AI_CLASSIFY — 출력 id 가 소유 TEMP 이고 출력 열이 바뀌었으면 같은 id 로 다시 만든다. */
  @Test
  void executeStep_aiClassifyOwnedTempOutput_schemaChanged_rebuildsSameTemp() {
    Long pipelineId = 25L, userId = 2L, stepId = 215L, stepExecId = 315L, ownTemp = 787L;
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class)))
        .thenReturn(
            new AiClassifyConfig(
                "Classify",
                List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
                List.of("text"),
                null,
                null));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(tempDatasetService.hasSchemaChanged(eq(ownTemp), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_25_ai"));
    DatasetService.PipelineTempRebuild rebuild = rebuildHandle(ownTemp, "ptmp_25_ai");
    when(tempDatasetService.rebuildTempDataset(eq(ownTemp), any())).thenReturn(rebuild);
    PipelineStepResponse noOutput = aiStepNoOutput(stepId, "ai-owned-temp", "REPLACE");
    PipelineStepResponse ai =
        new PipelineStepResponse(
            noOutput.id(),
            noOutput.name(),
            null,
            "AI_CLASSIFY",
            null,
            ownTemp,
            null,
            noOutput.inputDatasetIds(),
            List.of(),
            0,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);

    String status = runner.executeStep(stepExecId, ai, pipelineId, "TestPipeline", userId, false);

    verify(tempDatasetService).rebuildTempDataset(eq(ownTemp), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
    assertRebuildSettled(status, rebuild, userId);
  }

  /**
   * 증분 자동 생성 스텝 — 책갈피 이후 변경분만 읽는 실행에서 결과 열이 바뀌면 TEMP 를 다시 만들지 않고(빈 TEMP 에 변경분만 남는 조용한 누락) 전체 재생성 예약
   * 안내로 실패한다. 아무것도 지우지 않고 SQL 도 돌리지 않으며 책갈피도 전진하지 않는다.
   */
  @Test
  void executeStep_incrementalOwnedTemp_schemaChangedWithCursor_failsWithoutRebuild() {
    Long pipelineId = 26L, userId = 1L, stepId = 216L, stepExecId = 316L, ownTemp = 788L;
    PipelineStepResponse step = incrementalMergeStep(stepId, ownTemp, "APPEND");
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(
            Optional.of(
                new StepCursor(OffsetDateTime.parse("2026-10-01T00:00:00Z"), false, ownTemp)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-10-02T00:00:00Z"));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(tempDatasetService.hasSchemaChanged(eq(ownTemp), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_26_inc"));

    String status = runner.executeStep(stepExecId, step, pipelineId, "P", userId, false);

    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    assertThat(error.getValue()).contains("전체 재생성");
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
    verify(sqlExecutor, never()).execute(anyList(), anyString());
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
  }

  /** 증분 자동 생성 스텝이라도 전체 재생성 예약이 걸린 실행은 열이 바뀐 TEMP 를 다시 만든다(-infinity 로 전부 다시 채운다). */
  @Test
  void executeStep_incrementalOwnedTemp_schemaChangedWithFullRebuild_rebuilds() {
    Long pipelineId = 27L, userId = 1L, stepId = 217L, stepExecId = 317L, ownTemp = 789L;
    PipelineStepResponse step = incrementalMergeStep(stepId, ownTemp, "APPEND");
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(
            Optional.of(
                new StepCursor(OffsetDateTime.parse("2026-10-01T00:00:00Z"), true, ownTemp)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-10-02T00:00:00Z"));
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(ownTemp));
    when(tempDatasetService.hasSchemaChanged(eq(ownTemp), any())).thenReturn(true);
    when(datasetRepository.findTableNameById(ownTemp)).thenReturn(Optional.of("ptmp_27_inc"));
    DatasetService.PipelineTempRebuild rebuild = rebuildHandle(ownTemp, "ptmp_27_inc");
    when(tempDatasetService.rebuildTempDataset(eq(ownTemp), any())).thenReturn(rebuild);
    when(columnRepository.findByDatasetId(ownTemp))
        .thenReturn(List.of(col("code", false), col("name", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    String status = runner.executeStep(stepExecId, step, pipelineId, "P", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService).rebuildTempDataset(eq(ownTemp), any());
    // WD-238: 성공했으니 보관본을 버리고 되돌리지 않는다.
    assertRebuildSettled(status, rebuild, userId);
  }

  /**
   * 후속 F1 — 스키마가 같은 재사용 TEMP 도 실행 주체가 볼 수 없으면 쓰기(호출·맞바꿈) 전에 실패한다. API_CALL·PYTHON 은 SQL 처럼 입력 등급 전파
   * 뒤 판정하는 단계가 없어 재사용 시점에 바로 판정한다.
   */
  @Test
  void executeStep_apiCallReusedTempHiddenFromRunAs_failsBeforeCall() {
    Long pipelineId = 23L, userId = 2L, stepId = 213L, stepExecId = 313L, oldTemp = 785L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(oldTemp));
    when(tempDatasetService.hasSchemaChanged(eq(oldTemp), any())).thenReturn(false);
    // 판정이 빠지면 실행이 맞바꿈(createTempTable)까지 가도록 테이블명을 준다 — 없으면 판정 유무와 무관하게 orElseThrow 로 FAILED 가 되어
    // 이 TC 가 공허해진다. 판정이 있으면 쓰이지 않으므로 lenient.
    lenient().when(datasetRepository.findTableNameById(oldTemp)).thenReturn(Optional.of("ptmp_x"));
    PipelineSecurityGate.RunAs runAs = new PipelineSecurityGate.RunAs(userId, null);
    when(pipelineSecurityGate.runAs(userId)).thenReturn(runAs);
    doThrow(outputDenied()).when(pipelineSecurityGate).requireOutputVisible(oldTemp, runAs);

    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-hidden-reuse", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            true);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService, never()).createTempTable(anyString());
    verify(executorClient, never()).executeApiCall(any());
    verify(apiCallExecutor, never()).execute(any(), any(), any(), any(), any(), any(), any());
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
   * REPLACE 전략의 SELECT 자동 적재는 더 이상 즉시 truncate 하지 않는다. 대신 DELETE 문을 INSERT 와 같은 executor
   * 요청(preStatements)에 실어, 실행기가 같은 트랜잭션에서 순서대로 실행하게 한다 — 이래야 INSERT 가 실패해도 DELETE 까지 같이 롤백되어 출력이 빈
   * 채로 남지 않는다(원래 결함).
   *
   * <p>fixture 는 :213 {@code executeStep_selectWithOutputDataset_wrapsAsInsertIntoSelect} 의
   * given(출력 데이터셋이 이미 지정된 SELECT 스텝)을 그대로 복제하되, loadStrategy=REPLACE · executorEnabled=true 로 바꿔
   * 원자성 계약을 검증한다.
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
                true, List.of(), List.of(), 0, "2 rows affected", null, null, null));

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
    // (^DELETE FROM "(data|data_t\d+)"\."[a-z0-9_]+"( WHERE false)?$ — 접미사는 WD-426 잠금 전용) 이 요구하는
    // 형태(스키마·테이블 양쪽 인용)와
    // 정확히 같은지, OutputClearStatement 를 거치지 않고 독립적으로 확인한다.
    assertThat(preStatementsCaptor.getValue())
        .containsExactly("DELETE FROM \"data\".\"" + outputTable + "\"");
  }

  /** APPEND 전략은 출력을 비우지 않으므로 선행 문장도 없어야 한다 — REPLACE 전용 경로가 APPEND 까지 잘못 건드리지 않는지 확인하는 회귀 가드. */
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
                true, List.of(), List.of(), 0, "2 rows affected", null, null, null));

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

  // ------------------------------------------------------------------ //
  // WD-426 — REPLACE SQL 이 자기 출력을 읽거나 고침
  // ------------------------------------------------------------------ //

  /** 보안 판정이 본 사용자 SQL 의 읽기·쓰기 데이터셋 — 자기 출력 판정(OutputSelfReference)의 입력. */
  private void stubSqlAccess(Set<Long> reads, Set<Long> writes) {
    when(pipelineSecurityGate.checkStepSqlForRun(any(), any()))
        .thenReturn(new SqlAccessResult(true, null, null, null, reads, writes, true));
  }

  /**
   * PG SELECT + REPLACE 가 출력 자신을 읽으면 비우기를 선행 문장이 아니라 본 문장 안에서 한다 — 선행 문장은 같은 출력 잠금만 잡는 잠금 전용
   * 문장(#731 키 유지), 본 문장은 비우기 전 출력을 읽어 다시 채우는 한 문장이다(실행기 켠·끈 경로 모두).
   */
  @Test
  void REPLACE_SELECT_가_자기_출력을_읽으면_잠금_전용_선행문과_다시_채우기_한_문장을_보낸다() {
    Long outputDatasetId = 426L;
    String outputTable = "out426";
    String selectSql = "SELECT id, name || '+' AS name FROM data.\"out426\"";
    PipelineStepResponse step = replaceStep(4260L, "SQL", selectSql, outputDatasetId);
    stubSqlAccess(Set.of(outputDatasetId), Set.of());
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of(outputTable));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 2, "2 row(s) affected", null, null, null));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("SQL executed successfully");

    assertThat(runner.executeStep(4261L, step, 42L, "TestPipeline", 1L, true))
        .isEqualTo("COMPLETED");
    assertThat(runner.executeStep(4262L, step, 42L, "TestPipeline", 1L, false))
        .isEqualTo("COMPLETED");

    // 잠금 전용 선행문 — 리터럴로 못박는다(firehub-executor 의 선행문 정규식이 받는 정확한 형태, 잠금 키는 전체 비우기와 같다).
    List<String> lockOnly = List.of("DELETE FROM \"data\".\"out426\" WHERE false");
    String reload =
        "WITH \"_fh_self_src\" AS (\n"
            + selectSql
            + "\n), \"_fh_self_clear\" AS (DELETE FROM data.\"out426\" RETURNING 1)"
            + " INSERT INTO data.\"out426\" (\"id\", \"name\") SELECT * FROM \"_fh_self_src\""
            + " WHERE (SELECT count(*) FROM \"_fh_self_clear\") >= 0";
    verify(executorClient).executeSql(eq(reload), eq(lockOnly));
    verify(sqlExecutor).execute(eq(lockOnly), eq(reload));
  }

  /**
   * 출력을 읽지 않는 REPLACE SELECT 는 지금 경로 그대로다(전체 비우기 선행문 + INSERT … SELECT) — 선언 입력에 출력이 있어도 SQL 이 읽지
   * 않으면 같다.
   */
  @Test
  void REPLACE_SELECT_가_자기_출력을_읽지_않으면_예전처럼_비우기_선행문을_보낸다() {
    Long outputDatasetId = 427L;
    String selectSql = "SELECT id, name FROM data.\"source\"";
    PipelineStepResponse step = replaceStep(4270L, "SQL", selectSql, outputDatasetId);
    stubSqlAccess(Set.of(999L), Set.of());
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out427"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("id", false), col("name", false)));
    stubProbeColumns("id", "name");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 2, "2 row(s) affected", null, null, null));

    assertThat(runner.executeStep(4271L, step, 42L, "TestPipeline", 1L, true))
        .isEqualTo("COMPLETED");
    verify(executorClient)
        .executeSql(
            eq("INSERT INTO data.\"out427\" (\"id\", \"name\") " + selectSql),
            eq(List.of("DELETE FROM \"data\".\"out427\"")));
  }

  /**
   * PG 사용자 DML + REPLACE 가 출력을 읽거나(INSERT … SELECT FROM 출력) UPDATE 로 고치면 실행 전에 실패한다 — 사용자 문장은 고쳐 쓸
   * 수 없다. 실행기·로컬 실행기·probe 어느 것도 부르지 않아 출력이 그대로 남는다. 출력을 INSERT 대상으로만 쓰면(쓰기 집합) 통과한다.
   */
  @Test
  void REPLACE_사용자_DML_이_자기_출력을_읽거나_고치면_실행_전에_실패한다() {
    Long outputDatasetId = 428L;
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out428"));

    stubSqlAccess(Set.of(outputDatasetId), Set.of(outputDatasetId));
    String insertSelf = "INSERT INTO data.\"out428\" (val) SELECT val FROM data.\"out428\"";
    assertThat(
            runner.executeStep(
                4281L,
                replaceStep(4280L, "SQL", insertSelf, outputDatasetId),
                42L,
                "TestPipeline",
                1L,
                true))
        .isEqualTo("FAILED");

    stubSqlAccess(Set.of(), Set.of(outputDatasetId));
    String updateSelf = "UPDATE data.\"out428\" SET val = val + 1";
    assertThat(
            runner.executeStep(
                4282L,
                replaceStep(4280L, "SQL", updateSelf, outputDatasetId),
                42L,
                "TestPipeline",
                1L,
                true))
        .isEqualTo("FAILED");

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository, times(2))
        .updateStepExecution(
            anyLong(), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    assertThat(error.getAllValues())
        .allSatisfy(
            message ->
                assertThat(message)
                    .startsWith(
                        "교체(REPLACE) 스텝의 INSERT·UPDATE·DELETE 문은 출력 데이터셋 자신을 읽거나 고칠 수 없습니다"));
    verifyNoInteractions(executorClient, sqlExecutor, sqlColumnProbe);
  }

  /**
   * 출력 미지정(이 스텝 소유 TEMP) REPLACE SELECT 가 그 TEMP 자신을 읽으면, TEMP 준비 전에 정한 기존 TEMP 로 판정해 잠금 전용 선행문 +
   * 다시 채우기 한 문장을 보낸다. 기존 TEMP 는 한 번만 찾는다 — 실행 전 판정과 TEMP 준비가 같은 조회 결과를 쓴다.
   */
  @Test
  void 출력_미지정_REPLACE_SELECT_가_자기_TEMP_를_읽으면_TEMP_를_비우기_전_데이터로_다시_채운다() {
    Long stepId = 4290L;
    Long tempId = 429L;
    String selectSql = "SELECT code, name || '+' AS name FROM data.\"ptmp_429\"";
    PipelineStepResponse step =
        stepResponse(stepId, "self-temp", "SQL", selectSql, null, List.of());
    stubSqlAccess(Set.of(tempId), Set.of());
    stubProbeColumns("code", "name");
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(tempId));
    when(tempDatasetService.hasSchemaChanged(eq(tempId), any())).thenReturn(false);
    when(datasetRepository.findTableNameById(tempId)).thenReturn(Optional.of("ptmp_429"));
    when(columnRepository.findByDatasetId(tempId))
        .thenReturn(List.of(col("code", false), col("name", false)));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("SQL executed successfully");

    assertThat(runner.executeStep(4291L, step, 42L, "TestPipeline", 1L, false))
        .isEqualTo("COMPLETED");

    verify(sqlExecutor)
        .execute(
            eq(List.of("DELETE FROM \"data\".\"ptmp_429\" WHERE false")),
            eq(
                "WITH \"_fh_self_src\" AS (\n"
                    + selectSql
                    + "\n), \"_fh_self_clear\" AS (DELETE FROM data.\"ptmp_429\" RETURNING 1)"
                    + " INSERT INTO data.\"ptmp_429\" (\"code\", \"name\") SELECT * FROM"
                    + " \"_fh_self_src\" WHERE (SELECT count(*) FROM \"_fh_self_clear\") >= 0"));
    verify(tempDatasetService, times(1)).findExistingTempDataset(stepId);
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
  }

  /**
   * 위와 같은 TEMP 자기 읽기인데 결과 열이 바뀌면, TEMP 를 다시 만들기 <b>전에</b> 실패한다 — 다시 만든 빈 TEMP 를 읽으면 기존 행이 모두 사라진다.
   * 새 열 검증·재생성·생성·실행 어느 것도 하지 않아 TEMP 가 그대로 남고, 할 일을 알려 주는 문구로 실패한다.
   */
  @Test
  void REPLACE_SELECT_가_자기_TEMP_를_읽는데_결과_열이_바뀌면_다시_만들기_전에_실패한다() {
    Long stepId = 4300L;
    Long tempId = 430L;
    String selectSql = "SELECT code, name, 'x' AS extra FROM data.\"ptmp_430\"";
    PipelineStepResponse step =
        stepResponse(stepId, "self-temp-changed", "SQL", selectSql, null, List.of());
    stubSqlAccess(Set.of(tempId), Set.of());
    stubProbeColumns("code", "name", "extra");
    when(tempDatasetService.findExistingTempDataset(stepId)).thenReturn(Optional.of(tempId));
    when(tempDatasetService.hasSchemaChanged(eq(tempId), any())).thenReturn(true);

    assertThat(runner.executeStep(4301L, step, 42L, "TestPipeline", 1L, false)).isEqualTo("FAILED");

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            anyLong(), eq("FAILED"), isNull(), isNull(), error.capture(), isNull(), any());
    assertThat(error.getValue())
        .isEqualTo(PipelineAsyncRunner.SELF_READ_TEMP_SCHEMA_CHANGED_MESSAGE);
    verify(tempDatasetService, never()).validateColumnsForNewTemp(any());
    verify(tempDatasetService, never()).rebuildTempDataset(any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(executorClient, sqlExecutor);
  }

  // ------------------------------------------------------------------ //
  // #735 — 사용자가 직접 쓴 DML(비SELECT) + REPLACE 도 같은 선행 문장 경로를 탄다
  // ------------------------------------------------------------------ //

  /** REPLACE 전략의 스텝 픽스처(출력 데이터셋 지정). */
  private PipelineStepResponse replaceStep(
      Long id, String scriptType, String scriptContent, Long outputDatasetId) {
    return new PipelineStepResponse(
        id,
        "replace-step",
        null,
        scriptType,
        scriptContent,
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
  }

  /**
   * 사용자가 직접 쓴 INSERT + REPLACE 는 즉시 커밋되는 truncate 를 하지 않고, SELECT 자동 적재와 같은 DELETE 선행 문장을 사용자 DML 과
   * <b>같은 요청</b>으로 보낸다(#735). 선행 문장이 있어야 실행기가 출력 테이블 잠금(#731)을 잡고, 비우기와 적재가 한 트랜잭션이 된다 — 예전처럼
   * truncate 가 먼저 커밋되면 겹친 두 실행이 각자 비운 뒤 각자 적재해 행이 두 배가 된다. 사용자 SQL 은 고치지 않는다.
   */
  @Test
  void REPLACE_사용자_INSERT_SQL은_truncate하지_않고_DELETE를_선행문장으로_같은_요청에_보낸다() {
    Long outputDatasetId = 735L;
    String insertSql = "INSERT INTO data.\"out735\" (val) VALUES (1)";
    PipelineStepResponse step = replaceStep(7350L, "SQL", insertSql, outputDatasetId);

    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out735"));
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 1, "1 row(s) affected", null, null, null));

    String status = runner.executeStep(7351L, step, 73L, "TestPipeline", 1L, true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    // SELECT 경로 테스트와 같은 리터럴 — 두 경로의 선행 문장(=잠금 키)이 글자 단위로 같아야 서로 배타다.
    verify(executorClient)
        .executeSql(eq(insertSql), eq(List.of("DELETE FROM \"data\".\"out735\"")));
    verifyNoInteractions(sqlColumnProbe);
  }

  /** 실행기를 끈 경로({@code sqlExecutor})도 같은 계약이다(#735 — 이슈가 재현된 두 경로 중 하나). */
  @Test
  void REPLACE_사용자_INSERT_SQL_실행기_꺼진_경로도_DELETE를_선행문장으로_같은_요청에_보낸다() {
    Long outputDatasetId = 736L;
    String insertSql = "INSERT INTO data.\"out735_off\" (val) VALUES (1)";
    PipelineStepResponse step = replaceStep(7360L, "SQL", insertSql, outputDatasetId);

    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("out735_off"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("SQL executed successfully");

    String status = runner.executeStep(7361L, step, 73L, "TestPipeline", 1L, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(sqlExecutor).execute(List.of("DELETE FROM \"data\".\"out735_off\""), insertSql);
  }

  /**
   * 실행기를 끈 PYTHON + REPLACE 는 출력 테이블 세션 잠금 <b>안에서</b> 임시 테이블 생성 → stdout JSON 적재 → 맞바꿈을 한다(#735,
   * R5). 임시 테이블 이름이 WD-151 부터 실행마다 달라 임시 테이블 충돌은 이름이 막지만, 같은 출력의 REPLACE·SQL 스텝과의 직렬화라는 기존 동작을 지키려고
   * 이 경로의 잠금은 유지한다. 잠금 목이 본문을 실행하지 않게 해 "잠금 밖에서는 출력에 손대지 않는다"를 먼저 고정하고(스크립트 실행은 읽기 전용이라 잠금 밖), 이어서
   * 본문을 돌려 순서를 확인한다. truncate 는 더 이상 없다.
   */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE는_출력_잠금_안에서만_임시테이블에_적재하고_맞바꾼다() {
    Long outputDatasetId = 737L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7370L, "PYTHON", "print('x')", outputDatasetId);

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out735_py"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("v", false)));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"v\": \"a\"}]\n", "warn\n"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.function.Supplier<Object>> body =
        ArgumentCaptor.forClass(java.util.function.Supplier.class);
    when(outputTableSessionLock.callLocked(eq("out735_py"), body.capture()))
        .thenAnswer(inv -> body.getValue().get());

    String status = runner.executeStep(7371L, step, 73L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    // 잠금 → 임시 테이블 → 적재(_tmp, stdout 그대로) → 맞바꿈 순서. 스크립트는 잠금 전에 돈다.
    var order =
        inOrder(pythonExecutor, outputTableSessionLock, dataTableService, localPythonOutputLoader);
    order.verify(pythonExecutor).run("print('x')", 2);
    order.verify(outputTableSessionLock).callLocked(eq("out735_py"), any());
    order.verify(dataTableService).createTempTable("out735_py");
    order
        .verify(localPythonOutputLoader)
        .load(runTemp("out735_py"), "[{\"v\": \"a\"}]\n", Map.of("v", "TEXT"));
    order.verify(dataTableService).finishReplace("out735_py", runTemp("out735_py"), 0L);
  }

  /** 같은 셋업에서 잠금 목이 본문을 실행하지 않으면 출력(임시 테이블 생성·적재·맞바꿈)에 아무 일도 일어나지 않는다 — 쓰는 구간 전체가 잠금 안이다. */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE는_잠금_밖에서_출력에_손대지_않는다() {
    Long outputDatasetId = 737L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7372L, "PYTHON", "print('x')", outputDatasetId);

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out735_py"));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"v\": \"a\"}]", ""));
    // 잠금 목은 본문을 실행하지 않고 null 을 돌려준다 — 이어지는 log() 에서 스텝이 실패하지만, 판정 대상은 상태가 아니라 부작용이다.
    runner.executeStep(7373L, step, 73L, "TestPipeline", userId, false);

    verify(outputTableSessionLock).callLocked(eq("out735_py"), any());

    verify(dataTableService, never()).createTempTable(anyString());
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    verify(dataTableRowService, never()).truncateTable(anyString());
    verifyNoInteractions(localPythonOutputLoader);
  }

  /** 권한이 없어 거부되는 PYTHON 스텝은 출력을 비우지 않는다 — 비우기가 실행 직전으로 옮겨졌다(#735). */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE가_권한_없음으로_거부되면_출력을_비우지_않는다() {
    Long outputDatasetId = 738L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7380L, "PYTHON", "print('x')", outputDatasetId);

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(false);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out735_np"));

    String status = runner.executeStep(7381L, step, 73L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    verifyNoInteractions(outputTableSessionLock, pythonExecutor);
  }

  /** 실행기를 끈 PYTHON + APPEND 는 출력 테이블에 바로 적재한다 — 임시 테이블·잠금·비우기 없음. */
  @Test
  void 실행기_꺼진_PYTHON_APPEND는_잠금도_임시테이블도_없이_출력에_적재한다() {
    Long outputDatasetId = 739L;
    Long userId = 1L;
    PipelineStepResponse step =
        stepResponseWithOutput(
            7390L, "py-append", "PYTHON", "print('x')", outputDatasetId, List.of());

    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out735_ap"));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"v\": 1}]", ""));
    when(localPythonOutputLoader.load(eq("out735_ap"), eq("[{\"v\": 1}]"), any())).thenReturn(1L);

    String status = runner.executeStep(7391L, step, 73L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(localPythonOutputLoader).load(eq("out735_ap"), eq("[{\"v\": 1}]"), any());
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(dataTableService, never()).createTempTable(anyString());
    verifyNoInteractions(outputTableSessionLock);
  }

  /**
   * 로컬 스크립트가 비정상 종료하면 적재·임시 테이블 없이 실패한다. 오류에는 stderr(traceback)가 실린다(출력 있음 → stderr 만, executor
   * 규칙).
   */
  @Test
  void 실행기_꺼진_PYTHON_비정상종료는_적재없이_stderr로_실패한다() {
    Long outputDatasetId = 740L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7400L, "PYTHON", "boom", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_fail"));
    when(pythonExecutor.run("boom", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(1, "[{\"v\": 1}]", "NameError: boom"));

    String status = runner.executeStep(7401L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(7401L),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("NameError: boom"),
            isNull(),
            any());
    verifyNoInteractions(localPythonOutputLoader, outputTableSessionLock);
    verify(dataTableService, never()).createTempTable(anyString());
  }

  /** 로컬 적재가 실패하면 임시 테이블을 지우고 실패한다 — 원본은 그대로(맞바꿈 없음). */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE_적재실패는_임시테이블을_지운다() {
    Long outputDatasetId = 741L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7410L, "PYTHON", "print('x')", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_bad"));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "[{\"nope\": 1}]", ""));
    when(outputTableSessionLock.callLocked(eq("out_bad"), any()))
        .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(1).get());
    when(localPythonOutputLoader.load(eq(runTemp("out_bad")), anyString(), any()))
        .thenThrow(
            new ScriptExecutionException(
                "Python 실행 실패: 출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요."));

    String status = runner.executeStep(7411L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(dataTableService).dropTempTable("out_bad", runTemp("out_bad"));
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
  }

  /**
   * WD-211: 실행기 경로의 적재 데이터 오류(pg_error 22001)는 로컬 경로와 같은 한국어 사유 문구(DataExceptionTranslator)로 스텝 오류가
   * 된다. executor 오류 문자열(일반 안내)이나 원문(내부 SQL)은 싣지 않는다. pg_error 가 없으면 executor 문구에 접두만 붙는다(대조군).
   */
  @Test
  void 실행기_PYTHON_적재_데이터오류는_한국어_사유로_실패한다() {
    Long outputDatasetId = 744L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7440L, "PYTHON", "print('x')", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_de"));
    String generic = "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요.";
    when(executorClient.executePython(anyMap(), anyInt()))
        .thenReturn(
            new ExecutorClient.PythonExecuteResult(
                false,
                "",
                0,
                generic,
                10L,
                0,
                new ExecutorClient.PgError(
                    "22001", "value too long for type character varying(3)", null, null, "code")),
            new ExecutorClient.PythonExecuteResult(false, "", 0, generic, 10L, 0));

    assertThat(runner.executeStep(7445L, step, 74L, "TestPipeline", userId, true))
        .isEqualTo("FAILED");
    assertThat(runner.executeStep(7446L, step, 74L, "TestPipeline", userId, true))
        .isEqualTo("FAILED");

    verify(executionRepository)
        .updateStepExecution(
            eq(7445L),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq(
                "Python 실행 실패: 출력 결과를 데이터셋에 적재하지 못했습니다: 'code' 컬럼에 글자 수 제한(최대 3자)을 넘는 값이 있습니다. 출력"
                    + " JSON 의 값이 데이터셋 컬럼의 타입·형식·길이에 맞는지 확인해 주세요."),
            isNull(),
            any());
    verify(executionRepository)
        .updateStepExecution(
            eq(7446L),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq("Python 실행 실패: " + generic),
            isNull(),
            any());
  }

  /**
   * WD-210: 로컬 PYTHON + REPLACE 에서 stdout 이 JSON 이 아니면(디버그 print 뒤 JSON) 스텝이 실패하고 원본은 그대로다 — 예전엔
   * "완료·0행"이라 #685 규칙으로 맞바꾸지 않아 옛 데이터가 남고 갱신이 멈춘 걸 알 수 없었다. 적재기는 실제 파싱을 태운다(thenCallRealMethod —
   * 파싱이 DB 접근 전에 실패하므로 목의 빈 필드에 닿지 않는다). 임시 테이블은 버리고 맞바꿈은 없다.
   */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE_stdout이_JSON이_아니면_실패하고_원본을_맞바꾸지_않는다() {
    Long outputDatasetId = 743L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7430L, "PYTHON", "print('x')", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_nj"));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "start\n[{\"v\": 1}]\n", ""));
    when(outputTableSessionLock.callLocked(eq("out_nj"), any()))
        .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(1).get());
    when(localPythonOutputLoader.load(eq(runTemp("out_nj")), anyString(), any()))
        .thenCallRealMethod();

    String status = runner.executeStep(7431L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(7431L),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq(
                "Python 실행 실패: 스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다. 출력 데이터셋을 쓰는 스크립트는 결과"
                    + " JSON 한 번만 print 해야 합니다(디버그 출력은 sys.stderr 로). 출력 앞부분: \"start\n[{\"v\": 1}]\""),
            isNull(),
            any());
    verify(dataTableService).dropTempTable("out_nj", runTemp("out_nj"));
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  /**
   * WD-431: 로컬 PYTHON + REPLACE 에서 출력 값을 컬럼 타입으로 바꿀 수 없으면 스텝이 실패하고 그 문구(행·키·값·타입)가 스텝 오류로 기록되며 원본은
   * 맞바꾸지 않는다 — 예전엔 그 칸을 NULL 로 넣고 「완료」라 기존 데이터가 NULL 섞인 데이터로 바뀌었다. 적재기는 실제 변환을 태운다(변환이 DB 접근 전에
   * 실패하므로 목의 빈 필드에 닿지 않는다). 임시 테이블은 버린다.
   */
  @Test
  void 실행기_꺼진_PYTHON_REPLACE_값_변환이_안되면_실패하고_원본을_맞바꾸지_않는다() {
    Long outputDatasetId = 744L;
    Long userId = 1L;
    PipelineStepResponse step = replaceStep(7440L, "PYTHON", "print('x')", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_cv"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(
            List.of(
                new DatasetColumnResponse(
                    null, "n", null, "INTEGER", null, true, false, null, 0, false)));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(
            new PythonScriptExecutor.RunResult(
                0, "[{\"n\": 1}, {\"n\": 2}, {\"n\": \"not-a-number\"}]", ""));
    when(outputTableSessionLock.callLocked(eq("out_cv"), any()))
        .thenAnswer(inv -> inv.<java.util.function.Supplier<?>>getArgument(1).get());
    when(localPythonOutputLoader.load(eq(runTemp("out_cv")), anyString(), any()))
        .thenCallRealMethod();

    String status = runner.executeStep(7441L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(7441L),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq(
                "Python 실행 실패: 출력 JSON 의 3번째 행 'n' 값 'not-a-number' 을(를) 정수(INTEGER)로 변환할 수 없습니다."
                    + " 출력 데이터셋 컬럼 타입에 맞는 값으로 출력해 주세요."),
            isNull(),
            any());
    verify(dataTableService).dropTempTable("out_cv", runTemp("out_cv"));
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    verify(dataTableRowService, never()).insertBatch(anyString(), anyList(), anyList(), anyMap());
  }

  /** 출력 없는 로컬 PYTHON 은 적재하지 않고, 실행 로그는 stdout+stderr 다(executor 와 같은 규칙). */
  @Test
  void 실행기_꺼진_PYTHON_출력없음은_stdout과_stderr를_로그로_남긴다() {
    Long userId = 1L;
    PipelineStepResponse step =
        stepResponse(7420L, "py-noout", "PYTHON", "print('x')", null, List.of());
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "out\n", "err\n"));

    String status = runner.executeStep(7421L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(executionRepository)
        .updateStepExecution(
            eq(7421L), eq("COMPLETED"), any(), eq("out\nerr\n"), isNull(), any(), any());
    verifyNoInteractions(localPythonOutputLoader, outputTableSessionLock);
  }

  /** 실행기 켠 PYTHON 은 실행 주체 슬롯을 실어 보낸다(출력 없음). */
  @Test
  void python_executorEnabled_sendsRunAsSlot() {
    Long userId = 1L;
    PipelineStepResponse step =
        stepResponse(7430L, "py-slot", "PYTHON", "print('x')", null, List.of());
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(pythonReadGrantSync.prepareForRun(any(), anyInt())).thenReturn(4);
    when(executorClient.executePython(anyMap(), eq(4)))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "ok", 0, null, 10L, 0));

    String status = runner.executeStep(7431L, step, 74L, "TestPipeline", userId, true);

    assertThat(status).isEqualTo("COMPLETED");
    verify(executorClient).executePython(anyMap(), eq(4));
  }

  /** 로컬 경로도 실행 주체 슬롯으로 스크립트를 띄운다. */
  @Test
  void python_executorDisabled_runsWithRunAsSlot() {
    Long userId = 1L;
    PipelineStepResponse step =
        stepResponse(7440L, "py-slot-local", "PYTHON", "print('x')", null, List.of());
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(pythonReadGrantSync.prepareForRun(any(), anyInt())).thenReturn(3);
    when(pythonExecutor.run("print('x')", 3))
        .thenReturn(new PythonScriptExecutor.RunResult(0, "", ""));

    String status = runner.executeStep(7441L, step, 74L, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(pythonExecutor).run("print('x')", 3);
  }

  /**
   * CR2 — 실행 중 등급 정의 변경으로 슬롯 롤 세션이 끊겨 스크립트가 실패하면, 원인을 밝힌 문구로 스텝이 실패한다(실행기 켬/끔 두 경로). 끊긴 적이 없으면 예전
   * 문구 그대로(대조군).
   */
  @Test
  void python_failsWithClearMessage_whenSlotSessionsWereTerminatedDuringRun() {
    Long userId = 1L;
    PipelineStepResponse step =
        stepResponse(7460L, "py-term", "PYTHON", "print('x')", null, List.of());
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(pythonReadGrantSync.prepareForRun(any(), anyInt())).thenReturn(2);
    when(executorClient.executePython(anyMap(), eq(2)))
        .thenReturn(
            new ExecutorClient.PythonExecuteResult(
                false, "", 0, "terminating connection due to administrator command", 10L, 1));
    when(pythonExecutor.run("print('x')", 2))
        .thenReturn(new PythonScriptExecutor.RunResult(1, "", "terminating connection"));
    when(pythonReadGrantSync.slotSessionsTerminatedSince(anyLong(), anyLong()))
        .thenReturn(false, true, true);

    assertThat(runner.executeStep(7461L, step, 74L, "TestPipeline", userId, true))
        .isEqualTo("FAILED");
    assertThat(runner.executeStep(7462L, step, 74L, "TestPipeline", userId, true))
        .isEqualTo("FAILED");
    assertThat(runner.executeStep(7463L, step, 74L, "TestPipeline", userId, false))
        .isEqualTo("FAILED");

    verify(executionRepository)
        .updateStepExecution(
            eq(7461L),
            eq("FAILED"),
            isNull(),
            isNull(),
            argThat(m -> m != null && m.startsWith("Python 실행 실패: terminating")),
            isNull(),
            any());
    for (long id : new long[] {7462L, 7463L}) {
      verify(executionRepository)
          .updateStepExecution(
              eq(id),
              eq("FAILED"),
              isNull(),
              isNull(),
              contains("실행 중 보안 등급 구성이 바뀌어 Python 읽기 연결을 끊었습니다"),
              isNull(),
              any());
    }
  }

  /**
   * 슬롯 준비 실패(JIT 동기화 실패·등급 없음) → executor·자식 프로세스를 부르지 않고, REPLACE 임시 테이블도 만들지 않고 스텝
   * 실패(fail-closed). 실행기 켬/끔 두 경로 모두.
   */
  @Test
  void python_prepareFails_doesNotExecute() {
    Long userId = 1L;
    Long outputDatasetId = 745L;
    PipelineStepResponse step = replaceStep(7450L, "PYTHON", "print('x')", outputDatasetId);
    when(permissionChecker.hasPermission(userId, "pipeline:python_execute")).thenReturn(true);
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("out_deny"));
    when(pythonReadGrantSync.prepareForRun(any(), anyInt()))
        .thenThrow(new PythonReadAccessException("Python 읽기 권한을 준비하지 못해 실행을 중단했습니다."));

    String executorOn = runner.executeStep(7451L, step, 74L, "TestPipeline", userId, true);
    String executorOff = runner.executeStep(7452L, step, 74L, "TestPipeline", userId, false);

    assertThat(executorOn).isEqualTo("FAILED");
    assertThat(executorOff).isEqualTo("FAILED");
    verify(executorClient, never()).executePython(anyMap(), anyInt());
    verifyNoInteractions(pythonExecutor, localPythonOutputLoader, outputTableSessionLock);
    verify(dataTableService, never()).createTempTable(anyString());
    verify(dataTableRowService, never()).truncateTable(anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(7451L),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("Python 읽기 권한을 준비하지 못해"),
            isNull(),
            any());
  }

  /**
   * Fix round 1, 리뷰 지적 1 — 회귀 가드. loadStrategy 컬럼은 서버에 enum·체크 제약이 없어 ({@code
   * PipelineStepRepository} 는 null 만 "REPLACE" 로 매핑) 임의 문자열이 그대로 들어올 수 있다. Task 4 이전에는 상단 switch 의
   * default 분기가 알 수 없는 값을 REPLACE 로 폴백하며 truncate 했는데, SQL 스텝을 그 switch 밖으로 뺀 뒤
   * "REPLACE".equalsIgnoreCase 만으로 판단하면 알 수 없는 값이 아무것도 비우지 않고 매 실행마다 행이 누적된다 — 이 테스트는 그 회귀를 막는다.
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
   * MERGE 는 APPEND 와 마찬가지로 출력을 비우지 않는다(존재 이유 자체가 "기존 행 보존 + upsert") — truncate 도, DELETE 선행 문장도
   * 없어야 한다.
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
                true, List.of(), List.of(), 0, "2 rows affected", null, null, null));

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
   * PostgreSQL 이 "ON CONFLICT DO UPDATE command cannot affect row a second time" 를 돌려주면 (SELECT 가
   * 같은 PK 를 두 번 이상 낼 때) 스텝이 FAILED 가 되고, 오류 메시지에 "같은 키"가 포함돼야 한다 — 사용자가 원인(PG 내부 문구가 아니라)을 바로 알 수
   * 있어야 한다.
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
                "ERROR: ON CONFLICT DO UPDATE command cannot affect row a second time",
                null,
                null));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, true);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), contains("같은 키"), isNull(), any());
  }

  /**
   * Fix round 1, must 2 — 위 테스트는 {@code executorEnabled=true}(=result.error() 분기)만 태운다. 실행기를 끈
   * 경로({@code sqlExecutor.execute} 가 {@link ScriptExecutionException}을 던지는 경로)는 test 프로필의 기본값이자
   * Task 6 통합 테스트가 실제로 타는 경로인데, 그 catch 분기(:531-544)가 이 브랜치 전용 테스트 없이는 검증되지 않는다.
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
                "SQL 실행 실패: 한 번에 처리할 행 안에서 같은 키가 두 번 이상 나옵니다.\n원인: ERROR: ON CONFLICT DO UPDATE"
                    + " command cannot affect row a second time"));

    // when
    String status =
        runner.executeStep(stepExecId, sqlStep, pipelineId, "TestPipeline", userId, false);

    // then
    assertThat(status).isEqualTo("FAILED");
    verify(sqlExecutor).execute(eq(List.of()), anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), contains("같은 키"), isNull(), any());
  }

  /**
   * Fix round 1, must 4 — 중복키 번역과 형제인 {@code NO_UNIQUE_CONSTRAINT_PG_MESSAGE} 번역 분기가 지금까지 테스트 없이
   * 방치돼 있었다. {@code createPrimaryKeyIndexConcurrently} 가 남길 수 있는 INVALID {@code ux_<table>_pk}
   * 인덱스처럼, PK 메타데이터는 있는데 실제 유니크 제약이 없을 때 PostgreSQL 이 이 문구로 거부한다 — "PK 를 다시 지정하라"는 한국어 안내로 바뀌는지
   * 확인한다.
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
                    + " specification",
                null,
                null));

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
   * Fix round 2, should 3 — MERGE+비SQL 거부를 스텝 타입 분기(:304 근방) 전체보다 앞으로 옮긴 변경(Fix round 1, nit 5)이
   * 실제로 API_CALL/AI_CLASSIFY/실행기 켠 PYTHON 조합까지 잡는지 확인한다. 이전 위치(비SQL 타입 전용 switch 안)에서는 이 조합이 switch
   * 진입 조건에서 이미 제외돼 있어 거부되지 않고 조용히 통과했었다.
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
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            errorCaptor.capture(),
            isNull(),
            any());
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
    when(apiCallExecutor.execute(
            any(), eq("ptmp_api"), isNull(), eq("APPEND"), any(), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(5, "log"));

    // when
    String status =
        runner.executeStep(stepExecId, apiStep, pipelineId, "TestPipeline", userId, false);

    // then: 임시 데이터셋 생성, apiCallExecutor 호출 확인
    assertThat(status).isEqualTo("COMPLETED");
    verify(tempDatasetService)
        .createTempDataset(
            any(), eq(pipelineId), eq("TestPipeline"), eq(stepId), eq("api-step"), eq(userId));
    verify(apiCallExecutor)
        .execute(any(), eq("ptmp_api"), isNull(), eq("APPEND"), any(), any(), any());
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
            List.of(AI_INPUT_DATASET_ID),
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
            argThat(s -> tempDatasetId.equals(s.outputDatasetId())),
            eq(stepExecId),
            eq(userId),
            any()))
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
            argThat(s -> tempDatasetId.equals(s.outputDatasetId())),
            eq(stepExecId),
            eq(userId),
            any());
  }

  /**
   * WD-216: 명시 입력이 없고 의존 스텝에서도 출력 데이터셋을 얻지 못하면 분류할 입력이 없다 — 출력 TEMP 를 만들기 전에 한국어로 실패한다. 예전에는 실행기가
   * 영어 「No input rows found」와 함께 0행 완료를 돌려줘 스텝·실행 모두 COMPLETED 였다.
   */
  @Test
  void executeStep_aiClassifyWithNoResolvableInput_failsInKoreanBeforeCreatingTemp() {
    Long pipelineId = 24L, userId = 1L, stepId = 224L, stepExecId = 324L;
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            stepId,
            "ai-no-input",
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

    String status =
        runner.executeStep(stepExecId, aiStep, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq(AiClassifyExecutor.NO_INPUT_DATASET_MESSAGE),
            isNull(),
            any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(aiClassifyExecutor, never()).execute(any(), any(), any(), any());
  }

  /** WD-216: 의존 스텝이 있어도 그 스텝이 출력 데이터셋을 갖지 않으면(출력 미지정 DML 등) 입력이 해석되지 않아 같은 실패다. */
  @Test
  void executeStep_aiClassifyWithDependencyWithoutOutput_failsInKorean() {
    Long pipelineId = 25L, userId = 1L, step1Id = 225L, step2Id = 226L, stepExecId = 326L;
    PipelineStepResponse dmlStep =
        stepResponse(step1Id, "dml-src", "SQL", "UPDATE t SET a = 1", null, List.of());
    PipelineStepResponse aiStep =
        new PipelineStepResponse(
            step2Id,
            "ai-dep",
            null,
            "AI_CLASSIFY",
            null,
            null,
            null,
            List.of(),
            List.of("dml-src"),
            1,
            "REPLACE",
            null,
            Map.of(),
            null,
            null);
    when(permissionChecker.hasPermission(userId, "pipeline:ai_execute")).thenReturn(true);
    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(dmlStep, aiStep));
    when(datasetRepository.findBySourcePipelineStepId(step1Id)).thenReturn(Optional.empty());
    // 가드가 없으면 TEMP 생성까지 가도록 설정을 준비해 둔다(가드가 있으면 쓰이지 않으므로 lenient) — 변이 시 아래 never() 가 잡는다.
    lenient()
        .when(objectMapper.convertValue(any(), eq(AiClassifyConfig.class)))
        .thenReturn(
            new AiClassifyConfig(
                "Classify",
                List.of(new AiClassifyConfig.OutputColumn("label", "TEXT")),
                List.of("text"),
                null,
                null));

    String status =
        runner.executeStep(stepExecId, aiStep, pipelineId, "TestPipeline", userId, false);

    assertThat(status).isEqualTo("FAILED");
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            eq(AiClassifyExecutor.NO_INPUT_DATASET_MESSAGE),
            isNull(),
            any());
    verify(aiClassifyExecutor, never()).execute(any(), any(), any(), any());
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
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
            eq(userId),
            any()))
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
            eq(userId),
            any());
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
    when(executorClient.executePython(any(), anyInt()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "hello\n", 0, null, 100L, 0));

    // when — executorEnabled=true
    String status =
        runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: executorClient.executePython이 script + output_table + column_type_map 포함 Map으로 호출됨
    assertThat(status).isEqualTo("COMPLETED");
    ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executePython(captor.capture(), eq(2));
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
    when(executorClient.executePython(any(), anyInt()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "done", 0, null, 200L, 10));

    // when
    runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: createTempTable + swapTable (rows_loaded=10 > 0)
    verify(dataTableService).createTempTable("output_replace");
    // 스왑 여부의 판단은 DataTableService.finishReplace 가 단독으로 갖는다(#685) — 여기서는
    // 실행기가 **적재된 행 수를 정확히 넘겼는지**만 본다. 판단 자체는 DataTableServiceTest 가 본다.
    verify(dataTableService).finishReplace("output_replace", runTemp("output_replace"), 10L);
    verify(dataTableService, never()).dropTempTable(any(), any());
    // 실행기에는 이번 실행의 임시 테이블 이름이 그대로 적재 대상으로 간다(WD-151) — 실행기는 이름을 스스로 만들지 않는다.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> pyRequest = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executePython(pyRequest.capture(), anyInt());
    assertThat(pyRequest.getValue()).containsEntry("output_table", runTemp("output_replace"));
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
    when(executorClient.executePython(any(), anyInt()))
        .thenReturn(new ExecutorClient.PythonExecuteResult(true, "", 0, null, 100L, 0));

    // when
    runner.executeStep(stepExecId, pythonStep, pipelineId, "TestPipeline", userId, true);

    // then: createTempTable 후 rows_loaded=0 → dropTempTable (swap 없음)
    verify(dataTableService).createTempTable("output_norows");
    // 0행이 그대로 전달돼야 finishReplace 가 원본을 지키는 쪽으로 판단할 수 있다.
    verify(dataTableService).finishReplace("output_norows", runTemp("output_norows"), 0L);
    verify(dataTableService, never()).swapTable(any(), any());
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
   * <p>출력(테넌트 파이프라인 롤 커넥션)과 책갈피(앱 커넥션)는 다른 커넥션이라 한 트랜잭션으로 묶을 수 없다. 그래서 순서가 유일한 안전장치다. 이 테스트는 {@code
   * sqlExecutor.execute} 가 도는 동안 {@code advanceCursor} 가 아직 불리지 않았음을 실행 시점에 기록해 확인한다 — 프로덕션에서 두 줄을
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
        .advanceCursor(any(), any(), anyBoolean(), any());
    when(sqlExecutor.execute(anyList(), anyString()))
        .thenAnswer(
            inv -> {
              // 이 시점이 "출력이 커밋되는 순간"이다 — 여기서 이미 책갈피가 전진했다면 순서가 뒤집힌 것.
              advancedBeforeOutputCommit.set(advanced.get());
              return "ok";
            });

    String status = runner.executeStep(stepExecId, step, pipelineId, "P", userId, false);

    assertThat(status).isEqualTo("COMPLETED");
    assertThat(advancedBeforeOutputCommit).as("책갈피는 출력이 커밋된 뒤에만 전진해야 한다").isFalse();
    assertThat(advanced).as("성공 실행은 책갈피를 전진시켜야 한다").isTrue();
    verify(stepRepository).advanceCursor(eq(stepId), eq(candidate), eq(false), any());
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
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
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
    verify(stepRepository).advanceCursor(eq(stepId), any(), eq(true), any());
  }

  /**
   * <b>비SELECT 증분 스텝은 전체 재생성 예약이 걸려도 출력을 비우지 않는다.</b>
   *
   * <p>출력을 비우는 DELETE 뒤에 출력을 다시 채우는 것은 SELECT 자동 적재 경로(INSERT INTO ... SELECT) 뿐이다. 사용자가 직접 쓴
   * INSERT/UPDATE/DELETE 스텝(APPEND + {{last_run_at}} 은 저장 시점에 거부되지 않는다)에 그 DELETE 를 얹으면 출력이 빈 채로
   * COMPLETED 가 되고 책갈피까지 전진한다 — 조용한 전량 손실이다. 비SELECT 의 "전체 재생성"은 <b>전체 읽기</b>(-infinity)까지만을 뜻한다.
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

    verify(stepRepository).advanceCursor(eq(stepId), any(), eq(false), any());
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
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId), eq("FAILED"), isNull(), isNull(), contains("MERGE"), isNull(), any());
  }

  // ------------------------------------------------------------------ //
  // 증분 스텝 겹침 직렬화(WD-223)
  // ------------------------------------------------------------------ //

  /**
   * 증분 스텝은 책갈피·후보를 읽기 <b>전에</b> 스텝 잠금을 잡고, 책갈피를 전진시킨 <b>뒤에</b> 푼다. 순서가 어긋나면(후보를 먼저 잡고 기다리거나, 전진 전에
   * 풀면) 겹친 실행이 같은 구간을 다시 적재한다.
   */
  @Test
  void 증분_스텝은_책갈피_읽기_전에_잠그고_전진_뒤에_푼다() {
    Long stepId = 710L;
    Long outputDatasetId = 98L;
    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "APPEND");
    SessionAdvisoryLock.Held held = mock(SessionAdvisoryLock.Held.class);
    when(incrementalStepLock.acquire(eq(stepId), eq("incremental-step"), any())).thenReturn(held);
    // 첫 해제 순간에 책갈피가 이미 전진했는지 기록한다 — finally 안전망이 뒤에서 한 번 더 닫아도 "전진 전 조기 해제" 변이를 잡는다.
    java.util.concurrent.atomic.AtomicBoolean advanced =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Boolean> advancedAtFirstRelease =
        new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(
            inv -> {
              advanced.set(true);
              return null;
            })
        .when(stepRepository)
        .advanceCursor(any(), any(), anyBoolean(), any());
    doAnswer(
            inv -> {
              advancedAtFirstRelease.compareAndSet(null, advanced.get());
              return null;
            })
        .when(held)
        .close();
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", false), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(null, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-10-24T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    String status = runner.executeStep(810L, step, 78L, "P", 1L, false);

    assertThat(status).isEqualTo("COMPLETED");
    org.mockito.InOrder order =
        inOrder(incrementalStepLock, stepRepository, incrementalCursorService, sqlExecutor, held);
    order.verify(incrementalStepLock).acquire(eq(stepId), eq("incremental-step"), any());
    order.verify(stepRepository).findCursor(stepId);
    order.verify(incrementalCursorService).captureCandidate();
    order.verify(sqlExecutor).execute(anyList(), anyString());
    order.verify(stepRepository).advanceCursor(eq(stepId), any(), eq(false), any());
    order.verify(held, atLeastOnce()).close();
    assertThat(advancedAtFirstRelease.get()).as("잠금은 책갈피 전진 뒤에야 처음 풀린다").isTrue();
  }

  /** 실행기를 켠 경로(운영 기본값)도 같은 잠금 아래에서 책갈피를 읽고 전진시킨다. */
  @Test
  void 실행기를_켠_증분_스텝도_책갈피_읽기_전에_잠그고_전진_뒤에_푼다() {
    Long stepId = 714L;
    Long outputDatasetId = 102L;
    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "APPEND");
    SessionAdvisoryLock.Held held = mock(SessionAdvisoryLock.Held.class);
    when(incrementalStepLock.acquire(eq(stepId), any(), any())).thenReturn(held);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", false), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(null, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-10-24T01:00:00Z"));
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                true, List.of(), List.of(), 0, "ok", null, null, null));

    String status = runner.executeStep(814L, step, 82L, "P", 1L, true);

    assertThat(status).isEqualTo("COMPLETED");
    org.mockito.InOrder order = inOrder(incrementalStepLock, stepRepository, executorClient, held);
    order.verify(incrementalStepLock).acquire(eq(stepId), any(), any());
    order.verify(stepRepository).findCursor(stepId);
    order.verify(executorClient).executeSql(anyString(), anyList());
    order.verify(stepRepository).advanceCursor(eq(stepId), any(), eq(false), any());
    order.verify(held, atLeastOnce()).close();
  }

  /** 증분 스텝이 실패해도 잠금은 풀린다 — 풀리지 않으면 그 스텝의 다음 실행이 모두 상한까지 기다린다. */
  @Test
  void 증분_스텝이_실패해도_잠금을_푼다() {
    Long stepId = 711L;
    Long outputDatasetId = 99L;
    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "APPEND");
    SessionAdvisoryLock.Held held = mock(SessionAdvisoryLock.Held.class);
    when(incrementalStepLock.acquire(eq(stepId), any(), any())).thenReturn(held);
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", false), col("name", false)));
    stubProbeColumns("code", "name");
    when(stepRepository.findCursor(stepId))
        .thenReturn(Optional.of(new StepCursor(null, false, outputDatasetId)));
    when(incrementalCursorService.captureCandidate())
        .thenReturn(OffsetDateTime.parse("2026-10-24T01:00:00Z"));
    when(sqlExecutor.execute(anyList(), anyString()))
        .thenThrow(new ScriptExecutionException("boom"));

    String status = runner.executeStep(811L, step, 79L, "P", 1L, false);

    assertThat(status).isEqualTo("FAILED");
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
    verify(held).close();
  }

  /** 대기 상한을 넘기면 책갈피를 읽지도 쓰지도 않고 한국어 안내로 실패한다 — 책갈피가 그대로라 다음 실행이 그 구간을 읽는다. */
  @Test
  void 증분_스텝_잠금_대기가_상한을_넘기면_책갈피를_건드리지_않고_실패한다() {
    Long stepId = 712L;
    Long stepExecId = 812L;
    Long outputDatasetId = 100L;
    PipelineStepResponse step = incrementalMergeStep(stepId, outputDatasetId, "APPEND");
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("incremental_out"));
    when(incrementalStepLock.acquire(eq(stepId), any(), any()))
        .thenThrow(
            new ScriptExecutionException(
                IncrementalStepLock.timeoutMessage("incremental-step", 1_800_000)));

    String status = runner.executeStep(stepExecId, step, 80L, "P", 1L, false);

    assertThat(status).isEqualTo("FAILED");
    verify(stepRepository, never()).findCursor(any());
    verify(stepRepository, never()).advanceCursor(any(), any(), anyBoolean(), any());
    verify(sqlExecutor, never()).execute(anyList(), anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            contains("30분 넘게 끝나지 않아"),
            isNull(),
            any());
  }

  /** 증분이 아닌 SQL 스텝은 스텝 잠금을 잡지 않는다 — 불필요한 직렬화로 겹친 실행을 늦추지 않는다. */
  @Test
  void 증분이_아닌_SQL_스텝은_스텝_잠금을_잡지_않는다() {
    Long stepId = 713L;
    Long outputDatasetId = 101L;
    PipelineStepResponse step =
        new PipelineStepResponse(
            stepId,
            "plain-step",
            null,
            "SQL",
            "SELECT code, name FROM data.\"source\"",
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
    when(datasetRepository.findTableNameById(outputDatasetId)).thenReturn(Optional.of("plain_out"));
    when(columnRepository.findByDatasetId(outputDatasetId))
        .thenReturn(List.of(col("code", false), col("name", false)));
    stubProbeColumns("code", "name");
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");

    String status = runner.executeStep(813L, step, 81L, "P", 1L, false);

    assertThat(status).isEqualTo("COMPLETED");
    verifyNoInteractions(incrementalStepLock);
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
        stepId,
        name,
        null,
        "API_CALL",
        null,
        null,
        null,
        List.of(),
        List.of(),
        0,
        loadStrategy,
        Map.of(),
        null,
        null,
        null);
  }

  /** AI_CLASSIFY 테스트 스텝의 명시 입력 데이터셋 id(WD-216 이후 입력 필수). */
  private static final Long AI_INPUT_DATASET_ID = 9001L;

  /**
   * 출력 데이터셋을 지정하지 않은(=임시 데이터셋 자동 생성) AI_CLASSIFY 스텝. 입력은 명시 입력 하나({@link #AI_INPUT_DATASET_ID})다 —
   * 입력이 없으면 러너가 TEMP 를 만들기 전에 실패시키므로(WD-216) 출력 경로를 보는 테스트에는 입력이 있어야 한다.
   */
  private PipelineStepResponse aiStepNoOutput(Long stepId, String name, String loadStrategy) {
    return new PipelineStepResponse(
        stepId,
        name,
        null,
        "AI_CLASSIFY",
        null,
        null,
        null,
        List.of(AI_INPUT_DATASET_ID),
        List.of(),
        0,
        loadStrategy,
        null,
        Map.of(),
        null,
        null);
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
    when(apiCallExecutor.execute(
            any(), eq("ptmp_api_fail"), isNull(), eq("REPLACE"), any(), any(), any()))
        .thenThrow(new ScriptExecutionException("API 호출 실패"));

    // when
    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-fail", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

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
            stepExecId,
            apiStepNoOutput(stepId, "api-exec-fail", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            true);

    // then: 원본 truncate 없음 + 스테이징만 정리 + 맞바꿈 없음
    assertThat(status).isEqualTo("FAILED");
    verify(dataTableRowService, never()).truncateTable(anyString());
    // 실패 정리는 이번 실행이 만든 임시 테이블만 지운다(WD-151) — 같은 출력에 적재 중인 다른 실행의 것은 이름이 달라 닿지 않는다.
    verify(dataTableService).dropTempTable("ptmp_api_exec_fail", runTemp("ptmp_api_exec_fail"));
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
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
    when(aiClassifyExecutor.execute(any(), eq(stepExecId), eq(userId), any()))
        .thenThrow(new ScriptExecutionException("AI 분류 실패"));

    // when
    String status =
        runner.executeStep(
            stepExecId,
            aiStepNoOutput(stepId, "ai-fail", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

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
            any(), eq("ptmp_api_append"), isNull(), eq("APPEND"), any(), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(3, "log"));

    // when
    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-append", "APPEND"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

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
    when(aiClassifyExecutor.execute(any(), eq(stepExecId), eq(userId), any()))
        .thenReturn(new AiClassifyExecutor.ExecutionResult(4L, "ai log"));

    // when
    String status =
        runner.executeStep(
            stepExecId,
            aiStepNoOutput(stepId, "ai-append", "APPEND"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

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
    verify(aiClassifyExecutor).execute(stepCaptor.capture(), eq(stepExecId), eq(userId), any());
    assertThat(stepCaptor.getValue().loadStrategy())
        .as("APPEND 스텝의 로드 전략이 실행기까지 그대로 도달해야 한다")
        .isEqualTo("APPEND");
  }

  /**
   * 성공한 REPLACE 의 최종 결과가 그대로인지 고정하는 가드 테스트. 선비우기 제거 전에도 통과했다 (그때는 truncate 가 추가로 불렸을 뿐 맞바꿈 결과는 같았다)
   * — 회귀 방지용이다.
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
            stepExecId,
            apiStepNoOutput(stepId, "api-replace", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            true);

    // then: 스테이징 생성 → 7행 맞바꿈. 원본 truncate 는 없다.
    assertThat(status).isEqualTo("COMPLETED");
    verify(dataTableService).createTempTable("ptmp_api_replace");
    verify(dataTableService).finishReplace("ptmp_api_replace", runTemp("ptmp_api_replace"), 7L);
    verify(dataTableService, never()).dropTempTable(anyString(), anyString());
    // 실행기에는 이번 실행의 임시 테이블 이름이 그대로 적재 대상으로 간다(WD-151).
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> apiRequest = ArgumentCaptor.forClass(Map.class);
    verify(executorClient).executeApiCall(apiRequest.capture());
    assertThat(apiRequest.getValue()).containsEntry("output_table", runTemp("ptmp_api_replace"));
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
            any(), eq("ptmp_api_replace_off"), isNull(), eq("REPLACE"), any(), any(), any()))
        .thenReturn(new ApiCallExecutor.ApiCallResult(9, "log"));

    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-replace-off", "REPLACE"),
            pipelineId,
            "TestPipeline",
            userId,
            false);

    assertThat(status).isEqualTo("COMPLETED");
    verify(apiCallExecutor)
        .execute(any(), eq("ptmp_api_replace_off"), isNull(), eq("REPLACE"), any(), any(), any());
    verify(dataTableRowService, never()).truncateTable(anyString());
  }

  /**
   * 코드리뷰 LOW — 비SQL·비API 스텝의 로드 전략 분기(상단 {@code switch})가 대소문자를 가리면 소문자 레거시 행("append")이 {@code
   * default} 로 떨어져 <b>REPLACE 처럼 출력을 truncate</b> 한다. 같은 메서드의 SQL 분기는 전부 {@code equalsIgnoreCase} 라
   * 같은 값이 경로에 따라 다르게 해석되는 비대칭이었다. 실행기 끈 PYTHON 스텝이 그 switch 로 들어가는 유일한 경로라 그것으로 고정한다.
   */
  @Test
  void 소문자_append_는_비SQL_스텝에서도_truncate하지_않는다() {
    Long pipelineId = 97L, userId = 1L, stepId = 908L, stepExecId = 958L, outputDatasetId = 998L;
    PipelineStepResponse pythonStep =
        new PipelineStepResponse(
            stepId,
            "py-lower-append",
            null,
            "PYTHON",
            "print('x')",
            outputDatasetId,
            null,
            List.of(),
            List.of(),
            0,
            "append",
            null,
            null,
            null,
            null);

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

  // ── WD-111: 스텝 오류 문구 = 한국어 안내 + 원인 원문(내부 SQL 없음) ─────────────────────────────

  /**
   * 실행기 켠 경로(운영 기본값 app.executor.enabled=true) — 실행기가 구조화 오류(pg_error)를 주면 SQLState 로 분류한 안내 + 주
   * 메시지·DETAIL 만 저장하고, 오류 문자열(str(exc))의 위치 표시 줄에 실린 러너 INSERT 문은 저장하지 않는다.
   */
  @Test
  void 실행기_SQL_실패는_SQLState_안내와_원문만_저장하고_내부_INSERT_문은_싣지_않는다() {
    Long stepExecId = 3601L;
    Long outputDatasetId = 3602L;
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(
            3600L, "dup-step", "SQL", "SELECT k FROM data.\"src\"", outputDatasetId, List.of());
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("out_orders"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("k", true)));
    stubProbeColumns("k");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                false,
                null,
                null,
                0,
                "",
                "duplicate key value violates unique constraint \"ux_out_orders_pk\"\n"
                    + "DETAIL:  Key (k)=(1) already exists.\n"
                    + "LINE 1: INSERT INTO \"data\".\"out_orders\" (\"k\") SELECT k FROM ...\n",
                new ExecutorClient.PgError(
                    "23505",
                    "duplicate key value violates unique constraint \"ux_out_orders_pk\"",
                    "Key (k)=(1) already exists.",
                    null,
                    null),
                null));

    String status = runner.executeStep(stepExecId, sqlStep, 36L, "TestPipeline", 1L, true);

    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            errorCaptor.capture(),
            isNull(),
            any());
    assertThat(errorCaptor.getValue())
        .isEqualTo(
            "SQL 실행 실패: 같은 키 값이 이미 있습니다. 출력 데이터셋의 기본 키(또는 유니크) 컬럼에 이미 있는 값이나 SQL 결과 안에서 겹치는"
                + " 값이 들어갔습니다. 로드 전략(REPLACE·APPEND·MERGE)과 기본 키 설정을 확인하세요.\n"
                + "원인: ERROR: duplicate key value violates unique constraint \"ux_out_orders_pk\"\n"
                + "DETAIL: Key (k)=(1) already exists.")
        .doesNotContain("INSERT INTO");
  }

  /**
   * 구조화 오류를 보내지 않는 구버전 실행기(배포 순서 어긋남)·PG 오류가 아닌 실패는 오류 문자열을 쓰되, 위치 표시 줄(LINE n: …내부 SQL…)과 캐럿 줄은
   * 걷어낸다. 안내는 추측해 붙이지 않는다.
   */
  @Test
  void 구조화_오류가_없는_실행기_실패는_위치_표시_줄만_걷어낸다() {
    Long stepExecId = 3611L;
    Long outputDatasetId = 3612L;
    PipelineStepResponse sqlStep =
        stepResponseWithOutput(
            3610L, "old-exec", "SQL", "SELECT k FROM data.\"src\"", outputDatasetId, List.of());
    when(datasetRepository.findTableNameById(outputDatasetId))
        .thenReturn(Optional.of("out_orders"));
    when(columnRepository.findByDatasetId(outputDatasetId)).thenReturn(List.of(col("k", false)));
    stubProbeColumns("k");
    when(executorClient.executeSql(anyString(), anyList()))
        .thenReturn(
            new ExecutorClient.SqlExecuteResult(
                false,
                null,
                null,
                0,
                "",
                "syntax error at or near \"FORM\"\n"
                    + "LINE 1: INSERT INTO \"data\".\"out_orders\" (\"k\") SELECT k FORM x\n"
                    + "                                                       ^\n",
                null,
                null));

    runner.executeStep(stepExecId, sqlStep, 37L, "TestPipeline", 1L, true);

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            errorCaptor.capture(),
            isNull(),
            any());
    assertThat(errorCaptor.getValue())
        .isEqualTo("SQL 실행 실패: syntax error at or near \"FORM\"")
        .doesNotContain("INSERT INTO");
  }

  /**
   * 별칭 없는 식 컬럼(?column?)은 TEMP 를 만들기 전에 SQL 문맥의 한국어 안내(AS 로 이름 붙이기)로 실패한다 — 데이터셋 공용 검증의 영어
   * 문구(Invalid name …)가 저장되지 않고, TEMP 생성도 시도하지 않는다.
   */
  @Test
  void 별칭_없는_식_컬럼은_AS_안내로_실패하고_TEMP_를_만들지_않는다() {
    Long stepExecId = 3621L;
    PipelineStepResponse sqlStep =
        stepResponse(3620L, "expr-step", "SQL", "SELECT 1", null, List.of());
    stubProbeColumns("?column?");

    String status = runner.executeStep(stepExecId, sqlStep, 38L, "TestPipeline", 1L, false);

    assertThat(status).isEqualTo("FAILED");
    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("FAILED"),
            isNull(),
            isNull(),
            errorCaptor.capture(),
            isNull(),
            any());
    assertThat(errorCaptor.getValue())
        .isEqualTo(
            "SQL 결과 컬럼 이름 '?column?' 은 쓸 수 없습니다. 계산 컬럼에는 AS 로 이름을 붙여 주세요(예: SELECT 1 AS value)."
                + " 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다.")
        .doesNotContain("Invalid name");
    verify(tempDatasetService, never()).createTempDataset(any(), any(), any(), any(), any(), any());
    verify(tempDatasetService, never()).deleteTempDataset(any());
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

  // ------------------------------------------------------------------ //
  // 협조적 취소(WD-153)
  // ------------------------------------------------------------------ //

  /** 스텝 사이 취소: 첫 스텝이 끝난 뒤 취소가 들어오면 둘째 스텝은 실행하지 않고, 실행은 CANCELLED·이벤트 없음(체인·알림 미발화). */
  @Test
  void executeAsync_스텝_사이_취소되면_남은_스텝을_실행하지_않고_CANCELLED_로_끝낸다() {
    Long pipelineId = 7L, executionId = 70L, userId = 10L;
    PipelineStepResponse s1 =
        stepResponse(701L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());
    PipelineStepResponse s2 =
        stepResponse(702L, "s2", "SQL", "INSERT INTO data.\"t\" VALUES (2)", null, List.of());
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("P"));
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");
    // 시작 직후 false, s1 시작 전 false, s1 안(SQL 직전) false, s2 시작 전 true
    when(executionRepository.isStopRequested(executionId)).thenReturn(false, false, false, true);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1, s2),
        Map.of(701L, List.of(), 702L, List.of()),
        Map.of(701L, 801L, 702L, 802L),
        userId,
        false);

    // s1 만 실행됐다
    verify(sqlExecutor, times(1)).execute(anyList(), anyString());
    verify(executionRepository)
        .updateStepExecution(eq(801L), eq("COMPLETED"), any(), any(), any(), any(), any());
    verify(executionRepository, never())
        .updateStepExecution(eq(802L), eq("RUNNING"), any(), any(), any(), any(), any());
    // 남은 스텝 SKIPPED + 실행 CANCELLED 는 한 트랜잭션 메서드 하나로 쓴다(WD-230)
    verify(executionRepository).cancelAndSkipPendingSteps(eq(executionId), any());
    verify(executionRepository, never())
        .updateExecutionStatus(eq(executionId), eq("COMPLETED"), any(), any());
    // 취소된 실행은 완료 이벤트를 발행하지 않는다 — PIPELINE_CHAIN(ANY 포함)·완료 알림이 발화하지 않는다.
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  // ------------------------------------------------------------------ //
  // 마감 경합·외부 마감(WD-230)
  // ------------------------------------------------------------------ //

  /** 최종 COMPLETED 쓰기가 지면(정리기가 먼저 마감) 완료 이벤트를 내지 않는다. */
  @Test
  void executeAsync_최종_쓰기가_지면_COMPLETED_이벤트를_내지_않는다() {
    Long pipelineId = 11L, executionId = 110L, userId = 10L;
    PipelineStepResponse s1 =
        stepResponse(1101L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");
    when(executionRepository.updateExecutionStatus(eq(executionId), eq("COMPLETED"), any(), any()))
        .thenReturn(false);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1),
        Map.of(1101L, List.of()),
        Map.of(1101L, 1201L),
        userId,
        false);

    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  /** 스텝 실패로 최종 FAILED 쓰기가 지면 이벤트를 내지 않는다. */
  @Test
  void executeAsync_최종_쓰기가_지면_FAILED_이벤트를_내지_않는다() {
    Long pipelineId = 12L, executionId = 120L, userId = 10L;
    PipelineStepResponse s1 = stepResponse(1102L, "s1", "SQL", "BAD SQL", null, List.of());
    when(executionRepository.updateExecutionStatus(eq(executionId), eq("FAILED"), any(), any()))
        .thenReturn(false);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1),
        Map.of(1102L, List.of()),
        Map.of(1102L, 1202L),
        userId,
        false);

    verify(executionRepository).updateExecutionStatus(eq(executionId), eq("FAILED"), any(), any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  /** 예외 경로의 FAILED 쓰기가 지면 이벤트를 내지 않는다. */
  @Test
  void executeAsync_예외_경로의_FAILED_쓰기가_지면_이벤트를_내지_않는다() {
    Long pipelineId = 13L, executionId = 130L, userId = 10L;
    doThrow(new RuntimeException("DB error"))
        .when(executionRepository)
        .updateExecutionStatus(eq(executionId), eq("RUNNING"), any(), any());
    when(executionRepository.updateExecutionStatus(
            eq(executionId), eq("FAILED"), any(), any(), eq("DB error")))
        .thenReturn(false);

    runner.executeAsync(pipelineId, executionId, List.of(), Map.of(), Map.of(), userId, false);

    verify(executionRepository)
        .updateExecutionStatus(eq(executionId), eq("FAILED"), any(), any(), eq("DB error"));
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  /** 취소 요청 없이 외부에서 마감되면(isStopRequested=true) 다음 체크포인트에서 남은 스텝을 실행하지 않는다. */
  @Test
  void executeAsync_외부_마감되면_다음_체크포인트에서_남은_스텝을_실행하지_않는다() {
    Long pipelineId = 14L, executionId = 140L, userId = 10L;
    PipelineStepResponse s1 =
        stepResponse(1103L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());
    PipelineStepResponse s2 =
        stepResponse(1104L, "s2", "SQL", "INSERT INTO data.\"t\" VALUES (2)", null, List.of());
    when(sqlExecutor.execute(anyList(), anyString())).thenReturn("ok");
    when(executionRepository.isStopRequested(executionId)).thenReturn(false, false, false, true);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1, s2),
        Map.of(1103L, List.of(), 1104L, List.of()),
        Map.of(1103L, 1203L, 1104L, 1204L),
        userId,
        false);

    verify(sqlExecutor, times(1)).execute(anyList(), anyString());
  }

  /** 정리기가 이미 FAILED 로 마감한 실행: 러너의 CANCELLED 쓰기는 지고, 이벤트도 내지 않으며, 정리기가 닫은 스텝 행을 취소 문구로 덮지 않는다. */
  @Test
  void executeAsync_외부_FAILED_마감에서_CANCELLED_쓰기가_지면_이벤트도_스텝_덮어쓰기도_없다() {
    Long pipelineId = 15L, executionId = 150L, userId = 10L;
    PipelineStepResponse s1 =
        stepResponse(1105L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());
    when(executionRepository.isStopRequested(executionId)).thenReturn(true);
    when(executionRepository.cancelAndSkipPendingSteps(eq(executionId), any())).thenReturn(false);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1),
        Map.of(1105L, List.of()),
        Map.of(1105L, 1205L),
        userId,
        false);

    verify(sqlExecutor, never()).execute(anyList(), anyString());
    verify(executionRepository).cancelAndSkipPendingSteps(eq(executionId), any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  /** 대기 중 취소: 러너가 시작하자마자 취소 요청을 보면 스텝을 하나도 돌리지 않는다. */
  @Test
  void executeAsync_대기_중_취소된_실행은_스텝을_하나도_실행하지_않는다() {
    Long pipelineId = 8L, executionId = 80L, userId = 10L;
    PipelineStepResponse s1 =
        stepResponse(711L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());
    when(executionRepository.isStopRequested(executionId)).thenReturn(true);

    runner.executeAsync(
        pipelineId,
        executionId,
        List.of(s1),
        Map.of(711L, List.of()),
        Map.of(711L, 811L),
        userId,
        false);

    verify(sqlExecutor, never()).execute(anyList(), anyString());
    verify(executionRepository, never())
        .updateStepExecution(anyLong(), eq("RUNNING"), any(), any(), any(), any(), any());
    verify(executionRepository).cancelAndSkipPendingSteps(eq(executionId), any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  /** SQL 스텝 안의 실행 직전 체크포인트: 취소되면 SQL 을 보내지 않고 스텝을 CANCELLED(0행·출력 그대로)로 기록한다. */
  @Test
  void executeStep_SQL_실행_직전_취소되면_보내지_않고_CANCELLED_로_기록한다() {
    PipelineStepResponse s1 =
        stepResponse(721L, "s1", "SQL", "INSERT INTO data.\"t\" VALUES (1)", null, List.of());

    String status = runner.executeStep(821L, s1, 9L, "P", 10L, false, () -> true);

    assertThat(status).isEqualTo("CANCELLED");
    verify(sqlExecutor, never()).execute(anyList(), anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(821L),
            eq("CANCELLED"),
            isNull(),
            isNull(),
            contains("출력 데이터는 바뀌지 않았습니다"),
            isNull(),
            any());
  }

  /**
   * 실행기 켠 REPLACE API_CALL: 실행기 호출이 끝난 직후 취소가 보이면 맞바꾸지 않고 임시 테이블을 버린다 — 원본이 남는다(호출 자체는 끝까지 돌았다).
   */
  @Test
  void 실행기_켜진_REPLACE_API_CALL_호출_직후_취소되면_맞바꾸지_않고_임시_테이블을_버린다() {
    Long stepId = 931L, stepExecId = 961L, dsId = 991L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_cancel");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    when(executorClient.executeApiCall(any()))
        .thenAnswer(
            inv -> {
              // 실행기 호출이 도는 동안 취소 요청이 들어왔다.
              cancelled.set(true);
              return new ExecutorClient.ApiCallExecuteResult(true, 7, 1, "ok", null, 20L);
            });

    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-cancel", "REPLACE"),
            95L,
            "P",
            1L,
            true,
            cancelled::get);

    assertThat(status).isEqualTo("CANCELLED");
    verify(executorClient, times(1)).executeApiCall(any());
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    verify(dataTableService).dropTempTable("ptmp_api_cancel", runTemp("ptmp_api_cancel"));
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("CANCELLED"),
            isNull(),
            isNull(),
            contains("기존 데이터를 그대로 유지"),
            isNull(),
            any());
  }

  /** 실행기 켠 APPEND API_CALL: 호출 직후 취소되면 실행기가 넣은 행은 남고 그 수가 스텝 기록에 남는다. */
  @Test
  void 실행기_켜진_APPEND_API_CALL_호출_직후_취소되면_넣은_행_수를_남긴다() {
    Long stepId = 932L, stepExecId = 962L, dsId = 992L;
    when(objectMapper.convertValue(any(), eq(ApiCallConfig.class))).thenReturn(minimalApiConfig());
    stubReusedTempDataset(stepId, dsId, "ptmp_api_cancel_append");
    when(columnRepository.findByDatasetId(dsId)).thenReturn(List.of(col("name", false)));
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    when(executorClient.executeApiCall(any()))
        .thenAnswer(
            inv -> {
              cancelled.set(true);
              return new ExecutorClient.ApiCallExecuteResult(true, 5, 1, "ok", null, 20L);
            });

    String status =
        runner.executeStep(
            stepExecId,
            apiStepNoOutput(stepId, "api-cancel-append", "APPEND"),
            95L,
            "P",
            1L,
            true,
            cancelled::get);

    assertThat(status).isEqualTo("CANCELLED");
    verify(dataTableService, never()).createTempTable(anyString());
    verify(executionRepository)
        .updateStepExecution(
            eq(stepExecId),
            eq("CANCELLED"),
            isNull(),
            isNull(),
            contains("취소 전까지 5행 적재됨"),
            isNull(),
            any());
  }

  /** 재생성 되돌리기 재료(WD-238) 픽스처 — 러너는 이 값을 그대로 확정·되돌리기에 넘기기만 한다. */
  private static DatasetService.PipelineTempRebuild rebuildHandle(Long datasetId, String table) {
    return new DatasetService.PipelineTempRebuild(
        datasetId, table, "rtmp_bak_x", 1L, List.of(), null);
  }

  /**
   * 재생성한 TEMP 가 스텝 결과에 맞게 정리됐는지(WD-238) — 실패·취소면 되돌리기만, 성공이면 보관본 버리기만 정확히 한 번. SQL 이 아닌 스텝(PYTHON·
   * API_CALL·AI_CLASSIFY)도 같은 ensureStepTemp 경로를 지나 이 처리를 받는다.
   */
  private void assertRebuildSettled(
      String status, DatasetService.PipelineTempRebuild rebuild, Long userId) {
    if ("COMPLETED".equals(status)) {
      verify(tempDatasetService).commitRebuild(rebuild);
      verify(tempDatasetService, never()).rollbackRebuild(any(), anyLong());
    } else {
      verify(tempDatasetService).rollbackRebuild(rebuild, userId);
      verify(tempDatasetService, never()).commitRebuild(any());
    }
  }
}
