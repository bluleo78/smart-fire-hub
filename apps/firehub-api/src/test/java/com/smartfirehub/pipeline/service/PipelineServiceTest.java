package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.UnsafePythonScriptException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PipelineServiceTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;

  @Autowired private DatasetService datasetService;

  @Autowired private DSLContext dsl;

  /** 증분 책갈피 이월 검증용 — 서비스가 아니라 리포지토리로 직접 읽어 "무엇이 DB 에 남았는가"를 본다. */
  @Autowired private com.smartfirehub.pipeline.repository.PipelineStepRepository stepRepository;

  private Long testUserId;
  private Long inputDatasetId;
  private Long outputDatasetId;
  private Long pkOutputDatasetId;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Test User")
            .set(USER.EMAIL, "test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Create input dataset
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));

    DatasetDetailResponse inputDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Input Dataset", "input_dataset", null, null, "TABLE", "SOURCE", columns, null),
            testUserId);
    inputDatasetId = inputDataset.id();

    // Create output dataset
    DatasetDetailResponse outputDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Output Dataset", "output_dataset", null, null, "TABLE", "DERIVED", columns, null),
            testUserId);
    outputDatasetId = outputDataset.id();

    // MERGE 로드 전략 검증용 — PK 컬럼이 있는 출력 데이터셋(ux_<table>_pk 유니크 인덱스가 생긴다).
    List<DatasetColumnRequest> pkColumns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    DatasetDetailResponse pkOutputDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "PK Output Dataset", "pk_output_dataset", null, null, "TABLE", "DERIVED",
                pkColumns, null),
            testUserId);
    pkOutputDatasetId = pkOutputDataset.id();
  }

  @Test
  void createPipeline_withStepsAndDependencies_success() {
    // Given
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "First step",
                "SQL",
                "SELECT * FROM data.src_table",
                outputDatasetId,
                List.of(inputDatasetId),
                null),
            new PipelineStepRequest(
                "step2",
                "Second step",
                "SQL",
                "SELECT * FROM data.dst_table WHERE id = 1",
                inputDatasetId,
                List.of(outputDatasetId),
                List.of("step1")));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Test Pipeline", "Test pipeline description", steps);

    // When
    PipelineDetailResponse response = pipelineService.createPipeline(request, testUserId);

    // Then
    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("Test Pipeline");
    assertThat(response.steps()).hasSize(2);

    // Verify in DB
    Long pipelineCount =
        dsl.selectCount()
            .from(PIPELINE)
            .where(PIPELINE.ID.eq(response.id()))
            .fetchOne(0, Long.class);
    assertThat(pipelineCount).isEqualTo(1);

    Long stepCount =
        dsl.selectCount()
            .from(PIPELINE_STEP)
            .where(PIPELINE_STEP.PIPELINE_ID.eq(response.id()))
            .fetchOne(0, Long.class);
    assertThat(stepCount).isEqualTo(2);

    // Verify dependencies
    Long depCount = dsl.selectCount().from(PIPELINE_STEP_DEPENDENCY).fetchOne(0, Long.class);
    assertThat(depCount).isGreaterThanOrEqualTo(1);
  }

  // SQL 안전 검증 통합 회귀 (#136)

  @Test
  void createPipeline_sqlStepWithPublicSchemaReference_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "unsafe step",
                "SQL",
                "SELECT * FROM public.\"user\"",
                outputDatasetId,
                null,
                null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Unsafe Pipeline Schema", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void createPipeline_sqlStepWithMultiStatement_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "multi stmt step",
                "SQL",
                "SELECT 1; SELECT 2",
                outputDatasetId,
                null,
                null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Unsafe Pipeline Multi", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("멀티");
  }

  // UI가 안내하는 "스텝 참조" 문법({{#N}})을 FROM절에 그대로 써도 저장이 성공해야 한다 (#643).
  // {{#N}}은 유효한 SQL 토큰이 아니라서, 저장 시점 검증이 치환 없이 원본을 그대로 파싱하면
  // 항상 파싱 실패로 거부된다 — 실행 시점(PipelineAsyncRunner#resolveStepReferences)과 동일하게
  // 먼저 치환한 뒤 검증해야 한다.
  @Test
  void createPipeline_sqlStepWithStepReferenceInFromClause_succeeds() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "First step", "SQL", "SELECT 1 as test_col", null, null, null),
            new PipelineStepRequest(
                "step2",
                "Second step",
                "SQL",
                "SELECT * FROM {{#1}} LIMIT 1",
                null,
                null,
                List.of("step1")));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Step Reference Pipeline", "test", steps);

    assertThatCode(() -> pipelineService.createPipeline(request, testUserId))
        .doesNotThrowAnyException();
  }

  // {{#N}} 치환이 검증을 완전히 우회하는 통로가 되면 안 된다 — 치환 후에도 여전히 여러 statement면
  // 거부돼야 한다 (더미 치환으로 구조적 검증 자체가 무력화되지 않는지 확인).
  @Test
  void createPipeline_sqlStepWithStepReferenceAndMultiStatement_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "First step", "SQL", "SELECT 1 as test_col", null, null, null),
            new PipelineStepRequest(
                "step2",
                "Second step",
                "SQL",
                "SELECT * FROM {{#1}}; SELECT 2",
                null,
                null,
                List.of("step1")));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Step Reference Multi Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("멀티");
  }

  // --- MERGE 로드 전략 저장 시점 검증 (Task 5) ---

  @Test
  void createPipeline_mergeStrategyOnNonSqlStep_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "python with merge",
                "PYTHON",
                "print('hi')",
                pkOutputDatasetId,
                null,
                null,
                "MERGE"));

    CreatePipelineRequest request = new CreatePipelineRequest("Merge Non-SQL Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SQL");
  }

  @Test
  void createPipeline_mergeStrategyWithoutOutputDataset_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "merge without output",
                "SQL",
                "SELECT 1 as code",
                null,
                null,
                null,
                "MERGE"));

    CreatePipelineRequest request = new CreatePipelineRequest("Merge No Output Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("출력 데이터셋");
  }

  @Test
  void createPipeline_mergeStrategyWithoutPkOnOutputDataset_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "merge without pk",
                "SQL",
                "SELECT * FROM data.src_table",
                outputDatasetId, // setUp의 outputDataset은 PK 컬럼이 없다
                null,
                null,
                "MERGE"));

    CreatePipelineRequest request = new CreatePipelineRequest("Merge No PK Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PK");
  }

  @Test
  void createPipeline_unknownLoadStrategy_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "unknown strategy",
                "SQL",
                "SELECT * FROM data.src_table",
                outputDatasetId,
                null,
                null,
                "FOO"));

    CreatePipelineRequest request = new CreatePipelineRequest("Unknown Strategy Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FOO");
  }

  @Test
  void createPipeline_mergeStrategyWithPk_succeeds() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "merge with pk",
                "SQL",
                "SELECT * FROM data.src_table",
                pkOutputDatasetId,
                null,
                null,
                "MERGE"));

    CreatePipelineRequest request = new CreatePipelineRequest("Merge With PK Pipeline", "test", steps);

    assertThatCode(() -> pipelineService.createPipeline(request, testUserId))
        .doesNotThrowAnyException();
  }

  /**
   * Fix round 1, must 3 — 사용자가 직접 쓴 UPDATE/INSERT/DELETE 에 MERGE 를 걸면 실행 시점의 출력
   * 비우기 판단과 SELECT 래핑 두 블록이 모두 SELECT 자동 적재 전용이라 스킵되어, MERGE 가 조용히
   * 아무 의미도 없는 채로 사용자 SQL 이 그대로 실행된다(무동작). PK 없음과 같은 부류의 silent
   * degradation 이므로 저장 시점에 막는다.
   */
  @Test
  void createPipeline_mergeStrategyOnNonSelectSqlStep_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "merge on update",
                "SQL",
                "UPDATE data.pk_output_dataset SET name = 'x' WHERE code = 'a'",
                pkOutputDatasetId,
                null,
                null,
                "MERGE"));

    CreatePipelineRequest request = new CreatePipelineRequest("Merge On Update Pipeline", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT");
  }

  // PYTHON 스텝 escalation 코드 차단 — 저장 경로 end-to-end 검증 (#270).
  // validate() 호출이 saveSteps 에 실제로 배선되어 있는지 보장한다(단위테스트만으로는 배선 미검증).
  @Test
  void createPipeline_pythonStepWithSubprocess_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "escalation step",
                "PYTHON",
                "import subprocess\nsubprocess.run(['cat', '/etc/passwd'])",
                outputDatasetId,
                null,
                null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Unsafe Pipeline Python", "test", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(UnsafePythonScriptException.class);
  }

  // 정당한 psycopg2/DB_URL 입력 조회 스텝은 저장이 허용되어야 한다 (false-positive 가드 end-to-end).
  @Test
  void createPipeline_pythonStepWithLegitimatePsycopg2_succeeds() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "legit etl step",
                "PYTHON",
                "import os, json, psycopg2\n"
                    + "conn = psycopg2.connect(os.environ['DB_URL'])\n"
                    + "print(json.dumps([]))",
                outputDatasetId,
                null,
                null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Legit Pipeline Python", "test", steps);

    assertThatCode(() -> pipelineService.createPipeline(request, testUserId))
        .doesNotThrowAnyException();
  }

  @Test
  void createPipeline_withCyclicDependency_throwsException() {
    // Given
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "Step 1", "SQL", "SELECT 1", outputDatasetId, null, List.of("step2")),
            new PipelineStepRequest(
                "step2", "Step 2", "SQL", "SELECT 2", inputDatasetId, null, List.of("step1")));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Cyclic Pipeline", "Should fail", steps);

    // When/Then
    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(CyclicDependencyException.class);
  }

  @Test
  void getPipelines_returnsPaginatedList() {
    // Given
    CreatePipelineRequest request1 =
        new CreatePipelineRequest("Pipeline 1", "Description 1", List.of());
    CreatePipelineRequest request2 =
        new CreatePipelineRequest("Pipeline 2", "Description 2", List.of());

    pipelineService.createPipeline(request1, testUserId);
    pipelineService.createPipeline(request2, testUserId);

    // When
    PageResponse<PipelineResponse> response = pipelineService.getPipelines(0, 10);

    // Then
    assertThat(response.content()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(response.totalElements()).isGreaterThanOrEqualTo(2);
  }

  // #510 회귀 방지: 목록 조회 응답의 triggerCount가 실제 활성 트리거 개수를 반영하는지 검증한다.
  // 비활성(is_enabled=false) 트리거는 카운트에서 제외되어야 한다.
  @Test
  void getPipelines_reflectsActiveTriggerCount() {
    // Given: 트리거가 없는 파이프라인, 활성 2개 + 비활성 1개를 가진 파이프라인
    PipelineDetailResponse noTriggerPipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("No Trigger Pipeline", "no triggers", List.of()),
            testUserId);
    PipelineDetailResponse withTriggerPipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("With Trigger Pipeline", "has triggers", List.of()),
            testUserId);

    dsl.insertInto(PIPELINE_TRIGGER)
        .set(PIPELINE_TRIGGER.PIPELINE_ID, withTriggerPipeline.id())
        .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "WEBHOOK")
        .set(PIPELINE_TRIGGER.NAME, "활성 웹훅 1")
        .set(PIPELINE_TRIGGER.IS_ENABLED, true)
        .set(PIPELINE_TRIGGER.CREATED_BY, testUserId)
        .execute();
    dsl.insertInto(PIPELINE_TRIGGER)
        .set(PIPELINE_TRIGGER.PIPELINE_ID, withTriggerPipeline.id())
        .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "SCHEDULE")
        .set(PIPELINE_TRIGGER.NAME, "활성 스케줄")
        .set(PIPELINE_TRIGGER.IS_ENABLED, true)
        .set(PIPELINE_TRIGGER.CREATED_BY, testUserId)
        .execute();
    dsl.insertInto(PIPELINE_TRIGGER)
        .set(PIPELINE_TRIGGER.PIPELINE_ID, withTriggerPipeline.id())
        .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "WEBHOOK")
        .set(PIPELINE_TRIGGER.NAME, "비활성 웹훅")
        .set(PIPELINE_TRIGGER.IS_ENABLED, false)
        .set(PIPELINE_TRIGGER.CREATED_BY, testUserId)
        .execute();

    // When
    PageResponse<PipelineResponse> response = pipelineService.getPipelines(0, 50);

    // Then
    PipelineResponse noTriggerResult =
        response.content().stream()
            .filter(p -> p.id().equals(noTriggerPipeline.id()))
            .findFirst()
            .orElseThrow();
    PipelineResponse withTriggerResult =
        response.content().stream()
            .filter(p -> p.id().equals(withTriggerPipeline.id()))
            .findFirst()
            .orElseThrow();

    assertThat(noTriggerResult.triggerCount()).isZero();
    assertThat(withTriggerResult.triggerCount()).isEqualTo(2);
  }

  @Test
  void getPipelineById_returnsDetailWithSteps() {
    // Given
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "Step 1", "SQL", "SELECT 1", outputDatasetId, null, null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Test Pipeline", "Description", steps);

    PipelineDetailResponse created = pipelineService.createPipeline(request, testUserId);

    // When
    PipelineDetailResponse retrieved = pipelineService.getPipelineById(created.id());

    // Then
    assertThat(retrieved.id()).isEqualTo(created.id());
    assertThat(retrieved.name()).isEqualTo("Test Pipeline");
    assertThat(retrieved.steps()).hasSize(1);
    assertThat(retrieved.steps().get(0).name()).isEqualTo("step1");
  }

  @Test
  void updatePipeline_success() {
    // Given
    CreatePipelineRequest createRequest =
        new CreatePipelineRequest("Original Name", "Original Description", List.of());

    PipelineDetailResponse created = pipelineService.createPipeline(createRequest, testUserId);

    UpdatePipelineRequest updateRequest =
        new UpdatePipelineRequest("Updated Name", "Updated Description", false, null);

    // When
    pipelineService.updatePipeline(created.id(), updateRequest, testUserId);

    // Then
    PipelineDetailResponse updated = pipelineService.getPipelineById(created.id());
    assertThat(updated.name()).isEqualTo("Updated Name");
    assertThat(updated.description()).isEqualTo("Updated Description");
    assertThat(updated.isActive()).isFalse();
  }

  @Test
  void updatePipeline_withNewSteps_replacesSteps() {
    // Given
    List<PipelineStepRequest> originalSteps =
        List.of(
            new PipelineStepRequest(
                "step1", "Step 1", "SQL", "SELECT 1", outputDatasetId, null, null));

    CreatePipelineRequest createRequest =
        new CreatePipelineRequest("Test Pipeline", "Description", originalSteps);

    PipelineDetailResponse created = pipelineService.createPipeline(createRequest, testUserId);

    List<PipelineStepRequest> newSteps =
        List.of(
            new PipelineStepRequest(
                "step_new", "New Step", "SQL", "SELECT 2", inputDatasetId, null, null));

    UpdatePipelineRequest updateRequest =
        new UpdatePipelineRequest("Test Pipeline", "Description", true, newSteps);

    // When
    pipelineService.updatePipeline(created.id(), updateRequest, testUserId);

    // Then
    PipelineDetailResponse updated = pipelineService.getPipelineById(created.id());
    assertThat(updated.steps()).hasSize(1);
    assertThat(updated.steps().get(0).name()).isEqualTo("step_new");
  }

  @Test
  void deletePipeline_removesAllData() {
    // Given
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "Step 1", "SQL", "SELECT 1", outputDatasetId, null, null));

    CreatePipelineRequest request = new CreatePipelineRequest("To Delete", "Description", steps);

    PipelineDetailResponse created = pipelineService.createPipeline(request, testUserId);
    Long pipelineId = created.id();

    // When
    pipelineService.deletePipeline(pipelineId);

    // Then
    assertThatThrownBy(() -> pipelineService.getPipelineById(pipelineId))
        .isInstanceOf(PipelineNotFoundException.class);

    // Verify steps deleted
    Long stepCount =
        dsl.selectCount()
            .from(PIPELINE_STEP)
            .where(PIPELINE_STEP.PIPELINE_ID.eq(pipelineId))
            .fetchOne(0, Long.class);
    assertThat(stepCount).isEqualTo(0);
  }

  @Test
  void executePipeline_createsExecution() {
    // Given
    CreatePipelineRequest request =
        new CreatePipelineRequest("Test Pipeline", "Description", List.of());

    PipelineDetailResponse pipeline = pipelineService.createPipeline(request, testUserId);

    // When
    PipelineExecutionResponse execution =
        pipelineService.executePipeline(pipeline.id(), testUserId);

    // Then
    assertThat(execution.id()).isNotNull();
    assertThat(execution.pipelineId()).isEqualTo(pipeline.id());
    assertThat(execution.status()).isEqualTo("PENDING");

    // Verify execution record created
    Long executionCount =
        dsl.selectCount()
            .from(PIPELINE_EXECUTION)
            .where(PIPELINE_EXECUTION.ID.eq(execution.id()))
            .fetchOne(0, Long.class);
    assertThat(executionCount).isEqualTo(1);
  }

  @Test
  void getExecutionsByPipelineId_returnsExecutions() {
    // Given
    CreatePipelineRequest request =
        new CreatePipelineRequest("Test Pipeline", "Description", List.of());

    PipelineDetailResponse pipeline = pipelineService.createPipeline(request, testUserId);
    PipelineExecutionResponse execution =
        pipelineService.executePipeline(pipeline.id(), testUserId);

    // When
    List<PipelineExecutionResponse> executions =
        pipelineService.getExecutionsByPipelineId(pipeline.id());

    // Then
    assertThat(executions).isNotEmpty();
    assertThat(executions).anyMatch(exec -> exec.id().equals(execution.id()));
  }

  @Test
  void createPipeline_withValidAiClassifyStep_success() {
    // Given
    Map<String, Object> aiConfig =
        Map.of(
            "prompt", "Classify the sentiment of the text",
            "outputColumns", List.of(Map.of("name", "sentiment", "type", "TEXT")),
            "inputColumns", List.of("col1"));

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI classify step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request =
        new CreatePipelineRequest("AI Pipeline", "AI pipeline description", steps);

    // When
    PipelineDetailResponse response = pipelineService.createPipeline(request, testUserId);

    // Then
    assertThat(response.id()).isNotNull();
    assertThat(response.steps()).hasSize(1);
    assertThat(response.steps().get(0).scriptType()).isEqualTo("AI_CLASSIFY");
    assertThat(response.steps().get(0).aiConfig()).isNotNull();
    assertThat(response.steps().get(0).aiConfig()).containsKey("prompt");
  }

  @Test
  void createPipeline_aiClassifyStep_withoutOutputDatasetId_succeeds() {
    // AI_CLASSIFY는 outputColumns로 임시 데이터셋을 자동 생성하므로 outputDatasetId 없이도 생성 가능
    Map<String, Object> aiConfig =
        Map.of(
            "prompt",
            "Classify the sentiment",
            "outputColumns",
            List.of(Map.of("name", "sentiment", "type", "TEXT")));

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                null,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    PipelineDetailResponse response = pipelineService.createPipeline(request, testUserId);
    assertThat(response.id()).isNotNull();
    assertThat(response.steps()).hasSize(1);
    assertThat(response.steps().get(0).scriptType()).isEqualTo("AI_CLASSIFY");
  }

  @Test
  void createPipeline_aiClassifyStep_missingAiConfig_throwsException() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                null,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aiConfig");
  }

  @Test
  void createPipeline_aiClassifyStep_missingPrompt_throwsException() {
    Map<String, Object> aiConfig =
        Map.of("outputColumns", List.of(Map.of("name", "sentiment", "type", "TEXT")));

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prompt");
  }

  @Test
  void createPipeline_aiClassifyStep_missingOutputColumns_throwsException() {
    Map<String, Object> aiConfig = Map.of("prompt", "Classify the sentiment");

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outputColumn");
  }

  @Test
  void createPipeline_aiClassifyStep_invalidOutputColumnType_throwsException() {
    Map<String, Object> aiConfig =
        Map.of(
            "prompt",
            "Classify the sentiment",
            "outputColumns",
            List.of(Map.of("name", "sentiment", "type", "INVALID_TYPE")));

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outputColumn type");
  }

  @Test
  void createPipeline_aiClassifyStep_invalidBatchSize_throwsException() {
    Map<String, Object> aiConfig =
        Map.of(
            "prompt",
            "Classify the sentiment",
            "outputColumns",
            List.of(Map.of("name", "sentiment", "type", "TEXT")),
            "batchSize",
            200); // out of range

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batchSize");
  }

  @Test
  void createPipeline_aiClassifyStep_invalidOnError_throwsException() {
    Map<String, Object> aiConfig =
        Map.of(
            "prompt", "Classify the sentiment",
            "outputColumns", List.of(Map.of("name", "sentiment", "type", "TEXT")),
            "onError", "INVALID_VALUE");

    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "ai_step",
                "AI step",
                "AI_CLASSIFY",
                null,
                outputDatasetId,
                List.of(inputDatasetId),
                null,
                "REPLACE",
                null,
                aiConfig,
                null,
                null));

    CreatePipelineRequest request = new CreatePipelineRequest("AI Pipeline", "Description", steps);

    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("onError");
  }

  /**
   * 회귀 테스트 (#188): execution이 요청한 pipelineId에 속하지 않으면 PipelineNotFoundException(404) 으로 거부해야 한다 —
   * 그렇지 않으면 다른 파이프라인 실행 상세가 노출된다.
   */
  @Test
  void getExecutionById_otherPipelinesExecution_throwsNotFound() {
    // Given: 두 개의 파이프라인 + 첫 번째 파이프라인에서만 실행 생성
    PipelineDetailResponse pipelineA =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Pipeline A", "desc", List.of()), testUserId);
    PipelineDetailResponse pipelineB =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Pipeline B", "desc", List.of()), testUserId);

    PipelineExecutionResponse executionOfA =
        pipelineService.executePipeline(pipelineA.id(), testUserId);

    // When/Then: pipelineB 컨텍스트로 executionOfA 조회 시 404
    assertThatThrownBy(() -> pipelineService.getExecutionById(pipelineB.id(), executionOfA.id()))
        .isInstanceOf(PipelineNotFoundException.class)
        .hasMessageContaining("Execution not found");

    // Sanity: 정상 컨텍스트(pipelineA)에서는 정상 조회되어야 함
    ExecutionDetailResponse detail =
        pipelineService.getExecutionById(pipelineA.id(), executionOfA.id());
    assertThat(detail.id()).isEqualTo(executionOfA.id());
    assertThat(detail.pipelineId()).isEqualTo(pipelineA.id());
  }

  /** 회귀 테스트 (#188): 존재하지 않는 execution도 동일하게 PipelineNotFoundException으로 통일. */
  @Test
  void getExecutionById_nonexistentExecution_throwsNotFound() {
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Pipeline X", "desc", List.of()), testUserId);

    assertThatThrownBy(() -> pipelineService.getExecutionById(pipeline.id(), 999_999_999L))
        .isInstanceOf(PipelineNotFoundException.class)
        .hasMessageContaining("Execution not found");
  }

  // --- 증분 처리({{last_run_at}}) 저장 시점 검증 + 책갈피 이월 (Task 6) ---

  /** 증분 SELECT SQL. 저장 시점 SQL 가드(AST 파싱)를 통과해야 한다 — 플레이스홀더는 리터럴로 치환돼 검증된다. */
  private static final String INCREMENTAL_SQL =
      "SELECT code, name FROM data.src_table WHERE _updated_at >= {{last_run_at}}";

  @Test
  void createPipeline_incrementalWithReplace_isRejected() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1",
                "incremental + replace",
                "SQL",
                INCREMENTAL_SQL,
                pkOutputDatasetId,
                null,
                null,
                "REPLACE"));

    CreatePipelineRequest request =
        new CreatePipelineRequest("Incremental Replace Pipeline", "test", steps);

    // REPLACE 는 매 실행 출력을 비우므로 증분과 합치면 출력에 변경분만 남는다 — MERGE 를 안내해야 한다.
    assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MERGE");
  }

  @Test
  void createPipeline_incrementalWithAppend_isSaved() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "incremental + append", "SQL", INCREMENTAL_SQL, outputDatasetId, null,
                null, "APPEND"));

    // 플레이스홀더가 SQL 가드 파싱을 깨지 않아야 한다(치환 없이 파싱하면 항상 저장 불가).
    assertThatCode(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest("Incremental Append Pipeline", "test", steps),
                    testUserId))
        .doesNotThrowAnyException();
  }

  @Test
  void createPipeline_incrementalWithMerge_isSaved() {
    List<PipelineStepRequest> steps =
        List.of(
            new PipelineStepRequest(
                "step1", "incremental + merge", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null,
                null, "MERGE"));

    assertThatCode(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest("Incremental Merge Pipeline", "test", steps),
                    testUserId))
        .doesNotThrowAnyException();
  }

  /**
   * 재저장 이월 — {@code updatePipeline} 은 스텝을 전부 지우고 다시 넣어 스텝 id 가 바뀌므로, 책갈피는
   * 이름을 키로 이월돼야 한다. 이월하지 않으면 파이프라인을 저장할 때마다 전체를 다시 읽는다.
   */
  @Test
  void updatePipeline_sameNameSameOutput_carriesCursorOver() {
    java.time.OffsetDateTime bookmark =
        java.time.OffsetDateTime.parse("2026-09-19T01:02:03.123456Z");

    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Cursor Carry Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);

    Long oldStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    stepRepository.advanceCursor(oldStepId, bookmark, true);
    stepRepository.setFullRebuildPending(oldStepId, true);

    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "Cursor Carry Pipeline",
            "test",
            null,
            List.of(
                new PipelineStepRequest(
                    "stepA", "a-edited", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                    "MERGE"))),
        testUserId);

    Long newStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    assertThat(newStepId).as("스텝은 삭제·재생성되므로 id 가 바뀐다").isNotEqualTo(oldStepId);

    StepCursor cursor = stepRepository.findCursor(newStepId).orElseThrow();
    assertThat(cursor.lastRunAt()).isEqualTo(bookmark);
    assertThat(cursor.fullRebuildPending()).isTrue();
  }

  /**
   * 출력 데이터셋이 바뀌면 이월하지 않는다 — 새 출력은 과거 실행분을 받은 적이 없으므로, 책갈피만
   * 이어받으면 그 구간이 영원히 비게 된다(조용한 데이터 누락).
   */
  @Test
  void updatePipeline_differentOutputDataset_resetsCursor() {
    java.time.OffsetDateTime bookmark = java.time.OffsetDateTime.parse("2026-09-19T01:02:03Z");

    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Cursor Reset Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);

    Long oldStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    stepRepository.advanceCursor(oldStepId, bookmark, true);
    stepRepository.setFullRebuildPending(oldStepId, true);

    // 같은 이름, 다른 출력 데이터셋(PK 없음 → APPEND 로 바꾼다).
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "Cursor Reset Pipeline",
            "test",
            null,
            List.of(
                new PipelineStepRequest(
                    "stepA", "a-moved", "SQL", INCREMENTAL_SQL, outputDatasetId, null, null,
                    "APPEND"))),
        testUserId);

    Long newStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    StepCursor cursor = stepRepository.findCursor(newStepId).orElseThrow();
    assertThat(cursor.lastRunAt()).as("출력이 바뀌면 책갈피를 이월하지 않는다").isNull();
    assertThat(cursor.fullRebuildPending()).isFalse();
  }

  /**
   * 출력이 null(임시 데이터셋 자동 생성)인 스텝은 이월하지 않는다 — {@code null == null} 을 "같은
   * 출력"으로 보면 안 된다. 임시 데이터셋은 {@code source_pipeline_step_id} = 스텝 id 로 묶여 있어,
   * 재저장으로 id 가 바뀌면 러너가 빈 임시 데이터셋을 새로 만든다. 거기에 책갈피만 이어받으면 이전
   * 실행분이 통째로 빠진 채 변경분만 쌓인다.
   */
  @Test
  void updatePipeline_nullOutputDataset_doesNotCarryCursorOver() {
    java.time.OffsetDateTime bookmark = java.time.OffsetDateTime.parse("2026-09-19T01:02:03Z");

    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Cursor Null Output Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, null, null, null, "APPEND"))),
            testUserId);

    Long oldStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    stepRepository.advanceCursor(oldStepId, bookmark, true);

    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "Cursor Null Output Pipeline",
            "test",
            null,
            List.of(
                new PipelineStepRequest(
                    "stepA", "a-edited", "SQL", INCREMENTAL_SQL, null, null, null, "APPEND"))),
        testUserId);

    Long newStepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();
    StepCursor cursor = stepRepository.findCursor(newStepId).orElseThrow();
    assertThat(cursor.lastRunAt()).as("출력이 null 이면 이월하지 않는다").isNull();
    assertThat(cursor.fullRebuildPending()).isFalse();
  }

  /**
   * 책갈피 이월이 <b>스텝 이름을 키로 쓸 수 있는 근거</b>를 실측으로 고정한다.
   *
   * <p>{@code updatePipeline} 은 스텝을 전부 지우고 다시 넣어 id 가 바뀌므로 이월 키가 이름이고,
   * {@code restoreCursor} 의 {@code WHERE pipeline_id = ? AND name = ?} 가 한 행만 맞힌다는 전제도
   * 같은 곳에서 온다 — {@code pipeline_step} 의 {@code UNIQUE (pipeline_id, name)}(V3:24) 이다.
   * 그 제약이 사라지면 이월 설계 전체가 재검토 대상이므로, 여기서 제약의 존재 자체를 단언한다.
   * ({@code findCursorsByPipelineId} 는 그래도 {@code fetchGroups} 로 중복을 견디게 해 두었다 —
   * 제약이 사라졌을 때 "편집 불가"가 아니라 "이월 안 함"으로 degrade 시키기 위해서다.)
   */
  @Test
  void pipelineStepName_isUniquePerPipeline() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Step Name Unique Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);

    assertThatThrownBy(
            () ->
                dsl.insertInto(PIPELINE_STEP)
                    .set(PIPELINE_STEP.PIPELINE_ID, created.id())
                    .set(PIPELINE_STEP.NAME, "stepA")
                    .set(PIPELINE_STEP.SCRIPT_TYPE, "SQL")
                    .set(PIPELINE_STEP.SCRIPT_CONTENT, INCREMENTAL_SQL)
                    .set(PIPELINE_STEP.OUTPUT_DATASET_ID, pkOutputDatasetId)
                    .set(PIPELINE_STEP.STEP_ORDER, 1)
                    .set(PIPELINE_STEP.LOAD_STRATEGY, "MERGE")
                    .execute())
        .as("이름 이월 설계가 기대는 제약 — 같은 파이프라인에 동명 스텝은 존재할 수 없다")
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  // --- 상세 응답의 증분 경고 + 전체 재생성/재읽기 예약 API (Task 7) ---

  /** MERGE + GROUP BY(집계) 증분 SQL. 경고 대상 SQL의 대표 형태다. */
  private static final String INCREMENTAL_AGGREGATE_SQL =
      "SELECT code, count(*) FROM data.src_table WHERE _updated_at >= {{last_run_at}} GROUP BY code";

  @Test
  void 상세조회_APPEND와_증분플레이스홀더를_함께쓰면_중복경고가_붙는다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Append Incremental Warning Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, outputDatasetId, null, null,
                        "APPEND"))),
            testUserId);

    PipelineDetailResponse detail = pipelineService.getPipelineById(created.id());

    assertThat(detail.steps()).hasSize(1);
    assertThat(detail.steps().get(0).warnings())
        .anySatisfy(w -> assertThat(w).contains("중복"));
  }

  @Test
  void 상세조회_MERGE와_GROUP_BY_증분_SQL은_집계경고가_붙는다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Merge Aggregate Warning Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_AGGREGATE_SQL, pkOutputDatasetId, null,
                        null, "MERGE"))),
            testUserId);

    PipelineDetailResponse detail = pipelineService.getPipelineById(created.id());

    assertThat(detail.steps()).hasSize(1);
    assertThat(detail.steps().get(0).warnings()).anySatisfy(w -> assertThat(w).contains("집계"));
  }

  /**
   * 코드리뷰 MEDIUM — {@code {{#N}}} 스텝 참조를 쓰는 증분 스텝(이전 스텝 출력을 읽는, 증분의 가장 흔한
   * 모양)에도 집계 경고가 나와야 한다. 예전에는 원문을 그대로 JSqlParser 에 넘겨서 {@code {{#1}}} 이
   * 문법 오류를 내고, {@code incrementalWarnings} 가 그 예외를 삼켜 <b>항상</b> 빈 목록을 돌려줬다 —
   * 이 형태에서는 경고가 영원히 안 나왔다.
   */
  @Test
  void 상세조회_스텝참조를_쓰는_집계_증분_SQL도_집계경고가_붙는다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Step Ref Aggregate Warning Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", "SELECT 1 as test_col", null, null, null),
                    new PipelineStepRequest(
                        "stepB",
                        "b",
                        "SQL",
                        "SELECT code, count(*) AS n FROM {{#1}}"
                            + " WHERE _updated_at >= {{last_run_at}} GROUP BY code",
                        outputDatasetId,
                        null,
                        List.of("stepA"),
                        "APPEND"))),
            testUserId);

    PipelineDetailResponse detail = pipelineService.getPipelineById(created.id());

    assertThat(detail.steps()).hasSize(2);
    assertThat(detail.steps().get(1).warnings()).anySatisfy(w -> assertThat(w).contains("집계"));
  }

  /**
   * 코드리뷰 LOW — 로드 전략 비교의 대소문자 비대칭. 저장 API({@code LoadStrategy.valueOf})는 소문자를
   * 거부하지만 컬럼에는 체크 제약이 없어 레거시/외부 경로로 들어온 소문자 행이 남아 있을 수 있다.
   * 실행기({@code PipelineAsyncRunner})는 {@code equalsIgnoreCase} 라 그 행을 APPEND 로 실행하므로,
   * 경고만 {@code equals} 로 판단하면 정확히 그 행에서만 "중복" 경고가 조용히 빠진다.
   */
  @Test
  void 상세조회_소문자_레거시_append_행에도_중복경고가_붙는다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Lowercase Append Warning Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, outputDatasetId, null, null,
                        "APPEND"))),
            testUserId);
    // 저장 API 는 소문자를 거부하므로(의도된 요청 검증) 레거시 행은 DB 에 직접 써서 재현한다.
    dsl.update(PIPELINE_STEP)
        .set(PIPELINE_STEP.LOAD_STRATEGY, "append")
        .where(PIPELINE_STEP.PIPELINE_ID.eq(created.id()))
        .execute();

    PipelineDetailResponse detail = pipelineService.getPipelineById(created.id());

    assertThat(detail.steps().get(0).loadStrategy()).isEqualTo("append");
    assertThat(detail.steps().get(0).warnings()).anySatisfy(w -> assertThat(w).contains("중복"));
  }

  /** 비어 있음 대조 — 증분이지만 행 단위(집계 없음) MERGE 스텝은 경고가 없다(공허하지 않은 경고기 증명). */
  @Test
  void 상세조회_행단위_MERGE_증분_SQL은_경고가_없다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Merge Row Level No Warning Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);

    PipelineDetailResponse detail = pipelineService.getPipelineById(created.id());

    assertThat(detail.steps()).hasSize(1);
    assertThat(detail.steps().get(0).warnings()).isEmpty();
  }

  @Test
  void 전체재생성_예약과_취소가_책갈피_상태를_바꾼다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Full Rebuild Reserve Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);
    Long stepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();

    pipelineService.setFullRebuildPending(created.id(), stepId, true);
    assertThat(stepRepository.findCursor(stepId).orElseThrow().fullRebuildPending())
        .as("예약 후 DB 상태가 실제로 바뀌어야 한다")
        .isTrue();

    pipelineService.setFullRebuildPending(created.id(), stepId, false);
    assertThat(stepRepository.findCursor(stepId).orElseThrow().fullRebuildPending())
        .as("취소 후 DB 상태가 실제로 바뀌어야 한다")
        .isFalse();
  }

  @Test
  void 전체재생성_예약_다른파이프라인의_stepId는_NotFound다() {
    PipelineDetailResponse pipelineA =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Pipeline A",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);
    PipelineDetailResponse pipelineB =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Pipeline B", "test", List.of()), testUserId);
    Long stepIdOfA =
        stepRepository.findStepIdByPipelineAndName(pipelineA.id(), "stepA").orElseThrow();

    assertThatThrownBy(
            () -> pipelineService.setFullRebuildPending(pipelineB.id(), stepIdOfA, true))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  @Test
  void 전체재생성_예약_플레이스홀더_없는_스텝은_IllegalArgumentException이다() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Non Incremental Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA",
                        "a",
                        "SQL",
                        "SELECT code, name FROM data.src_table",
                        outputDatasetId,
                        null,
                        null,
                        "APPEND"))),
            testUserId);
    Long stepId = stepRepository.findStepIdByPipelineAndName(created.id(), "stepA").orElseThrow();

    assertThatThrownBy(() -> pipelineService.setFullRebuildPending(created.id(), stepId, true))
        .isInstanceOf(IllegalArgumentException.class);

    // 대조 — APPEND 자체가 아니라 "증분(플레이스홀더 있음)+APPEND" 조합이 경고 사유다. 플레이스홀더가
    // 없으면 APPEND 여도 경고가 비어 있어야 한다(APPEND-category 대조를 MERGE 로 두 변수를 동시에 바꾸지
    // 않고, loadStrategy 하나만 바꿔 확인).
    assertThat(pipelineService.getPipelineById(created.id()).steps().get(0).warnings()).isEmpty();
  }

  /**
   * {@code fullRebuildMode} 세 가지 형태를 실측한다 — 웹 UI(Task 8)가 이 필드로 "전체 재생성"(출력
   * 재작성) vs "전체 재읽기"(입력만 전체, 출력 재작성 보장 없음) 라벨을 정확히 가른다.
   */
  @Test
  void 상세조회_fullRebuildMode가_스텝_형태에_따라_갈린다() {
    PipelineDetailResponse rebuildOutput =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Rebuild Output Mode Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA", "a", "SQL", INCREMENTAL_SQL, pkOutputDatasetId, null, null,
                        "MERGE"))),
            testUserId);
    assertThat(pipelineService.getPipelineById(rebuildOutput.id()).steps().get(0).fullRebuildMode())
        .as("SELECT 자동 적재 스텝은 예약 시 출력을 실제로 재생성한다")
        .isEqualTo(PipelineStepResponse.FULL_REBUILD_MODE_REBUILD_OUTPUT);

    PipelineDetailResponse readAll =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "Read All Mode Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA",
                        "a",
                        "SQL",
                        "UPDATE data.output_dataset SET col1 = col1 WHERE _updated_at >= {{last_run_at}}",
                        outputDatasetId,
                        null,
                        null,
                        "APPEND"))),
            testUserId);
    assertThat(pipelineService.getPipelineById(readAll.id()).steps().get(0).fullRebuildMode())
        .as("사용자 DML 스텝은 예약해도 출력을 재생성하지 않고 전체를 다시 읽기만 한다")
        .isEqualTo(PipelineStepResponse.FULL_REBUILD_MODE_READ_ALL);

    PipelineDetailResponse nonIncremental =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "No Placeholder Mode Pipeline",
                "test",
                List.of(
                    new PipelineStepRequest(
                        "stepA",
                        "a",
                        "SQL",
                        "SELECT code, name FROM data.src_table",
                        outputDatasetId,
                        null,
                        null,
                        "APPEND"))),
            testUserId);
    assertThat(
            pipelineService.getPipelineById(nonIncremental.id()).steps().get(0).fullRebuildMode())
        .as("증분 스텝이 아니면 재생성 모드 자체가 없다(예약도 불가)")
        .isNull();
  }
}
