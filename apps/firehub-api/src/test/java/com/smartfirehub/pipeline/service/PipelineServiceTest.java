package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class PipelineServiceTest extends IntegrationTestBase {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;
    private Long inputDatasetId;
    private Long outputDatasetId;

    @BeforeEach
    void setUp() {
        // Create test user
        testUserId = dsl.insertInto(USER)
                .set(USER.USERNAME, "testuser")
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Test User")
                .set(USER.EMAIL, "test@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();

        // Create input dataset
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)
        );

        DatasetDetailResponse inputDataset = datasetService.createDataset(new CreateDatasetRequest(
                "Input Dataset",
                "input_dataset",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);
        inputDatasetId = inputDataset.id();

        // Create output dataset
        DatasetDetailResponse outputDataset = datasetService.createDataset(new CreateDatasetRequest(
                "Output Dataset",
                "output_dataset",
                null,
                null,
                "DERIVED",
                columns
        ), testUserId);
        outputDatasetId = outputDataset.id();
    }

    @Test
    void createPipeline_withStepsAndDependencies_success() {
        // Given
        List<PipelineStepRequest> steps = List.of(
                new PipelineStepRequest(
                        "step1",
                        "First step",
                        "SQL",
                        "SELECT * FROM input",
                        outputDatasetId,
                        List.of(inputDatasetId),
                        null
                ),
                new PipelineStepRequest(
                        "step2",
                        "Second step",
                        "SQL",
                        "SELECT * FROM output WHERE condition",
                        inputDatasetId,
                        List.of(outputDatasetId),
                        List.of("step1")
                )
        );

        CreatePipelineRequest request = new CreatePipelineRequest(
                "Test Pipeline",
                "Test pipeline description",
                steps
        );

        // When
        PipelineDetailResponse response = pipelineService.createPipeline(request, testUserId);

        // Then
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Test Pipeline");
        assertThat(response.steps()).hasSize(2);

        // Verify in DB
        Long pipelineCount = dsl.selectCount()
                .from(PIPELINE)
                .where(PIPELINE.ID.eq(response.id()))
                .fetchOne(0, Long.class);
        assertThat(pipelineCount).isEqualTo(1);

        Long stepCount = dsl.selectCount()
                .from(PIPELINE_STEP)
                .where(PIPELINE_STEP.PIPELINE_ID.eq(response.id()))
                .fetchOne(0, Long.class);
        assertThat(stepCount).isEqualTo(2);

        // Verify dependencies
        Long depCount = dsl.selectCount()
                .from(PIPELINE_STEP_DEPENDENCY)
                .fetchOne(0, Long.class);
        assertThat(depCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void createPipeline_withCyclicDependency_throwsException() {
        // Given
        List<PipelineStepRequest> steps = List.of(
                new PipelineStepRequest(
                        "step1",
                        "Step 1",
                        "SQL",
                        "SELECT 1",
                        outputDatasetId,
                        null,
                        List.of("step2")
                ),
                new PipelineStepRequest(
                        "step2",
                        "Step 2",
                        "SQL",
                        "SELECT 2",
                        inputDatasetId,
                        null,
                        List.of("step1")
                )
        );

        CreatePipelineRequest request = new CreatePipelineRequest(
                "Cyclic Pipeline",
                "Should fail",
                steps
        );

        // When/Then
        assertThatThrownBy(() -> pipelineService.createPipeline(request, testUserId))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    void getPipelines_returnsPaginatedList() {
        // Given
        CreatePipelineRequest request1 = new CreatePipelineRequest(
                "Pipeline 1",
                "Description 1",
                List.of()
        );
        CreatePipelineRequest request2 = new CreatePipelineRequest(
                "Pipeline 2",
                "Description 2",
                List.of()
        );

        pipelineService.createPipeline(request1, testUserId);
        pipelineService.createPipeline(request2, testUserId);

        // When
        PageResponse<PipelineResponse> response = pipelineService.getPipelines(0, 10);

        // Then
        assertThat(response.content()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.totalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getPipelineById_returnsDetailWithSteps() {
        // Given
        List<PipelineStepRequest> steps = List.of(
                new PipelineStepRequest(
                        "step1",
                        "Step 1",
                        "SQL",
                        "SELECT 1",
                        outputDatasetId,
                        null,
                        null
                )
        );

        CreatePipelineRequest request = new CreatePipelineRequest(
                "Test Pipeline",
                "Description",
                steps
        );

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
        CreatePipelineRequest createRequest = new CreatePipelineRequest(
                "Original Name",
                "Original Description",
                List.of()
        );

        PipelineDetailResponse created = pipelineService.createPipeline(createRequest, testUserId);

        UpdatePipelineRequest updateRequest = new UpdatePipelineRequest(
                "Updated Name",
                "Updated Description",
                false,
                null
        );

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
        List<PipelineStepRequest> originalSteps = List.of(
                new PipelineStepRequest(
                        "step1",
                        "Step 1",
                        "SQL",
                        "SELECT 1",
                        outputDatasetId,
                        null,
                        null
                )
        );

        CreatePipelineRequest createRequest = new CreatePipelineRequest(
                "Test Pipeline",
                "Description",
                originalSteps
        );

        PipelineDetailResponse created = pipelineService.createPipeline(createRequest, testUserId);

        List<PipelineStepRequest> newSteps = List.of(
                new PipelineStepRequest(
                        "step_new",
                        "New Step",
                        "SQL",
                        "SELECT 2",
                        inputDatasetId,
                        null,
                        null
                )
        );

        UpdatePipelineRequest updateRequest = new UpdatePipelineRequest(
                "Test Pipeline",
                "Description",
                true,
                newSteps
        );

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
        List<PipelineStepRequest> steps = List.of(
                new PipelineStepRequest(
                        "step1",
                        "Step 1",
                        "SQL",
                        "SELECT 1",
                        outputDatasetId,
                        null,
                        null
                )
        );

        CreatePipelineRequest request = new CreatePipelineRequest(
                "To Delete",
                "Description",
                steps
        );

        PipelineDetailResponse created = pipelineService.createPipeline(request, testUserId);
        Long pipelineId = created.id();

        // When
        pipelineService.deletePipeline(pipelineId);

        // Then
        assertThatThrownBy(() -> pipelineService.getPipelineById(pipelineId))
                .isInstanceOf(PipelineNotFoundException.class);

        // Verify steps deleted
        Long stepCount = dsl.selectCount()
                .from(PIPELINE_STEP)
                .where(PIPELINE_STEP.PIPELINE_ID.eq(pipelineId))
                .fetchOne(0, Long.class);
        assertThat(stepCount).isEqualTo(0);
    }

    @Test
    void executePipeline_createsExecution() {
        // Given
        CreatePipelineRequest request = new CreatePipelineRequest(
                "Test Pipeline",
                "Description",
                List.of()
        );

        PipelineDetailResponse pipeline = pipelineService.createPipeline(request, testUserId);

        // When
        PipelineExecutionResponse execution = pipelineService.executePipeline(pipeline.id(), testUserId);

        // Then
        assertThat(execution.id()).isNotNull();
        assertThat(execution.pipelineId()).isEqualTo(pipeline.id());
        assertThat(execution.status()).isEqualTo("PENDING");

        // Verify execution record created
        Long executionCount = dsl.selectCount()
                .from(PIPELINE_EXECUTION)
                .where(PIPELINE_EXECUTION.ID.eq(execution.id()))
                .fetchOne(0, Long.class);
        assertThat(executionCount).isEqualTo(1);
    }

    @Test
    void getExecutionsByPipelineId_returnsExecutions() {
        // Given
        CreatePipelineRequest request = new CreatePipelineRequest(
                "Test Pipeline",
                "Description",
                List.of()
        );

        PipelineDetailResponse pipeline = pipelineService.createPipeline(request, testUserId);
        PipelineExecutionResponse execution = pipelineService.executePipeline(pipeline.id(), testUserId);

        // When
        List<PipelineExecutionResponse> executions = pipelineService.getExecutionsByPipelineId(pipeline.id());

        // Then
        assertThat(executions).isNotEmpty();
        assertThat(executions).anyMatch(exec -> exec.id().equals(execution.id()));
    }
}
