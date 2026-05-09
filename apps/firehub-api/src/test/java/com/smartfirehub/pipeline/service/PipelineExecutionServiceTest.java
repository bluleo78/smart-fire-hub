package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PipelineExecutionService 단위 테스트.
 *
 * <p>비동기 실행 로직(executeStep, topologicalSort 등)은 {@link PipelineAsyncRunnerTest}에서 검증한다. 이 클래스는 DAG
 * 유효성 검사와 실행 오케스트레이션(레코드 생성 → asyncRunner 위임)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PipelineExecutionServiceTest {

  @Mock PipelineStepRepository stepRepository;
  @Mock PipelineExecutionRepository executionRepository;
  @Mock PipelineRepository pipelineRepository;
  @Mock PipelineAsyncRunner asyncRunner;
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
  // executePipeline 오케스트레이션 테스트
  // ------------------------------------------------------------------ //

  @Test
  void executePipeline_singleSqlStep_returnsExecutionIdAndCreatesStepExecution() {
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
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("TestPipeline"));

    // when
    Long result = service.executePipeline(pipelineId, userId);

    // then
    assertThat(result).isEqualTo(executionId);
    verify(executionRepository).createStepExecution(executionId, stepId);
    verify(executionRepository).createExecution(pipelineId, userId, "MANUAL", null);
    // asyncRunner.executeAsync가 호출되었는지 확인
    verify(asyncRunner)
        .executeAsync(
            eq(pipelineId),
            eq(executionId),
            anyList(),
            anyMap(),
            anyMap(),
            eq(userId),
            anyBoolean());
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
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("EmptyPipeline"));

    // when
    Long result = service.executePipeline(pipelineId, userId);

    // then
    assertThat(result).isEqualTo(executionId);
    verify(executionRepository).createExecution(pipelineId, userId, "MANUAL", null);
  }

  @Test
  void executePipeline_withTriggerInfo_passesTriggeredByAndTriggerIdToRepository() {
    // given
    Long pipelineId = 5L;
    Long userId = 10L;
    Long executionId = 80L;
    String triggeredBy = "SCHEDULE";
    Long triggerId = 99L;

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of());
    when(executionRepository.createExecution(pipelineId, userId, triggeredBy, triggerId))
        .thenReturn(executionId);
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("ScheduledPipeline"));

    // when
    Long result = service.executePipeline(pipelineId, userId, triggeredBy, triggerId);

    // then
    assertThat(result).isEqualTo(executionId);
    verify(executionRepository).createExecution(pipelineId, userId, triggeredBy, triggerId);
  }

  @Test
  void executePipeline_multipleSteps_allStepExecutionRecordsCreated() {
    // given
    Long pipelineId = 6L;
    Long userId = 10L;
    Long executionId = 90L;
    Long step1Id = 110L;
    Long step2Id = 111L;

    PipelineStepResponse step1 = stepResponse(step1Id, "step1", "SQL", "SELECT 1", null, List.of());
    PipelineStepResponse step2 =
        stepResponse(step2Id, "step2", "SQL", "SELECT 2", null, List.of("step1"));

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(210L);
    when(executionRepository.createStepExecution(executionId, step2Id)).thenReturn(211L);
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("MultiStepPipeline"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: 두 스텝 모두 실행 레코드 생성됨
    verify(executionRepository).createStepExecution(executionId, step1Id);
    verify(executionRepository).createStepExecution(executionId, step2Id);

    // asyncRunner에 의존성 맵이 올바르게 전달되는지 확인
    ArgumentCaptor<java.util.Map> depMapCaptor = ArgumentCaptor.forClass(java.util.Map.class);
    verify(asyncRunner)
        .executeAsync(
            eq(pipelineId),
            eq(executionId),
            anyList(),
            depMapCaptor.capture(),
            anyMap(),
            eq(userId),
            anyBoolean());
    java.util.Map<Long, List<Long>> capturedDepMap = depMapCaptor.getValue();
    // step2는 step1에 의존
    assertThat(capturedDepMap.get(step2Id)).containsExactly(step1Id);
    // step1은 의존성 없음
    assertThat(capturedDepMap.get(step1Id)).isEmpty();
  }

  @Test
  void executePipeline_auditLogWritten() {
    // given
    Long pipelineId = 7L;
    Long userId = 10L;
    Long executionId = 95L;

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of());
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(pipelineRepository.findNameById(pipelineId)).thenReturn(Optional.of("AuditPipeline"));

    // when
    service.executePipeline(pipelineId, userId);

    // then: 감사 로그 기록됨
    verify(auditLogService)
        .log(
            eq(userId),
            any(),
            eq("EXECUTE"),
            eq("pipeline"),
            eq(String.valueOf(pipelineId)),
            contains("AuditPipeline"),
            any(),
            any(),
            eq("SUCCESS"),
            any(),
            any());
  }

  // ------------------------------------------------------------------ //
  // @Transactional 원자성 관련 테스트 (이슈 #191)
  // ------------------------------------------------------------------ //

  /**
   * createStepExecution 도중 예외 발생 시 asyncRunner.executeAsync가 호출되지 않음을 검증한다.
   *
   * <p>실제 트랜잭션 롤백은 Spring 통합 환경에서만 일어나지만, 비동기 실행 위임이 차단되는지는 단위 레벨에서도 검증할 수 있다. 트랜잭션 동기화가 비활성 상태(테스트
   * 환경)에서는 즉시 호출 경로를 타므로, 예외 전파로 asyncRunner 미호출 여부를 확인한다.
   */
  @Test
  void executePipeline_createStepExecutionThrows_asyncRunnerNotCalled() {
    // given: 두 스텝 중 두 번째 스텝 실행 레코드 생성 시 예외 발생
    Long pipelineId = 8L;
    Long userId = 10L;
    Long executionId = 100L;
    Long step1Id = 120L;
    Long step2Id = 121L;

    PipelineStepResponse step1 = stepResponse(step1Id, "step1", "SQL", "SELECT 1", null, List.of());
    PipelineStepResponse step2 =
        stepResponse(step2Id, "step2", "SQL", "SELECT 2", null, List.of("step1"));

    when(stepRepository.findByPipelineId(pipelineId)).thenReturn(List.of(step1, step2));
    when(executionRepository.createExecution(pipelineId, userId, "MANUAL", null))
        .thenReturn(executionId);
    when(executionRepository.createStepExecution(executionId, step1Id)).thenReturn(220L);
    // 두 번째 스텝 실행 레코드 생성 시 DB 오류 시뮬레이션
    when(executionRepository.createStepExecution(executionId, step2Id))
        .thenThrow(new RuntimeException("DB connection lost"));

    // when / then: 예외가 전파되어야 한다
    assertThatThrownBy(() -> service.executePipeline(pipelineId, userId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("DB connection lost");

    // asyncRunner.executeAsync는 호출되지 않아야 한다
    // (트랜잭션 컨텍스트 없는 단위 테스트 환경에서, 즉시 호출 경로이지만
    //  예외 전파로 인해 registerSynchronization 이전에 중단됨)
    verify(asyncRunner, never())
        .executeAsync(any(), any(), any(), any(), any(), any(), anyBoolean());
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
}
