package com.smartfirehub.pipeline.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CancelExecutionResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.exception.CyclicDependencyException;
import com.smartfirehub.pipeline.exception.ExecutionAlreadyFinishedException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 파이프라인 실행 오케스트레이터.
 *
 * <p>파이프라인 실행 레코드를 생성하고 {@link PipelineAsyncRunner}에게 비동기 실행을 위임한다. 실제 스텝 실행 로직은
 * PipelineAsyncRunner에 위치하며, 이 클래스는 DAG 유효성 검증과 실행 초기화만 담당한다.
 *
 * <p>설계 의도: PipelineAsyncRunner를 별도 Spring Bean으로 분리함으로써 {@code @Async} AOP 프록시가 올바르게 적용된다. 같은 클래스
 * 내 자기호출(self-invocation)은 프록시를 우회하여 HTTP 스레드를 블로킹할 수 있는 문제를 방지한다 (이슈 #189).
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
  private final PipelineExecutionRegistry executionRegistry;

  @Value("${app.executor.enabled:false}")
  private boolean executorEnabled;

  public PipelineExecutionService(
      PipelineStepRepository stepRepository,
      PipelineExecutionRepository executionRepository,
      PipelineRepository pipelineRepository,
      PipelineAsyncRunner asyncRunner,
      UserRepository userRepository,
      AuditLogService auditLogService,
      PipelineExecutionRegistry executionRegistry) {
    this.stepRepository = stepRepository;
    this.executionRepository = executionRepository;
    this.pipelineRepository = pipelineRepository;
    this.asyncRunner = asyncRunner;
    this.executionRegistry = executionRegistry;
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
   * <p>실행 레코드 생성이 단일 트랜잭션으로 원자적으로 처리된다. 실제 비동기 실행은 트랜잭션 커밋 후에 시작되므로 데이터 가시성 문제가 없다.
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param userId 실행 요청 사용자 ID
   * @return 생성된 파이프라인 실행 레코드 ID
   */
  @Transactional
  public Long executePipeline(Long pipelineId, Long userId) {
    return executePipeline(pipelineId, userId, "MANUAL", null);
  }

  /**
   * 실행을 이 인스턴스 등록부에 올린 뒤 비동기 러너에 제출한다(WD-230). 등록부에 있는 동안 하트비트가 갱신되고 정리기가 건너뛴다. 제출이 예외(풀 포화로 인한
   * {@code TaskRejectedException} 포함)를 던지면 러너가 영영 시작하지 않으므로 등록을 되돌리고 다시 던진다 — 안 그러면 하트비트가 죽은 실행을 계속
   * 살려 둔다.
   */
  private void submitRegistered(
      Long pipelineId,
      Long executionId,
      List<PipelineStepResponse> steps,
      Map<Long, List<Long>> stepDependencyMap,
      Map<Long, Long> stepIdToStepExecId,
      Long userId) {
    executionRegistry.register(executionId, TenantContext.require("파이프라인 실행 등록"));
    try {
      asyncRunner.executeAsync(
          pipelineId,
          executionId,
          steps,
          stepDependencyMap,
          stepIdToStepExecId,
          userId,
          executorEnabled);
    } catch (RuntimeException | Error e) {
      executionRegistry.unregister(executionId);
      throw e;
    }
  }

  /**
   * 파이프라인을 비동기로 실행한다 (트리거 정보 포함).
   *
   * <p>실행 레코드를 생성한 뒤 {@link PipelineAsyncRunner#executeAsync}에 위임하여 HTTP 스레드를 블로킹하지 않는다.
   *
   * <p>트랜잭션 원자성: {@code createExecution} + N×{@code createStepExecution}이 단일 트랜잭션으로 묶여 중간 실패 시 모두
   * 롤백된다. {@code asyncRunner.executeAsync}는 트랜잭션 커밋 이후({@code afterCommit})에 호출되므로 비동기 스레드가 미커밋
   * 데이터를 읽는 경쟁 조건이 발생하지 않는다.
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param userId 실행 요청 사용자 ID
   * @param triggeredBy 트리거 유형 (예: "MANUAL", "SCHEDULE", "API")
   * @param triggerId 트리거 레코드 ID (해당 없으면 null)
   * @return 생성된 파이프라인 실행 레코드 ID
   */
  @Transactional
  public Long executePipeline(Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
    // 파이프라인 스텝 및 의존성 로드
    List<PipelineStepResponse> steps = stepRepository.findByPipelineId(pipelineId);

    // 실행 레코드 생성
    Long executionId =
        executionRepository.createExecution(pipelineId, userId, triggeredBy, triggerId);

    // 스텝 실행 레코드 일괄 생성 (초기 상태: PENDING)
    // 중간에 예외 발생 시 @Transactional이 createExecution 포함 전체를 롤백한다
    Map<Long, Long> stepIdToStepExecId = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      Long stepExecId =
          executionRepository.createStepExecution(executionId, step.id(), step.name());
      stepIdToStepExecId.put(step.id(), stepExecId);
    }

    // 의존성 맵 구성 (stepId → 의존 스텝 ID 목록)
    Map<Long, List<Long>> stepDependencyMap = buildDependencyMap(steps);

    // 비동기 실행은 트랜잭션 커밋 후에 시작한다.
    // @Transactional 메서드 내에서 @Async를 직접 호출하면, 비동기 스레드가
    // 아직 커밋되지 않은 데이터를 읽으려 시도하는 경쟁 조건이 생길 수 있다.
    // afterCommit 콜백으로 executeAsync를 지연 호출하여 이를 방지한다.
    // 트랜잭션 동기화가 활성화되지 않은 경우(예: 테스트 환경)에는 즉시 호출한다.
    final Long finalExecutionId = executionId;
    final Map<Long, Long> finalStepIdToStepExecId = stepIdToStepExecId;
    final Map<Long, List<Long>> finalStepDependencyMap = stepDependencyMap;
    final List<PipelineStepResponse> finalSteps = steps;
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              // 트랜잭션 커밋 완료 후 비동기 실행 위임
              // → Spring AOP 프록시를 통해 @Async("pipelineExecutor")가 올바르게 적용됨
              submitRegistered(
                  pipelineId,
                  finalExecutionId,
                  finalSteps,
                  finalStepDependencyMap,
                  finalStepIdToStepExecId,
                  userId);
            }
          });
    } else {
      // 트랜잭션 컨텍스트 없음 — 즉시 비동기 실행 위임
      submitRegistered(
          pipelineId,
          finalExecutionId,
          finalSteps,
          finalStepDependencyMap,
          finalStepIdToStepExecId,
          userId);
    }

    // 파이프라인 실행 감사 로그 (#60/#92). 실행 번호를 설명에 넣고(취소 기록과 같은 「(실행 #N)」 형식) 무엇이 실행을 시작했는지를
    // metadata 에 남긴다(WD-462) — 트리거 실행은 트리거를 만든 사람 이름으로 남으므로, 이 값이 없으면 사람이 누른 실행과 예약·연쇄 실행이
    // 감사 화면에서 똑같아 보인다. triggeredBy 는 실행 기록(pipeline_execution.triggered_by)에 저장한 값 그대로다
    // (MANUAL 또는 트리거 유형 SCHEDULE·API·PIPELINE_CHAIN·WEBHOOK·DATASET_CHANGE). triggerId 는 수동 실행이면 null.
    String pipelineNameForLog = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");
    // audit_log.username 은 NOT NULL 이라 다른 감사 호출부처럼 "unknown" 으로 채운다.
    String usernameForLog =
        userRepository.findById(userId).map(u -> u.username()).orElse("unknown");
    Map<String, Object> executeMetadata = new LinkedHashMap<>();
    executeMetadata.put("executionId", executionId);
    executeMetadata.put("triggeredBy", triggeredBy);
    executeMetadata.put("triggerId", triggerId);
    auditLogService.log(
        userId,
        usernameForLog,
        "EXECUTE",
        "pipeline",
        String.valueOf(pipelineId),
        "파이프라인 실행: " + pipelineNameForLog + " (실행 #" + executionId + ")",
        null,
        null,
        "SUCCESS",
        null,
        executeMetadata);

    return executionId;
  }

  /**
   * 파이프라인 실행 취소를 요청한다(WD-153, 협조적 취소).
   *
   * <p>대기 중(PENDING — 비동기 스레드가 아직 시작 전)이면 그 자리에서 CANCELLED 로 마감되고, 러너가 나중에 시작해도 아무것도 실행하지 않는다. 실행
   * 중(RUNNING)이면 요청만 DB 에 남고 러너가 다음 체크포인트에서 멈춘다 — 진행 중인 API 호출·SQL 문장은 자기 타임아웃까지 끝까지 돈다.
   *
   * <p><b>경쟁 정리</b>: 실행 상태 쓰기는 러너·취소 양쪽 모두 "끝나지 않은 상태에서만" 하는 조건부 UPDATE 다. 마지막 체크포인트를 지난 뒤(마지막 스텝이
   * 이미 끝난 뒤) 도착한 요청은 <b>접수하되 효과가 없다</b> — 실행은 정상 COMPLETED/FAILED 로 끝나고 요청 시각만 남는다. 이 시점의 API 는 아직
   * RUNNING 만 볼 수 있어 "이미 끝남"을 판정할 수 없기 때문이다. 화면은 상태를 폴링해 최종 상태를 보여 준다.
   *
   * @throws PipelineNotFoundException 실행이 없거나 이 파이프라인 것이 아니면(404)
   * @throws ExecutionAlreadyFinishedException 이미 끝난 실행이면(409)
   */
  @Transactional
  public CancelExecutionResponse cancelExecution(Long pipelineId, Long executionId, Long userId) {
    // 소유권 검증(#188) — 다른 파이프라인의 실행 id 로 취소하지 못하게 하고, 존재 여부도 숨긴다. RLS 라 다른 테넌트 실행도 여기서 404 가 된다.
    Long ownerPipelineId =
        executionRepository
            .findPipelineIdOfExecution(executionId)
            .filter(pipelineId::equals)
            .orElseThrow(PipelineNotFoundException::execution);

    PipelineExecutionRepository.CancelRequestResult result =
        executionRepository
            .requestCancel(executionId, userId)
            .orElseThrow(ExecutionAlreadyFinishedException::new);

    // 실행 감사 로그와 짝을 맞춘다 — 누가 언제 어떤 실행을 멈췄는지 감사 화면에서 보이게.
    String pipelineNameForLog = pipelineRepository.findNameById(ownerPipelineId).orElse("Pipeline");
    String usernameForLog =
        userRepository.findById(userId).map(u -> u.username()).orElse("unknown");
    auditLogService.log(
        userId,
        usernameForLog,
        "CANCEL",
        "pipeline",
        String.valueOf(ownerPipelineId),
        "파이프라인 실행 취소: " + pipelineNameForLog + " (실행 #" + executionId + ")",
        null,
        null,
        "SUCCESS",
        null,
        null);

    return new CancelExecutionResponse(executionId, result.status(), result.cancelRequestedAt());
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
