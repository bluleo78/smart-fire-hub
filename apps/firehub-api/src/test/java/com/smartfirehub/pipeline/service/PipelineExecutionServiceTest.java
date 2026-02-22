package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import java.util.List;
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
  @Mock DataTableService dataTableService;
  @Mock DatasetRepository datasetRepository;
  @Mock DatasetColumnRepository columnRepository;
  @Mock SqlScriptExecutor sqlExecutor;
  @Mock PythonScriptExecutor pythonExecutor;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @Mock ApiCallExecutor apiCallExecutor;
  @Mock ApiConnectionService apiConnectionService;
  @Mock ObjectMapper objectMapper;

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
        stepResponse(stepId, "step1", "SQL", "SELECT 1", null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(sqlExecutor.execute("SELECT 1")).thenReturn("1 row affected");

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
        stepResponse(stepId, "publish-step", "SQL", "SELECT 1", null, List.of());

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(sqlStep));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, stepId)).thenReturn(stepExecId);
    when(sqlExecutor.execute("SELECT 1")).thenReturn("ok");

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
        null);
  }
}
