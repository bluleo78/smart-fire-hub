package com.smartfirehub.pipeline.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 파이프라인 실행 오케스트레이터.
 *
 * <p>파이프라인 실행 레코드를 생성하고 {@link PipelineAsyncRunner}에게 비동기 실행을 위임한다. 실제 스텝 실행 로직은 PipelineAsyncRunner에
 * 위치하며, 이 클래스는 DAG 유효성 검증과 실행 초기화만 담당한다.
 *
 * <p>설계 의도: PipelineAsyncRunner를 별도 Spring Bean으로 분리함으로써 {@code @Async} AOP 프록시가 올바르게 적용된다.
 * 같은 클래스 내 자기호출(self-invocation)은 프록시를 우회하여 HTTP 스레드를 블로킹할 수 있는 문제를 방지한다 (이슈 #189).
 */
@Slf4j
@Service
public class PipelineExecutionService {

  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineRepository pipelineRepository;
  private final PipelineAsyncRunner asyncRunner;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  @Value("${app.executor.enabled:false}")
  private boolean executorEnabled;

  public PipelineExecutionService(
      PipelineStepRepository stepRepository,
      PipelineExecutionRepository executionRepository,
      PipelineRepository pipelineRepository,
      PipelineAsyncRunner asyncRunner,
      UserRepository userRepository,
      AuditLogService auditLogService) {
    this.stepRepository = stepRepository;
    this.executionRepository = executionRepository;
    this.pipelineRepository = pipelineRepository;
    this.asyncRunner = asyncRunner;
    this.userRepository = userRepository;
    this.auditLogService = auditLogService;
  }

  /**
   * DAG 유효성 검사 — Kahn's algorithm으로 위상 정렬을 시도하여 순환 의존성을 감지한다.
   *
   * @param steps 검증할 스텝 목록
   * @throws CyclicDependencyException 순환 의존성이 감지된 경우
   */
  public void validateDAG(List<PipelineStepRequest> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }

    // 스텝 이름 → 인덱스 맵 구성
    Map<String, Integer> stepNameToIndex = new HashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      stepNameToIndex.put(steps.get(i).name(), i);
    }

    // 인접 리스트 및 진입 차수 맵 구성
    Map<Integer, List<Integer>> adjacencyList = new HashMap<>();
    Map<Integer, Integer> inDegree = new HashMap<>();

    for (int i = 0; i < steps.size(); i++) {
      adjacencyList.put(i, new ArrayList<>());
      inDegree.put(i, 0);
    }

    // 의존성 그래프 구성
    for (int i = 0; i < steps.size(); i++) {
      PipelineStepRequest step = steps.get(i);
      if (step.dependsOnStepNames() != null) {
        for (String dependsOnName : step.dependsOnStepNames()) {
          Integer dependsOnIndex = stepNameToIndex.get(dependsOnName);
          if (dependsOnIndex != null) {
            adjacencyList.get(dependsOnIndex).add(i);
            inDegree.put(i, inDegree.get(i) + 1);
          }
        }
      }
    }

    // Kahn's algorithm
    Queue<Integer> queue = new LinkedList<>();
    for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.offer(entry.getKey());
      }
    }

    int processedCount = 0;
    while (!queue.isEmpty()) {
      int current = queue.poll();
      processedCount++;

      for (int neighbor : adjacencyList.get(current)) {
        inDegree.put(neighbor, inDegree.get(neighbor) - 1);
        if (inDegree.get(neighbor) == 0) {
          queue.offer(neighbor);
        }
      }
    }

    // 처리된 노드 수 != 전체 노드 수 → 순환 의존성 존재
    if (processedCount != steps.size()) {
      throw new CyclicDependencyException("Cyclic dependency detected in pipeline steps");
    }
  }

  /**
   * 파이프라인을 비동기로 실행한다 (수동 실행).
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param userId 실행 요청 사용자 ID
   * @return 생성된 파이프라인 실행 레코드 ID
   */
  public Long executePipeline(Long pipelineId, Long userId) {
    return executePipeline(pipelineId, userId, "MANUAL", null);
  }

  /**
   * 파이프라인을 비동기로 실행한다 (트리거 정보 포함).
   *
   * <p>실행 레코드를 생성한 뒤 {@link PipelineAsyncRunner#executeAsync}에 위임하여 HTTP 스레드를 블로킹하지 않는다.
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param userId 실행 요청 사용자 ID
   * @param triggeredBy 트리거 유형 (예: "MANUAL", "SCHEDULE", "API")
   * @param triggerId 트리거 레코드 ID (해당 없으면 null)
   * @return 생성된 파이프라인 실행 레코드 ID
   */
  public Long executePipeline(Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
    // 파이프라인 스텝 및 의존성 로드
    List<PipelineStepResponse> steps = stepRepository.findByPipelineId(pipelineId);

    // 실행 레코드 생성
    Long executionId =
        executionRepository.createExecution(pipelineId, userId, triggeredBy, triggerId);

    // 스텝 실행 레코드 일괄 생성 (초기 상태: PENDING)
    Map<Long, Long> stepIdToStepExecId = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      Long stepExecId = executionRepository.createStepExecution(executionId, step.id());
      stepIdToStepExecId.put(step.id(), stepExecId);
    }

    // 의존성 맵 구성 (stepId → 의존 스텝 ID 목록)
    Map<Long, List<Long>> stepDependencyMap = buildDependencyMap(steps);

    // 별도 Bean(PipelineAsyncRunner)을 통해 비동기 실행 위임
    // → Spring AOP 프록시를 통해 @Async("pipelineExecutor")가 올바르게 적용됨
    asyncRunner.executeAsync(
        pipelineId, executionId, steps, stepDependencyMap, stepIdToStepExecId, userId,
        executorEnabled);

    // 파이프라인 실행 감사 로그 (#60/#92)
    String pipelineNameForLog = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");
    String usernameForLog = userRepository.findById(userId).map(u -> u.username()).orElse(null);
    auditLogService.log(
        userId,
        usernameForLog,
        "EXECUTE",
        "pipeline",
        String.valueOf(pipelineId),
        "파이프라인 실행: " + pipelineNameForLog,
        null,
        null,
        "SUCCESS",
        null,
        null);

    return executionId;
  }

  /**
   * 스텝 목록에서 의존성 맵을 구성한다.
   *
   * @param steps 파이프라인 스텝 목록
   * @return 스텝 ID → 의존 스텝 ID 목록 맵
   */
  private Map<Long, List<Long>> buildDependencyMap(List<PipelineStepResponse> steps) {
    Map<String, Long> stepNameToId = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      stepNameToId.put(step.name(), step.id());
    }

    Map<Long, List<Long>> dependencyMap = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      List<Long> deps = new ArrayList<>();
      if (step.dependsOnStepNames() != null) {
        for (String depName : step.dependsOnStepNames()) {
          Long depStepId = stepNameToId.get(depName);
          if (depStepId != null) {
            deps.add(depStepId);
          }
        }
      }
      dependencyMap.put(step.id(), deps);
    }

    return dependencyMap;
  }
}
