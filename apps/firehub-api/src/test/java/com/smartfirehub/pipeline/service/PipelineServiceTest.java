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

  private Long testUserId;
  private Long inputDatasetId;
  private Long outputDatasetId;

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
}
