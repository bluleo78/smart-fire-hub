package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.pipeline.dto.LoadStrategy;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.executor.AiClassifyExecutor;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallExecutor;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ScriptTokenIssuer;
import com.smartfirehub.pipeline.service.step.PgStepDialect;
import com.smartfirehub.pipeline.service.step.SqlOutputColumns;
import com.smartfirehub.pipeline.service.step.SqlStepRun;
import com.smartfirehub.pipeline.service.step.StepDialect;
import com.smartfirehub.pipeline.service.step.TiberoStepDialect;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.securitylevel.pythonread.PythonReadAccessException;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrants;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * PipelineExecutionService의 비동기 파이프라인 실행을 담당하는 별도 Spring Bean.
 *
 * <p>같은 클래스 내 자기호출(self-invocation)로는 Spring AOP 프록시를 우회하여 {@code @Async}가 적용되지 않는 문제를 방지하기 위해 별도
 * 빈으로 분리한다. PipelineExecutionService가 이 빈을 주입받아 호출함으로써 프록시를 통한 정상적인 비동기 실행이 보장된다.
 *
 * <p>참고: DataExportAsyncRunner(이슈 #167)와 동일한 패턴으로 수정됨(이슈 #189).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PipelineAsyncRunner {

  /**
   * API_CALL 스텝이 참조한 API 연결이 실행 시점에 없거나 이 워크스페이스에서 보이지 않을 때 실행 기록에 남기는 문구(WD-180). 저장 시점 검증
   * 문구({@link PipelineService#API_CONNECTION_NOT_FOUND_MESSAGE})와 같은 문체로, 할 일(연결 다시 선택)을 담는다.
   */
  static final String API_CONNECTION_NOT_FOUND_AT_RUN_MESSAGE =
      "스텝이 참조한 API 연결을 찾을 수 없습니다. 파이프라인 편집에서 연결을 다시 선택하세요.";

  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineExecutionRegistry executionRegistry;

  /** 테넌트별 동시 실행 관문 — 러너가 끝나면 자리를 돌려줘 기다리던 다음 실행이 시작된다(WD-439). */
  private final PipelineTenantAdmission tenantAdmission;

  private final PipelineRepository pipelineRepository;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final SqlColumnProbe sqlColumnProbe;
  private final SqlScriptExecutor sqlExecutor;
  private final PythonScriptExecutor pythonExecutor;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final ApiCallExecutor apiCallExecutor;
  private final ApiConnectionService apiConnectionService;
  private final ObjectMapper objectMapper;
  private final PermissionChecker permissionChecker;
  private final ExecutorClient executorClient;

  /**
   * Tibero 저장소 테넌트의 SQL 단계 실행·컬럼 탐지 전용 클라이언트(WD-77, 스펙 §5.1). Tibero 테넌트 SQL 은 이것으로만 실행한다 — {@code
   * executorEnabled} 와 무관하고 PG 실행기({@link #executorClient}·{@link #sqlExecutor}·{@link
   * #sqlColumnProbe})로 폴백하지 않는다.
   */
  private final DbExecutorClient dbExecutorClient;

  private final AiClassifyExecutor aiClassifyExecutor;
  private final TempDatasetService tempDatasetService;

  /**
   * 테넌트 백엔드별 데이터 스키마·검증기 선택(WD-77, 스펙 §5.0). SQL 스텝 검증기와 {@code {{#N}}} 한정 이름을 모두 여기서 얻는다 — PG 테넌트는
   * 기존 검증기 빈·{@code DataSchema.qualify} 그대로다.
   */
  private final PipelineDataSchema pipelineDataSchema;

  private final PythonScriptValidator pythonScriptValidator;
  private final IncrementalCursorService incrementalCursorService;

  /** Tibero 테넌트 증분 책갈피 후보를 Tibero 시계로 캡처한다(WD-77) — PG 후보 함수·PG 시계는 Tibero 테넌트에서 쓰지 않는다. */
  private final TiberoIncrementalCursor tiberoIncrementalCursor;

  private final OutputTableSessionLock outputTableSessionLock;

  /** 증분 스텝의 「책갈피 읽기 → 적재 → 전진」 직렬화 잠금(WD-223). */
  private final IncrementalStepLock incrementalStepLock;

  private final PipelineSecurityGate pipelineSecurityGate;
  private final PythonReadGrants pythonReadGrants;
  private final LocalPythonOutputLoader localPythonOutputLoader;

  /** AI_CLASSIFY 입력의 AI 정책 판정(S3 §4.3) — PipelineSecurityGate(흐름 B 소유)를 고치지 않고 호출부에서 가드를 직접 부른다. */
  private final DatasetAccessGuard datasetAccessGuard;

  /** AI_CLASSIFY 가 실제로 쓸 분류 공급자의 호스팅 — 실행 시점 값으로 판정한다(저장 후 외부로 바뀐 경우를 막는다). */
  private final AiHostingResolver aiHostingResolver;

  /** Tibero 테넌트 PYTHON 의 실행 1회용 스크립트 토큰 발급기(WD-78) — 스크립트가 조회 도우미로 db-executor 슬롯 조회를 부를 때 쓴다. */
  private final ScriptTokenIssuer scriptTokenIssuer;

  /** 스크립트 토큰에 싣는 테넌트 세대 번호({@code script_token_epoch}) 조회(WD-78). */
  private final TenantDataStoreRepository tenantDataStoreRepository;

  /**
   * 실행기(firehub-executor)의 Python 실행 시간 제한(초) — 실행기 {@code python_timeout} 과 같은 값이어야 한다. 기본값·환경 변수
   * 이름 ({@code EXECUTOR_PYTHON_TIMEOUT})을 실행기와 맞춰 한 값을 두 서비스에 같이 줄 수 있게 했다. Tibero 스크립트 토큰 만료를 정하는
   * 데만 쓴다 — 실행기 쪽만 늘리면 긴 스크립트의 늦은 조회가 만료(401)로 실패한다(fail-closed). final 이 아닌 필드 주입이라 생성자 주입 테스트에서는
   * 기본값이 남는다.
   */
  @Value("${app.executor.python-timeout-seconds:1800}")
  private long executorPythonTimeoutSeconds = 1800;

  /** 스크립트 토큰 만료에 더하는 여유(초) — 발급부터 실행 시작까지(스테이징 DDL·실행기 대기)와 시계 차이를 덮는다. */
  static final long PYTHON_TOKEN_TTL_MARGIN_SECONDS = 60;

  /** 실행기 끈(로컬) Tibero PYTHON 의 스크립트 토큰 유효 시간 — 자식 프로세스 시간 제한 + 여유. */
  static final Duration LOCAL_PYTHON_TOKEN_TTL =
      Duration.ofSeconds(PythonScriptExecutor.TIMEOUT_SECONDS + PYTHON_TOKEN_TTL_MARGIN_SECONDS);

  /**
   * 파이프라인을 비동기로 실행한다.
   *
   * <p>이 메서드는 {@code pipelineExecutor} 스레드풀에서 실행되므로 HTTP 요청 스레드를 블록하지 않는다. DAG 위상 정렬 후 스텝을 순서대로
   * 실행하고 완료 이벤트를 발행한다.
   *
   * @param pipelineId 실행할 파이프라인 ID
   * @param executionId 생성된 파이프라인 실행 레코드 ID
   * @param steps 파이프라인 스텝 목록
   * @param stepDependencyMap 스텝 ID → 의존 스텝 ID 목록 매핑
   * @param stepIdToStepExecId 스텝 ID → 스텝 실행 레코드 ID 매핑
   *     <p><b>트랜잭션 경계(P2-b Task 5)</b>: 이 메서드는 의도적으로 {@code @Transactional} 이 아니다. 파이프라인 실행은 수 분이
   *     걸릴 수 있어 전체를 한 트랜잭션으로 감싸면 커넥션을 그만큼 점유한다. 상태 갱신({@code updateExecutionStatus}/{@code
   *     updateStepExecution}, 최종 상태는 취소 요청이 없을 때만 쓰는 {@code finishUnlessCancelRequested} — WD-411)은
   *     모두 <b>단일 행 쓰기</b>이고 여러 건이 함께 커밋돼야 하는 불변식이 없으므로(스텝 상태는 각각 독립, 최종 상태는 스텝 종료 후 한 번), 리포지토리의
   *     클래스 레벨 {@code @Transactional} 이 여는 짧은 트랜잭션으로 충분하다 — 그 트랜잭션이 곧 RLS GUC 공급 지점이다.
   * @param userId 실행 주체 ID — 수동은 요청자, 트리거는 트리거 생성자. Python/AI 권한 체크와 SQL 스텝 보안 등급
   *     판정(PipelineSecurityGate)에 쓴다
   * @param executorEnabled 외부 실행기 활성화 여부
   */
  @Async("pipelineExecutor")
  public void executeAsync(
      Long pipelineId,
      Long executionId,
      List<PipelineStepResponse> steps,
      Map<Long, List<Long>> stepDependencyMap,
      Map<Long, Long> stepIdToStepExecId,
      Long userId,
      boolean executorEnabled) {
    try {
      runExecution(
          pipelineId,
          executionId,
          steps,
          stepDependencyMap,
          stepIdToStepExecId,
          userId,
          executorEnabled);
    } finally {
      // 어떤 경로로 끝나도(정상·실패·취소·마감 경합 패배·예상 밖 예외) 등록부에서 뺀다 — 안 빼면 하트비트가 끝난 실행을 계속 갱신한다(WD-230).
      executionRegistry.unregister(executionId);
      // 관문 자리도 같은 지점에서 돌려준다 — 어떤 종료 경로든 자리가 새지 않아야 그 테넌트의 대기 실행이 멈추지 않는다(WD-439).
      tenantAdmission.release(executionId);
    }
  }

  /** {@link #executeAsync} 의 본문 — 등록 해제를 어떤 종료 경로에서도 보장하려고 바깥 메서드가 try/finally 로 감싼다. */
  private void runExecution(
      Long pipelineId,
      Long executionId,
      List<PipelineStepResponse> steps,
      Map<Long, List<Long>> stepDependencyMap,
      Map<Long, Long> stepIdToStepExecId,
      Long userId,
      boolean executorEnabled) {

    LocalDateTime executionStartedAt = LocalDateTime.now(ZoneOffset.UTC);
    Long pipelineCreatedBy = pipelineRepository.findCreatedByIdById(pipelineId).orElse(null);
    String pipelineName = pipelineRepository.findNameById(pipelineId).orElse("Pipeline");

    // 협조적 취소 확인(WD-153) — 체크포인트마다 DB 의 취소 요청을 PK 로 읽는다. 여러 API 인스턴스 중 어디서 취소를 받아도 보이고, 실행기에는 인자로
    // 넘겨 페이지·배치·재시도 대기 사이에서도 같은 판정을 쓴다.
    // WD-230: 취소 요청뿐 아니라 실행이 외부에서 마감된 경우(중단 정리기가 FAILED 로 만듦)에도 다음 체크포인트에서 멈춘다.
    ExecutionCancelCheck cancel = () -> executionRepository.isStopRequested(executionId);

    try {
      // 실행 상태를 RUNNING으로 업데이트. 조건부 쓰기다(끝나지 않은 실행만) — 대기 중에 취소돼 이미 CANCELLED 인 실행을 되살리지 않는다.
      executionRepository.updateExecutionStatus(executionId, "RUNNING", executionStartedAt, null);

      // 취소 체크포인트(WD-153) — 시작 직후. 대기 중(PENDING)에 취소된 실행은 취소 API 가 이미 CANCELLED·스텝 SKIPPED 로 마감했다 —
      // 스텝을 하나도
      // 돌리지 않고 끝낸다. 시작 쓰기와 이 확인 사이에 RUNNING 취소가 들어온 경우도 같은 마감으로 정리된다(조건부라 이미 마감된 실행에는 무동작).
      if (cancel.isCancelRequested()) {
        finishCancelled(executionId);
        return;
      }

      // 위상 정렬로 실행 순서 결정
      List<PipelineStepResponse> executionOrder = topologicalSort(steps, stepDependencyMap);

      // 스텝별 실행 상태 추적
      Map<Long, String> stepStatuses = new HashMap<>();

      // 순서대로 스텝 실행
      boolean cancelled = false;
      for (PipelineStepResponse step : executionOrder) {
        Long stepExecId = stepIdToStepExecId.get(step.id());

        // 취소 체크포인트(WD-153) — 각 스텝 시작 전(= DAG 스텝 사이). 남은 스텝은 실행하지 않고 아래에서 SKIPPED(취소 사유)로 마감한다.
        if (cancel.isCancelRequested()) {
          cancelled = true;
          break;
        }

        // 의존 스텝이 모두 COMPLETED인지 확인
        boolean canExecute = true;
        List<Long> dependencies = stepDependencyMap.get(step.id());

        for (Long depStepId : dependencies) {
          String depStatus = stepStatuses.get(depStepId);
          if (!"COMPLETED".equals(depStatus)) {
            canExecute = false;
            break;
          }
        }

        if (!canExecute) {
          // 의존 스텝 실패/스킵으로 이 스텝도 SKIPPED 처리. 저장 문구는 오류가 아니라 실행하지 않은 이유다(WD-111 — 예전 영어
          // "Dependency failed or skipped" 는 오류처럼 읽혔다. 이미 저장된 옛 문구는 바꾸지 않고 웹이 SKIPPED 면 중립 안내로
          // 보인다).
          executionRepository.updateStepExecution(
              stepExecId,
              "SKIPPED",
              null,
              null,
              PipelineSqlErrorMessages.SKIPPED_MESSAGE,
              null,
              LocalDateTime.now(ZoneOffset.UTC));
          stepStatuses.put(step.id(), "SKIPPED");
          log.info("Step {} skipped due to failed dependency", step.name());
        } else {
          // 스텝 실행
          String status =
              executeStep(
                  stepExecId, step, pipelineId, pipelineName, userId, executorEnabled, cancel);
          stepStatuses.put(step.id(), status);
          // 스텝 안의 체크포인트가 취소를 알아챘다 — 그 스텝은 CANCELLED 로 기록됐다. 남은 스텝은 돌리지 않는다.
          if ("CANCELLED".equals(status)) {
            cancelled = true;
            break;
          }
        }
      }

      if (cancelled) {
        // 앞선 스텝이 실패했더라도 사용자가 멈춘 실행은 CANCELLED 로 끝낸다(남은 스텝을 돌리지 않은 이유가 취소이므로).
        finishCancelled(executionId);
        return;
      }

      // 전체 실행 최종 상태 결정
      boolean allCompleted = stepStatuses.values().stream().allMatch(s -> "COMPLETED".equals(s));
      boolean anyFailed = stepStatuses.values().stream().anyMatch(s -> "FAILED".equals(s));

      String finalStatus;
      if (allCompleted) {
        finalStatus = "COMPLETED";
      } else if (anyFailed) {
        finalStatus = "FAILED";
      } else {
        finalStatus = "COMPLETED"; // 일부 스킵됐지만 실패 없음
      }

      // 취소 요청이 없을 때만 최종 상태를 쓴다(WD-411) — 마지막 스텝이 문장을 실행하는 중에 들어온 취소는 스텝 사이 체크포인트가 더 없어
      // 놓쳤고, COMPLETED 로 끝나 연쇄 트리거가 하위 파이프라인을 돌렸다. 이미 반영된 스텝 결과는 그대로 두고 실행만 CANCELLED 로 마감한다.
      boolean won =
          executionRepository.finishUnlessCancelRequested(
              executionId, finalStatus, LocalDateTime.now(ZoneOffset.UTC));
      if (!won) {
        // 취소 요청이 있었거나(→ 취소 마감) 정리기 등이 먼저 마감했다(→ finishCancelled 가 조건부라 무동작). 어느 쪽이든 완료 이벤트를
        // 내지 않는다 — 내면 멈춘·중단된 실행이 정상 완료로 체인·알림을 일으킨다.
        finishCancelled(executionId);
        return;
      }
      log.info("Pipeline execution {} completed with status: {}", executionId, finalStatus);

      // 체인 트리거를 위한 완료 이벤트 발행.
      // 이 스레드(pipelineExecutor)는 TenantContextTaskDecorator 로 테넌트를 승계받은 상태이고,
      // @Async 리스너 제출도 이 스레드에서 일어나므로 테넌트가 그대로 이어진다 — 그래서 이벤트
      // 페이로드에 tenantId 를 따로 싣지 않는다. (P2-b Task 5)
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, finalStatus, pipelineCreatedBy));

    } catch (Exception e) {
      // 취소 예외가 스텝 밖까지 올라온 경우(방어) — 실패가 아니라 취소로 마감한다.
      if (PipelineExecutionCancelledException.findIn(e) != null) {
        finishCancelled(executionId);
        return;
      }
      // 취소를 요청한 뒤 예외로 끝난 실행도 FAILED 로 마감하지 않는다(WD-411) — FAILED 이벤트는 조건이 실패·항상인 연쇄 트리거와 실패
      // 알림을 일으켜, 사용자가 멈춘 흐름의 하위가 돈다.
      if (isStopRequestedQuietly(cancel)) {
        log.warn("Pipeline execution {} 취소 요청 뒤 예외로 끝남 — 취소로 마감", executionId, e);
        finishCancelled(executionId);
        return;
      }
      log.error("Pipeline execution {} failed with exception", executionId, e);
      // 스텝 실행 레코드가 하나도 생성되기 전에 던져진 예외(토폴로지 정렬 실패, DB 오류 등)는
      // 스텝 레벨 error_message로 남길 곳이 없어 원인이 완전히 유실됐다(#517).
      // 최상위 예외 메시지를 execution 레벨 error_message로 보존한다.
      boolean won =
          executionRepository.updateExecutionStatus(
              executionId, "FAILED", null, LocalDateTime.now(ZoneOffset.UTC), e.getMessage());
      if (!won) {
        log.info("Pipeline execution {} 이미 마감된 실행 — 완료 이벤트 생략", executionId);
        return;
      }

      // 실패 이벤트 발행
      applicationEventPublisher.publishEvent(
          new PipelineCompletedEvent(pipelineId, executionId, "FAILED", pipelineCreatedBy));
    }
  }

  /** 예외 경로에서 쓰는 중지 확인 — 확인마저 실패하면(DB 장애 등) 중지 요청이 없는 것으로 보고 원래대로 FAILED 로 마감한다. */
  private boolean isStopRequestedQuietly(ExecutionCancelCheck cancel) {
    try {
      return cancel.isCancelRequested();
    } catch (RuntimeException ex) {
      log.warn("중지 요청 확인 실패 — FAILED 로 마감합니다", ex);
      return false;
    }
  }

  /**
   * 취소된 실행을 마감한다(WD-153) — 아직 시작하지 않은 스텝을 SKIPPED(취소 사유)로, 실행을 CANCELLED 로 바꾼다. 두 쓰기는 한 트랜잭션({@code
   * cancelAndSkipPendingSteps})이고 모두 조건부라(PENDING 스텝만·끝나지 않은 실행만) 대기 중 취소로 이미 마감된 실행에는 무동작이다.
   *
   * <p><b>완료 이벤트를 발행하지 않는다.</b> PIPELINE_CHAIN 트리거는 조건이 ANY 여도 취소된 실행으로는 발화하지 않는다 — 사용자가 멈춘 흐름의 하위
   * 파이프라인이 이어서 돌면 "멈췄다"는 의도와 반대다. 완료 알림도 보내지 않는다(취소한 사람이 화면에서 결과를 이미 본다). 리스너 쪽에도 같은 방어를
   * 둔다(TriggerEventService#matchesCondition·NotificationService).
   *
   * <p><b>WD-230</b>: 멈춘 이유가 취소 요청이 아니라 정리기의 외부 마감(FAILED)일 수도 있다. 그때는 CANCELLED 쓰기가 조건부라
   * 지고(false), 정리기가 이미 닫은 스텝 행을 취소 문구로 덮으면 안 되므로 <b>상태 쓰기가 이긴 경우에만</b> 남은 스텝을 취소 사유로 SKIPPED
   * 처리한다(쓰기 순서를 상태 → 스텝으로 바꾼 이유). 이 메서드는 어느 경우에도 이벤트를 발행하지 않는다.
   */
  private void finishCancelled(Long executionId) {
    // 상태 쓰기와 남은 스텝 SKIPPED 를 한 트랜잭션으로 묶은 저장소 메서드 — 사이에서 죽어도 스텝이 PENDING 으로 남지 않는다.
    boolean won =
        executionRepository.cancelAndSkipPendingSteps(
            executionId, LocalDateTime.now(ZoneOffset.UTC));
    if (!won) {
      log.info("Pipeline execution {} 이미 마감된 실행 — 취소 마감 생략", executionId);
      return;
    }
    log.info("Pipeline execution {} 중지 요청 또는 외부 마감으로 실행을 멈췄습니다", executionId);
  }

  /**
   * Kahn's algorithm 기반 위상 정렬로 스텝 실행 순서를 결정한다.
   *
   * @param steps 전체 스텝 목록
   * @param stepDependencyMap 스텝 ID → 의존 스텝 ID 목록
   * @return 실행 순서가 보장된 스텝 목록
   */
  List<PipelineStepResponse> topologicalSort(
      List<PipelineStepResponse> steps, Map<Long, List<Long>> stepDependencyMap) {
    // 역방향 의존성 맵 (자식 → 부모) 및 진입 차수 맵 구성
    Map<Long, List<Long>> reverseDeps = new HashMap<>();
    Map<Long, Integer> inDegree = new HashMap<>();

    for (PipelineStepResponse step : steps) {
      reverseDeps.put(step.id(), new ArrayList<>());
      inDegree.put(step.id(), 0);
    }

    for (PipelineStepResponse step : steps) {
      List<Long> deps = stepDependencyMap.get(step.id());
      inDegree.put(step.id(), deps.size());

      for (Long depStepId : deps) {
        reverseDeps.get(depStepId).add(step.id());
      }
    }

    // Kahn's algorithm: 진입 차수 0인 노드부터 처리
    Queue<Long> queue = new LinkedList<>();
    for (PipelineStepResponse step : steps) {
      if (inDegree.get(step.id()) == 0) {
        queue.offer(step.id());
      }
    }

    List<Long> sortedStepIds = new ArrayList<>();

    while (!queue.isEmpty()) {
      Long currentStepId = queue.poll();
      sortedStepIds.add(currentStepId);

      for (Long childStepId : reverseDeps.get(currentStepId)) {
        inDegree.put(childStepId, inDegree.get(childStepId) - 1);
        if (inDegree.get(childStepId) == 0) {
          queue.offer(childStepId);
        }
      }
    }

    // ID → 스텝 응답 매핑 후 정렬 결과 반환
    Map<Long, PipelineStepResponse> stepMap = new HashMap<>();
    for (PipelineStepResponse step : steps) {
      stepMap.put(step.id(), step);
    }

    List<PipelineStepResponse> result = new ArrayList<>();
    for (Long stepId : sortedStepIds) {
      result.add(stepMap.get(stepId));
    }

    return result;
  }

  /**
   * 개별 파이프라인 스텝을 실행한다. 스크립트 타입(SQL/PYTHON/API_CALL/AI_CLASSIFY)에 따라 적절한 실행기를 호출하고 결과를 기록한다.
   *
   * @param stepExecId 스텝 실행 레코드 ID
   * @param step 실행할 스텝 정보
   * @param pipelineId 파이프라인 ID
   * @param pipelineName 파이프라인 이름 (로그/임시 데이터셋 생성에 사용)
   * @param userId 실행 사용자 ID
   * @param executorEnabled 외부 Python/API 실행기 활성화 여부
   * @return 실행 결과 상태 ("COMPLETED" 또는 "FAILED")
   */
  String executeStep(
      Long stepExecId,
      PipelineStepResponse step,
      Long pipelineId,
      String pipelineName,
      Long userId,
      boolean executorEnabled) {
    return executeStep(
        stepExecId,
        step,
        pipelineId,
        pipelineName,
        userId,
        executorEnabled,
        ExecutionCancelCheck.NONE);
  }

  /**
   * 개별 스텝 실행 — 협조적 취소 확인({@code cancel}, WD-153)을 받는다.
   *
   * <p>스텝 안의 체크포인트: SQL·PYTHON·실행기(firehub-executor) 호출은 그 호출 하나를 기다리므로 <b>호출 직전·직후</b>만 본다(진행 중인
   * 호출은 자기 타임아웃까지 끝까지 돈다). 로컬 API_CALL 은 페이지 사이·재시도 대기 중, AI_CLASSIFY 는 배치 사이·재시도 대기 중이다(실행기 안). 단
   * SQL 의 "직후"는 확인하지 않는다 — SQL 스텝은 출력 비우기와 적재가 한 트랜잭션으로 이미 커밋돼 되돌릴 수 없으므로, 그 스텝을 CANCELLED 로 적으면
   * 사실과 다르다. 그래서 정상 COMPLETED 로 남기고 다음 스텝 시작 전 체크포인트에서 멈춘다.
   *
   * @return "COMPLETED" / "FAILED" / "CANCELLED"(체크포인트가 취소를 알아챔 — 남은 스텝을 돌리지 않는다)
   */
  String executeStep(
      Long stepExecId,
      PipelineStepResponse step,
      Long pipelineId,
      String pipelineName,
      Long userId,
      boolean executorEnabled,
      ExecutionCancelCheck cancel) {
    LocalDateTime stepStartedAt = LocalDateTime.now(ZoneOffset.UTC);
    // 증분 스텝 잠금(WD-223) — 책갈피를 읽기 전에 잡고 책갈피 전진 뒤에 푼다. 실패·취소로 그 전에 끝나면 아래 finally 가 푼다.
    SessionAdvisoryLock.Held incrementalLock = null;
    // 열 구성이 바뀌어 다시 만든 러너 TEMP 의 되돌리기 재료(WD-238) — 옛 테이블이 보관본으로 옮겨져 있다. 출력이 커밋되면 보관본을 버리고, 실패·취소면
    // 아래 catch 가 되돌린다. 재생성은 커밋된 뒤 본 실행이 다른 연결·트랜잭션에서 돌아 한 트랜잭션으로 묶을 수 없기 때문이다.
    DatasetService.PipelineTempRebuild tempRebuild = null;

    try {
      // 스텝 상태를 RUNNING으로 업데이트
      executionRepository.updateStepExecution(
          stepExecId, "RUNNING", null, null, null, stepStartedAt, null);

      // Tibero 저장소 테넌트 판정(WD-77, 스펙 §5.1) — 이 스텝에서 한 번만 묻는다(테넌트 백엔드는 생성 후 불변이다). 모든 스텝 종류가
      // Tibero 에서 열렸다(Python 은 WD-78) — 종류별 분기가 이 값으로 Tibero 경로(db-executor·스테이징·조회 도우미)를 고른다.
      DataStoreBackend backend = pipelineDataSchema.backend();
      // 백엔드별로 갈리는 분기(후보 시각·비우기 문장·행 트리거·스크립트 토큰·스테이징 판정·로컬 Python·Tibero SQL 실행 본체·PG 래핑 SQL
      // 조립/실패 번역)는 방언이 맡는다(WD-231). PG 싱크(탐침·실행) 호출만 보안 불변식(SqlGateArchitectureTest) 때문에 이 메서드에 남아,
      // SQL 분기 두 곳(TEMP 탐침·실행)이 방언 타입으로 갈린다.
      StepDialect dialect = dialectFor(backend);

      // WD-30: 러너 TEMP 의 쓰기 후 허용 목록 확정 계획(출력 등급 처리가 채운다 — 허용 목록 필요 입력이 없거나 출력이 전부 교체되지 않으면 null).
      PipelineSecurityGate.OutputAllowlistPlan allowlistPlan = null;

      // 출력 데이터셋 ID 및 테이블명 결정 (임시 데이터셋 포함)
      Long outputDatasetId = step.outputDatasetId();
      // 이 스텝의 출력이 「이 스텝이 소유한 러너 TEMP」인가(WD-222). 스텝 조회(findByPipelineId)는 화면 표시·{{#N}} 참조를 위해 출력이
      // 비어 있으면 그 스텝의 TEMP id 를 채워(coalesce) 주고, 화면 저장은 그 id 를 명시 출력으로 되돌려 고정한다. 그래서 두 번째 실행부터는
      // 자동 생성(TEMP) 스텝도 출력 id 가 늘 채워져 있어, 아래 "출력 미지정 → TEMP 준비" 분기가 죽고 SELECT 열이 바뀌어도 TEMP 가 옛 열
      // 구성 그대로 남았다(열 추가 → INSERT 개수 불일치, 이름 변경 → 열 불일치 실패). 출력 id 가 이 스텝의
      // TEMP(source_pipeline_step_id =
      // 스텝 id)와 같으면 미지정과 똑같이 취급해, 실행마다 결과 열과 TEMP 스키마를 비교하고 다르면 다시 만든다(ensureStepTemp). 다른 스텝·
      // 다른 파이프라인의 TEMP 는 source_pipeline_step_id 가 달라 여기에 걸리지 않는다 — 그대로 명시 출력이다(WD-224 와 독립).
      // 이 스텝의 기존 TEMP 는 처음 필요할 때 한 번만 찾는다 — 소유 판정·자기 출력 판정(WD-426)·TEMP 준비(ensureStepTemp)가 같은 결과를
      // 쓰고, 필요 없는 스텝(출력 미지정 DML 등)은 찾지 않는다.
      final Supplier<Optional<Long>> existingStepTemp =
          once(() -> tempDatasetService.findExistingTempDataset(step.id()));
      final boolean ownedTempOutput =
          outputDatasetId == null || outputDatasetId.equals(existingStepTemp.get().orElse(null));

      String outputTableName = null;
      if (outputDatasetId != null) {
        outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElse(null);
      }

      // 전략 문자열 원본(미지정이면 기본 REPLACE). 아래 API_CALL 실행기 호출은 enum 이 아니라 이 원본을
      // 그대로 넘긴다 — strategy.name() 으로 바꾸면 소문자 레거시 값("append")이 대문자로 둔갑해 실행기가
      // 보는 값이 달라진다.
      String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";

      // 로드 전략을 이 메서드에서 딱 한 번 해석한다. load_strategy 컬럼에는 서버 쪽 enum·체크 제약이
      // 없어(PipelineStepRepository 는 null 만 "REPLACE" 로 매핑) 임의 문자열이 그대로 들어올 수 있으므로
      // 해석 실패는 REPLACE 로 폴백하고 경고를 한 번만 남긴다 — 예전에는 같은 폴백이 비SQL 분기와 SQL
      // 분기에 따로 하나씩 있었다. 대소문자 무시(LoadStrategy.parse)라 소문자 레거시 행("append")도
      // APPEND 로 해석된다.
      LoadStrategy strategy =
          LoadStrategy.parse(loadStrategy)
              .orElseGet(
                  () -> {
                    // 미지정(null)은 위에서 이미 "REPLACE" 로 바뀌어 해석에 성공하므로, 여기 오는 값은
                    // 오직 해석할 수 없는 값(오타·과거 값)뿐이다.
                    log.warn("Unknown load strategy '{}', falling back to REPLACE", loadStrategy);
                    return LoadStrategy.REPLACE;
                  });

      // Fix round 1, nit 5 — MERGE 는 SQL 스텝 전용 거부를 특정 타입 분기 안에만 두면(예전에는 실행기 끈
      // PYTHON 만 타던 로드 전략 블록에 있었다 — WD-29 에서 그 블록은 없어졌다) 다른 비SQL 타입은
      // 거기 도달하지 못한다 — 즉 레거시 PYTHON+MERGE 행이 실행기 켠 상태로 오면
      // 아무 거부도 없이 그냥 APPEND 처럼(비우지 않고) 조용히 실행된다. 저장 시점(PipelineService.
      // saveSteps)이 이미 이 조합을 거부하지만, 그 우회(레거시 데이터 등)에 대한 2차 방어는 스텝
      // 타입을 가리지 않고 걸어야 의미가 있으므로 모든 타입 분기보다 앞에 둔다.
      if (strategy == LoadStrategy.MERGE && !"SQL".equals(step.scriptType())) {
        throw new ScriptExecutionException("MERGE 는 SQL 스텝 전용입니다");
      }

      // 로드 전략(비우기·맞바꿈)은 이제 모든 비SQL 경로가 임시 테이블 맞바꿈으로 처리한다 —
      // API_CALL·AI_CLASSIFY·PYTHON(실행기 켬/끔 모두). SQL 스텝은 REPLACE 비우기를 본 문장과 같은
      // 트랜잭션으로 보낼 DELETE 선행 문장으로 만든다(SQL 분기, Task 4 원자성 · #731/#735 직렬화).
      // 예전의 "실행기 끈 PYTHON + REPLACE 는 truncate 후 자식이 직접 적재" 경로는 WD-29(R5)에서
      // 없앴다 — 자식은 이제 읽기 전용 슬롯 롤이라 적재를 API 가 하고, 그 REPLACE 는 실행기 켠 경로와
      // 같은 임시 테이블 맞바꿈이다(아래 PYTHON 분기).

      // 증분 처리 상태 — SQL 분기 안에서 정해지지만, 책갈피 전진은 실행 성공 이후(분기 밖)에 하므로
      // 메서드 스코프에 둔다.
      boolean incrementalStep = false;
      OffsetDateTime stepCursorCandidate = null;
      boolean stepWasFullRebuild = false;
      // 증분 스텝이 이번 실행에서 책갈피 이후 변경분만 읽는가(전체 재생성 예약·첫 실행이 아님) — 그때는 소유 TEMP 를 비운 채 다시 만들 수
      // 없다(WD-222, 아래 ensureStepTemp 호출 참고).
      boolean incrementalPartialRead = false;

      // 스크립트 타입별 실행
      String executionLog;
      if ("SQL".equals(step.scriptType())) {
        // 출력 비우기를 INSERT 와 같은 트랜잭션으로 보내기 위한 선행 문장 — 따로 커밋하면 실패 시 출력이
        // 빈 채로 남는다(Task 4). SELECT 자동 적재(REPLACE)일 때만 채워진다 — 아래에서 결정한다.
        List<String> preStatements = new ArrayList<>();
        String sql = step.scriptContent().trim();
        sql = resolveStepReferences(sql, pipelineId, step);

        // ── 증분 처리({{last_run_at}}) ────────────────────────────────────────────────
        // 책갈피 후보값은 SQL 실행 "전에" 잡아야 한다 — 실행 도중 커밋되는 트랜잭션의 행
        // (_updated_at = 그 트랜잭션 시작 시각)을 다음 실행이 놓치지 않기 위해서다. 실행 후에 잡으면
        // 그 구간이 영원히 비어 버린다. 값 자체의 안전한 계산(시각 먼저 → 활성 트랜잭션 최소값)은
        // V123 의 DB 함수가 책임진다.
        // 판정은 저장·예약 API 와 공유하는 단일 헬퍼로 내린다(#739) — 세 곳이 어긋나면 소비되지 않는 예약이 생긴다.
        incrementalStep = LastRunAtPlaceholder.isIncrementalStep(step.scriptType(), sql);
        if (incrementalStep) {
          // 저장 시점(PipelineService.saveSteps)이 이미 REPLACE+증분을 거부하지만, 그 검증을 우회해
          // 저장된 레거시 행에 대한 2차 방어를 둔다 — REPLACE 로 돌면 매 실행 출력에 변경분만 남는다.
          if (strategy == LoadStrategy.REPLACE) {
            throw new ScriptExecutionException(
                "{{last_run_at}} 은 REPLACE 와 함께 쓸 수 없습니다(매 실행 출력이 변경분만 남습니다). MERGE 를 사용하세요.");
          }
          // 같은 스텝의 겹친 실행 직렬화(WD-223) — 책갈피·후보를 읽기 "전에" 잡는다. 기다린 뒤에 읽어야 앞 실행이 전진시킨
          // 책갈피를 본다(후보를 먼저 잡고 기다리면 advanceCursor 가 책갈피를 뒤로 되돌린다). 비증분 스텝은 잡지 않는다.
          incrementalLock = incrementalStepLock.acquire(step.id(), step.name(), cancel);
          StepCursor cursor =
              stepRepository
                  .findCursor(step.id())
                  .orElseThrow(
                      () -> new ScriptExecutionException("증분 스텝의 책갈피를 찾을 수 없습니다: " + step.name()));
          stepWasFullRebuild = cursor.fullRebuildPending();
          // 전체 재생성 예약이면 책갈피를 무시하고 전체를 읽는다(-infinity).
          OffsetDateTime injected = stepWasFullRebuild ? null : cursor.lastRunAt();
          incrementalPartialRead = injected != null;
          // Tibero 테넌트는 _UPDATED_AT 이 Tibero 서버 시계(SYSTIMESTAMP)의 벽시계라, 후보도 Tibero 시계와 Tibero 의 열린
          // 트랜잭션에서 잡고(PG 시계와 섞지 않는다) 플레이스홀더도 Tibero 서버 벽시계로 변환하는 식으로 치환한다. 후보를 SQL
          // 실행 전에 잡는다는 불변식은 두 방언이 같다. 책갈피 저장(advanceCursor)은 PG timestamptz 그대로이고 값만 Tibero
          // 시계다.
          stepCursorCandidate = dialect.captureIncrementalCandidate();
          sql = LastRunAtPlaceholder.substitute(sql, injected, backend);
          // 소스 테이블의 백필(_updated_at 컬럼+트리거) 완료 여부를 여기서 따로 확인하지 않는다.
          // V124 백필은 락을 못 잡은 테이블을 건너뛸 수 있지만, 그 결과는 fail-closed 다:
          // 컬럼이 없으면 사용자 SQL 의 `_updated_at >= {{last_run_at}}` 이 PG 에서
          // "column _updated_at does not exist" 로 즉시 실패한다. 그리고 "컬럼은 있는데 트리거가
          // 없는"(=증분이 조용히 틀리는) 조합은 V124·수리 스크립트가 컬럼과 트리거를 한 (서브)
          // 트랜잭션에 넣기 때문에 만들어지지 않는다. 즉 여기 가드를 두어도 막을 새로운 사고가 없다.
          // (초안에서 실제로 가드를 넣었다가 걷어냈다 — 이 경로의 SqlValidator 는 미한정 테이블
          //  참조를 거부하므로 미한정 이름 집합이 항상 비어 가드가 무조건 통과하는 죽은 코드였다.
          //  "보호한다고 주장하는 죽은 코드"는 보호가 없는 것보다 나쁘다.)
          executionRepository.setInjectedLastRunAt(stepExecId, injected);
        }

        // 실행 직전 재검증 — 저장 이후 정책 변경/우회 방지. probe/wrappedSql 결합은 이 검증 통과 후이므로
        // 단일 statement·세미콜론 없음이 보장되어 구조적으로 안전하다. (#136)
        // 조회/DML 판정도 이 검증이 파싱한 AST 로 얻는다(WD-93) — 저장 시점(PipelineService#validateAndJudgeQuery)과
        // 같은 메서드다. 예전 어휘 판정은 앞 주석·CTE 이름·VALUES 등을 DML 로 오판해 REPLACE 출력만 비운 채 완료됐다.
        // 검증기는 테넌트 백엔드에 맞춰 PipelineDataSchema 가 고른다 — PG 는 기존 빈 그대로,
        // Tibero 는 GENIA_T{id} 스키마·Tibero 가드 검증기다(저장 시점과 같은 선택, WD-77).
        boolean isSelect =
            pipelineDataSchema.validator().validate(sql) == SqlValidator.StatementKind.QUERY;
        // 보안 등급(S2, 스펙 §4.2 5행): 실행 주체(수동=요청자, 트리거=트리거 생성자) 기준 판정. 판정 문자열은 스텝 참조·증분
        // 플레이스홀더를 치환하고 위에서 검증한 바로 이 sql 이다 — DML 분기는 이 문자열을 그대로, SELECT 분기는 러너가 만든
        // INSERT/MERGE 래퍼 + 이 문자열을 실행하므로 판정한 테이블 = 실행되는 테이블이다(가드 계약). 원문(scriptContent)이나
        // 주석을 뗀 사본을 판정하면 안 된다. 사용자 SQL 을 실제로 돌리는 probe(SqlColumnProbe)·실행보다 먼저다. 거부는 예외로
        // 스텝 실패가 되고, 메시지는 구분 불가 문구라 실행 이력에 숨김 데이터셋 이름이 남지 않는다.
        // 실행 주체 자격은 이 스텝에서 한 번만 계산해 이 스텝의 모든 판정(입력·TEMP 삭제 전·출력)에 쓴다.
        PipelineSecurityGate.RunAs runAs = pipelineSecurityGate.runAs(userId);
        // 선언 입력(inputDatasetIds)도 전파 입력이다(스펙 §4.5) — 판정 문자열(sql)과 별개로 VIEW 판정 후 등급만 합친다.
        // 사용자 SQL 만의 판정(sqlAccess)은 아래 자기 출력 판정(WD-426)이 따로 쓴다 — 선언 입력은 SQL 이 읽지 않으면 비워도 잃는 것이 없다.
        SqlAccessResult sqlAccess = pipelineSecurityGate.checkStepSqlForRun(runAs, sql);
        SqlAccessResult access =
            pipelineSecurityGate.mergeDeclaredInputs(runAs, sqlAccess, step.inputDatasetIds());
        // REPLACE 가 비울 출력을 사용자 SQL 이 스스로 읽거나 고치는가(WD-426, 판정 규칙은 OutputSelfReference) — 저장 검증의 2차
        // 방어다(저장 뒤 출력 변경·{{#N}} 이 같은 출력을 가리킴·레거시 행). TEMP 준비·probe 보다 먼저 한 번만 판정해, 거부된 스텝은
        // 아무것도 만들거나 지우지 않는다. 비울 출력은 명시 출력, 또는 소유 TEMP 로 가는 SELECT 면 아래 TEMP 준비가 재사용할 기존
        // TEMP 다(다시 만들어도 같은 id — 없으면 새 빈 TEMP 라 읽을 것이 없다).
        OutputSelfReference.Plan selfReference = OutputSelfReference.Plan.AS_IS;
        if (strategy == LoadStrategy.REPLACE) {
          Long clearTarget =
              isSelect && ownedTempOutput ? existingStepTemp.get().orElse(null) : outputDatasetId;
          String judgedSql = sql;
          selfReference =
              OutputSelfReference.plan(
                  isSelect,
                  dialect instanceof TiberoStepDialect,
                  sqlAccess,
                  () -> pipelineDataSchema.validator().isUpdateOrDelete(judgedSql),
                  clearTarget);
          if (selfReference.rejected()) {
            throw new ScriptExecutionException(selfReference.message() + ".");
          }
        }
        // 자기 출력 다시 채우기 — 비우기는 본 문장 안(PgStepDialect.wrapSelect),
        // 선행 문장은 OutputClearStatement#lockOnly 다(PG 전용 — Tibero 는 위에서 거부된다).
        boolean reloadFromOwnOutput =
            selfReference == OutputSelfReference.Plan.RELOAD_FROM_OWN_OUTPUT;
        // 이번 실행에서 SQL 스텝 출력을 위해 임시 데이터셋을 자동 생성/재사용했는지 여부.
        // 예약어 컬럼명 별칭 처리(renameReservedColumn*)는 이 경로에서만 적용해야 한다 — 사용자가
        // 명시적으로 지정한 기존 데이터셋(outputDatasetId가 원래부터 있던 경우)은 실제로 "id"라는
        // 정상 사용자 컬럼을 가질 수 있으므로 그 이름을 그대로 매칭해야 한다(#645).
        boolean tempDatasetAutoCreated = false;
        // 그중 이번 실행에서 TEMP 를 새로 만들었는지(빈 테이블) — 새 TEMP 만 입력 최대 등급으로 "정확히" 맞출 수 있다(낮추기 포함).
        // 재사용 TEMP 는 이전 실행 데이터가 남아 있을 수 있어 상향만 한다(PipelineSecurityGate#enforceOutputLevel).
        boolean tempDatasetFresh = false;
        // probe 결과를 스텝당 한 번만 얻어 재사용한다 — 아래 두 블록이 같은 sql 을 각각 probe 하면
        // 테넌트 풀 대여·트랜잭션·왕복이 두 벌 나가고, 그 사이 풀이 축출·close() 될 틈까지 생긴다.
        // SELECT 가 아니면 probe 자체가 필요 없으므로 지연 획득한다.
        List<ColumnInfo> probedColumns = null;
        // Tibero 출력 없는 SELECT 가 TEMP 생성을 위해 이미 probe 했으면 그 결과(적재가 재사용 — probe 를 두 번 보내지 않는다).
        DbExecutorClient.ProbeResult tiberoProbe = null;

        // SELECT이고 출력이 없거나 이 스텝 소유 TEMP 면 임시 데이터셋을 준비한다(없으면 생성, 열이 바뀌었으면 재생성 — WD-222).
        // Tibero 도 같은 경로다(WD-77 2단계) — 컬럼·타입은 아래에서 db-executor probe 로 얻는다.
        if (isSelect && ownedTempOutput) {
          tempDatasetAutoCreated = true;
          // SELECT * FROM {{#N}} 처럼 이전 스텝(또는 실제 데이터셋)의 결과를 그대로 재사용하면
          // 결과 컬럼에 시스템 예약 컬럼(id/import_id/created_at/_updated_at)이 그대로 섞여 들어온다.
          // V124 백필 이후 _updated_at 은 모든 데이터셋 테이블에 있으므로 SELECT * 는 항상 이 경로를 탄다.
          // 이 컬럼들을 그대로 새 임시 데이터셋의 사용자 컬럼으로 넘기면
          // DataTableService의 예약어 가드(사용자가 신규 데이터셋에 직접 그 이름을 짓는 것을 막기 위한 것)에
          // 걸려 실행이 항상 실패한다(#645). 자동 생성 경로에서만 예약어 컬럼명을 자동으로
          // 별칭 처리(rename-on-conflict)해 우회한다 — 사용자가 명시적으로 짓는 다른 스텝 타입의
          // 출력 컬럼명 검증은 그대로 유지한다.
          if (dialect instanceof TiberoStepDialect tiberoDialect) {
            // Tibero 는 db-executor probe 로 컬럼·타입을 얻는다(PG probe 는 쓰지 않는다). 같은 결과를 아래 적재에서 다시 쓴다.
            // PG 탐침(sqlColumnProbe)은 보안 불변식 때문에 방언이 아니라 이 메서드에 남아, 그래서 여기서 방언 타입으로 갈린다.
            tiberoProbe = tiberoDialect.probeTibero(sql);
            probedColumns = tiberoDialect.tiberoResultColumns(tiberoProbe);
          } else {
            probedColumns = sqlColumnProbe.columnsWithTypes(sql);
          }
          List<ColumnInfo> selectColumns = renameReservedColumns(probedColumns);
          // 결과 컬럼명이 데이터셋 이름 규칙에 맞는지 TEMP 생성 전에 본다(WD-111). 맞지 않으면 데이터셋 공용 검증의
          // 영어 문구(Invalid name: ?column?. Must match …)로 실패해, 별칭 없는 식 컬럼(SELECT 1 → ?column?)에 AS 를
          // 붙이면 된다는 걸 알 수 없었다. 규칙은 DataTableNames 하나를 쓰고, 안내는 이 경로에서만 SQL 문맥으로 낸다.
          rejectInvalidResultColumnNames(selectColumns);

          // 증분 스텝이 변경분만 읽는 실행이면 열이 바뀐 TEMP 를 다시 만들지 않는다(WD-222) — 다시 만든 TEMP 는 비어 있는데 이번 SQL 은
          // 책갈피 이후 변경분만 채우고 책갈피까지 전진해, 그 전 행이 조용히 영영 빠진다. 전체 재생성 예약 뒤 실행하면(-infinity + 출력 비우기)
          // 정상으로 다시 채워진다 — 그 안내와 함께 실패시킨다.
          // TEMP 자신을 읽는 실행(WD-426)도 같다 — SELF_READ_TEMP_SCHEMA_CHANGED_MESSAGE. 증분 스텝은 REPLACE 가
          // 아니라 둘이 함께 오지 않는다.
          String rebuildRefusal =
              incrementalPartialRead
                  ? INCREMENTAL_TEMP_SCHEMA_CHANGED_MESSAGE
                  : reloadFromOwnOutput ? SELF_READ_TEMP_SCHEMA_CHANGED_MESSAGE : null;
          StepTemp temp =
              ensureStepTemp(
                  step,
                  existingStepTemp.get(),
                  selectColumns,
                  pipelineId,
                  pipelineName,
                  userId,
                  runAs,
                  "",
                  rebuildRefusal);
          tempDatasetFresh = temp.fresh();
          tempRebuild = temp.rebuild();
          outputDatasetId = temp.datasetId();
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 즉시 truncate 하지 않는다 — 임시 데이터셋도 이번 실행 재사용 시 이전 실행 결과를
          // 담고 있어(findExistingTempDataset) 일반 출력 테이블과 같은 원자성 문제를 겪는다. 비우기는
          // 아래 REPLACE 판단 블록에서 DELETE 선행 문장으로 통일해 처리한다(Task 4).
        } else if (ownedTempOutput && outputDatasetId != null) {
          // 비SELECT 가 이 스텝 소유 TEMP 에 직접 쓴다 — ensureStepTemp 를 거치지 않으므로 쓰기 전에 크래시 흔적을 맞춘다(WD-256).
          tempDatasetService.reconcileTempDataset(outputDatasetId);
        }

        // 출력 비우기 결정 — REPLACE 는 SELECT(자동 적재)든 비SELECT(사용자가 직접 쓴
        // INSERT/UPDATE/DELETE)든 DELETE 를 본 문장과 <b>같은 트랜잭션</b>으로 보낼 선행 문장으로 쌓는다.
        //
        // 예전에는 비SELECT 만 여기서 별도 트랜잭션으로 즉시 truncate 했다. 그러면 (1) 선행 문장이 없어
        // 출력 테이블 단위 직렬화 잠금(#731, SqlScriptExecutor.OUTPUT_LOCK_SQL)이 걸리지 않고, (2) 비우기가
        // 먼저 커밋되므로, 같은 출력에 실행이 겹치면 두 실행이 각자 비운 뒤 각자 적재해 모든 행이 두 번
        // 들어갔다(#735 — 둘 다 완료로 끝난다). 선행 문장으로 통일하면 실행기를 끈 경로(sqlExecutor)와
        // 켠 경로(firehub-executor) 모두 "잠금 → 비우기 → 사용자 DML → 커밋"이 한 트랜잭션이 되고,
        // 잠금 키가 같은 선행 문장에서 나오므로 SELECT 자동 적재 실행과도 서로 배타다. 덤으로 사용자
        // DML 이 실패하면 비우기도 함께 롤백되어 이전 출력이 남는다(SELECT 경로와 같은 계약).
        // 여기에 truncateTable 을 다시 넣지 말 것.
        //
        // 알 수 없는 loadStrategy 는 이미 위(메서드 상단)에서 경고와 함께 REPLACE 로 해석됐으므로, 여기는
        // REPLACE/APPEND/MERGE 세 가지만 본다 — 알 수 없는 값이 조용히 아무것도 비우지 않고 매 실행마다
        // 행이 누적되던 회귀(Fix round 1, 리뷰 지적 1)는 그 해석에서 이미 막힌다.
        //
        // MERGE 는 APPEND 처럼 출력을 비우지 않는다 — MERGE 의 존재 이유 자체가 "기존 행은 그대로 두고
        // PK 로 upsert"이므로, 여기서 비우면 그 전 실행 결과가 통째로 사라진 뒤 이번 실행분만 남는다
        // (REPLACE 와 다를 게 없어진다). preStatements 도 비워 둔다 — 아래 SELECT 래핑 분기가 MERGE 일 때
        // 자체적으로 사용하지 않는다.
        boolean isMerge = strategy == LoadStrategy.MERGE;
        if (strategy == LoadStrategy.REPLACE) {
          if (outputTableName != null) {
            // Tibero 는 같은 계약의 Tibero 판 비우기 문장 — db-executor 가 선행문 형식을 다시 검사하고 대상 테이블을 먼저 잠근 뒤 같은
            // 트랜잭션에서 실행한다(#731/#735 와 같은 직렬화·원자성). 자기 출력 다시 채우기는
            // OutputClearStatement#lockOnly(WD-426).
            preStatements.add(
                reloadFromOwnOutput
                    ? OutputClearStatement.lockOnly(outputTableName)
                    : dialect.outputClearStatement(outputTableName));
          }
        }

        // (stepWasFullRebuild 는 증분 스텝일 때만 참이 될 수 있으므로 증분 여부를 따로 묻지 않는다.)
        // 전체 재생성 예약이 걸린 증분 스텝은 이번 실행에서 출력을 통째로 비운다 — 증분 스텝은 APPEND/
        // MERGE 라 평소에는 출력을 비우지 않으므로, 예약을 해소하려면 여기서 DELETE 선행 문장을 넣어야
        // 한다. 본 INSERT 와 같은 트랜잭션이므로 실패하면 출력이 그대로 보존된다(Task 4 와 같은 계약).
        // 위 REPLACE 블록과 중복될 일은 없다 — 증분 스텝은 바로 위에서 APPEND/MERGE 가 아니면 예외를
        // 던지므로, 여기 도달하는 증분 스텝의 preStatements 는 언제나 빈 목록이다.
        //
        // <b>isSelect 가 반드시 필요하다.</b> SELECT 자동 적재 경로만이 이 DELETE 뒤에 출력을 다시
        // 채운다(INSERT INTO ... SELECT 래핑). 사용자가 직접 쓴 INSERT/UPDATE/DELETE 스텝
        // (APPEND + {{last_run_at}} 조합은 저장 시점에 거부되지 않는다)에 이 DELETE 를 얹으면, 출력을
        // 비운 뒤 사용자 DML 이 그 자리를 채운다는 보장이 전혀 없어 출력이 빈 채로 COMPLETED 가 되고
        // 책갈피까지 전진한다 — 조용한 전량 손실이다. 비SELECT 증분 스텝의 "전체 재생성"은 출력을
        // 비우는 것이 아니라 <b>전체 읽기</b>(-infinity 치환)까지만을 뜻한다. 무엇을 다시 쓸지는
        // 사용자 DML 이 스스로 정한다.
        if (stepWasFullRebuild && isSelect && outputTableName != null) {
          // Tibero 는 REPLACE 와 같은 Tibero 판 비우기 문장 — db-executor 선행문 규칙(PreStatementRule)은 이 형식만 받고
          // 대상 테이블을 먼저
          // 잠근다. PG 형식(OutputClearStatement)을 보내면 실행기가 거부한다.
          preStatements.add(dialect.outputClearStatement(outputTableName));
        }

        // 출력 등급(판단 사항 5·스펙 §4.5): SELECT 는 러너가 붙이는 래퍼의 INSERT 대상이 판정 문자열에 없으므로 여기서 본다 — 러너가
        // 만든 TEMP·지정 출력 모두 입력 최대 등급보다 낮으면 자동 상향(지정 출력은 볼 수 있어야 한다). 사용자 DML 스텝의 지정 출력도
        // REPLACE 면 비우기(DELETE) 선행 문장의 대상이 되므로 실행 주체가 볼 수 있어야 하고, DML 쓰기 대상은 입력보다 낮으면 자동
        // 상향한다. 아래 실행(선행 문장 포함)보다 먼저다.
        // 러너 소유 TEMP 여부는 게이트가 DB 로 따로 판정한다 — 비SELECT(사용자 DML) 스텝은 위 TEMP 준비 분기를 타지 않고 들어온 출력
        // (coalesce 폴백으로 채워진 소유 TEMP 포함)을 그대로 쓰기 때문이다.
        if (outputDatasetId != null) {
          if (isSelect) {
            allowlistPlan =
                pipelineSecurityGate.enforceOutputLevel(
                    access,
                    outputDatasetId,
                    step.id(),
                    tempDatasetFresh,
                    // 출력 전부 교체 = REPLACE 비우기 또는 증분 전체 재구축(위 preStatements 의 DELETE 와 같은 조건) — WD-30
                    // 쓰기 후 넓힘 허용
                    strategy == LoadStrategy.REPLACE || stepWasFullRebuild,
                    runAs);
          } else {
            pipelineSecurityGate.requireOutputVisible(outputDatasetId, runAs);
          }
        }
        if (!isSelect) {
          // DML 쓰기 대상 자동 상향(스펙 §4.5) — 출력 지정이 없는 DML 도 대상이다. 가드는 PIPELINE_RUN 에서 쓰기 하향을 거부하지 않는다.
          pipelineSecurityGate.propagateToWriteTargets(access, runAs);
        }

        // Tibero 행 트리거 보장(사용자 DML·증분) — 보안 게이트·출력 등급 처리가 끝난 뒤라 거부된 SQL 은 DDL 을 만들지 않는다. PG 는 무동작.
        dialect.prepareSqlExecution(sql, isSelect, incrementalStep, outputTableName);
        SqlStepRun sqlRun =
            new SqlStepRun(
                sql,
                preStatements,
                isSelect,
                outputTableName,
                outputDatasetId,
                isMerge,
                tempDatasetAutoCreated,
                tiberoProbe);
        // 남는 백엔드 분기(WD-231, 의도적): Tibero 는 실행 본체 전체를 방언이 맡지만, PG 싱크
        // (ExecutorClient.executeSql·SqlScriptExecutor.execute·SqlColumnProbe.columnsWithTypes)는
        // SqlGateArchitectureTest 가 보안 판정(checkStepSqlForRun)과 같은 이 메서드 안에서만 부르게 강제한다 —
        // 방언·람다·도우미로 옮기면 판정과 실행이 갈라진다. 그래서 PG 는 싱크가 아닌 조립·번역만 방언에 맡기고
        // 싱크는 여기서 직접 부른다.
        if (dialect instanceof TiberoStepDialect tiberoDialect) {
          // Tibero 테넌트: 컬럼 탐지·실행 모두 db-executor 로만 한다. executorEnabled 와 무관하고 PG 실행기로
          // 폴백하지 않는다(스펙 §3). 여기까지 오면 검증·보안 게이트·출력 등급 처리가 이미 끝났다(사용자 SQL 은 게이트 통과 뒤에만
          // 실행된다 — probe 포함).
          executionLog = tiberoDialect.executeSql(sqlRun, cancel);
        } else if (isSelect && outputTableName != null && outputDatasetId != null) {
          PgStepDialect pgDialect = (PgStepDialect) dialect;
          // SELECT 자동 적재: 컬럼 추출 → 매칭 → INSERT INTO ... SELECT 래핑(매칭·래핑은 PG 방언이 한다).
          // 위 자동 생성 블록이 이미 probe 했으면 그 결과를 그대로 쓴다(이름은 ColumnInfo 에서 뽑는다).
          if (probedColumns == null) {
            probedColumns = sqlColumnProbe.columnsWithTypes(sql);
          }
          PgStepDialect.WrappedSelect wrapped =
              pgDialect.wrapSelect(sqlRun, probedColumns, reloadFromOwnOutput);

          // 취소 체크포인트(WD-153) — SQL 실행 직전. 아직 출력에 아무것도 쓰지 않았다(REPLACE 비우기도 이 문장과 같은 트랜잭션이다).
          cancel.checkpoint(0, false);
          if (executorEnabled) {
            var result = executorClient.executeSql(wrapped.sql(), preStatements);
            if (!result.success()) {
              // MERGE 면 원본 키 중복 등을 PK 이름으로 안내하고, 아니면 공통 실행 실패 문구다(번역은 방언).
              throw pgDialect.translateExecutorFailure(
                  result.pgError(), result.error(), wrapped.mergePkColumns());
            }
            executionLog = result.executionLog();
          } else {
            try {
              executionLog = sqlExecutor.execute(preStatements, wrapped.sql());
            } catch (ScriptExecutionException e) {
              // 번역할 MERGE 오류가 아니면 방언이 같은 예외를 그대로 돌려준다(예전 rethrow 와 같다).
              throw pgDialect.translateLocalFailure(e, wrapped.mergePkColumns());
            }
          }
        } else {
          PgStepDialect pgDialect = (PgStepDialect) dialect;
          // 기존 INSERT/UPDATE/DELETE는 그대로 실행한다. REPLACE 면 preStatements 에 출력 비우기
          // DELETE 가 들어 있어 사용자 DML 과 같은 트랜잭션·같은 출력 잠금 아래에서 실행된다(#735).
          // APPEND/MERGE 면 빈 목록이다(증분 전체 재생성 DELETE 는 isSelect 게이트가 걸려 여기로 오지
          // 않는다).
          // 취소 체크포인트(WD-153) — 사용자 DML 실행 직전.
          cancel.checkpoint(0, false);
          if (executorEnabled) {
            var result = executorClient.executeSql(sql, preStatements);
            if (!result.success()) {
              throw pgDialect.translateExecutorFailure(result.pgError(), result.error(), null);
            }
            executionLog = result.executionLog();
          } else {
            executionLog = sqlExecutor.execute(preStatements, sql);
          }
        }
      } else if ("PYTHON".equals(step.scriptType())) {
        // 인가 게이트: 명시적 python_execute 권한 필요
        if (!permissionChecker.hasPermission(userId, "pipeline:python_execute")) {
          throw new ScriptExecutionException(
              "Python 스크립트 실행에는 'pipeline:python_execute' 권한이 필요합니다. " + "관리자에게 이 기능 활성화를 요청하세요.");
        }
        // escalation 코드 차단 — 저장 시 검증을 우회해 저장된 스텝(직접 DB 삽입 등)에 대한 실행 시 2차 방어 (#270)
        pythonScriptValidator.validate(step.scriptContent());
        // 보안 등급(코드리뷰 CR2·후속 F1): 이미 있던 출력은 실행 주체가 볼 수 있어야 쓴다 — 판정은 아래 enforcePythonOutputLevel 이
        // 한 번 한다(코드리뷰 8). 그 호출 전까지 들어온 출력을 비우거나 쓰지 않으므로 "비우기·맞바꿈·적재 전" 순서가 지켜진다.
        // 입력 읽기는 SQL 관문으로 판정하지 않고 DB 권한(실행 주체 등급의 읽기 슬롯 롤, WD-29)으로 막는다. 출력 등급은 그 슬롯이 읽을 수
        // 있던 최대 등급으로 올린다(아래, 공통 결정 R4).
        // 실행 주체 자격은 이 스텝에서 한 번만 계산해 이 스텝의 모든 판정(출력·TEMP 삭제 전)에 쓴다.
        PipelineSecurityGate.RunAs pyRunAs = pipelineSecurityGate.runAs(userId);
        boolean pyTempFresh = false;
        // 이 스텝의 TEMP 를 ensureStepTemp 로 준비했는지(그 안에서 맞춤까지 한다).
        boolean pyTempPrepared = false;
        // 출력이 없거나 이 스텝 소유 TEMP 이고 pythonConfig에 outputColumns가 있으면 임시 데이터셋 준비(WD-222 — 열이 바뀌면 재생성).
        // outputColumns 가 없으면 들어온 출력(소유 TEMP 포함)을 그대로 쓴다(이전 동작 유지).
        if (ownedTempOutput && step.pythonConfig() != null) {
          com.smartfirehub.pipeline.dto.PythonStepConfig pythonStepConfig =
              objectMapper.convertValue(
                  step.pythonConfig(), com.smartfirehub.pipeline.dto.PythonStepConfig.class);
          if (pythonStepConfig.outputColumns() != null
              && !pythonStepConfig.outputColumns().isEmpty()) {
            List<ColumnInfo> pythonColumns =
                pythonStepConfig.outputColumns().stream()
                    .map(col -> new ColumnInfo(col.name(), col.type()))
                    .toList();
            StepTemp pyTemp =
                ensureStepTemp(
                    step,
                    existingStepTemp.get(),
                    pythonColumns,
                    pipelineId,
                    pipelineName,
                    userId,
                    pyRunAs,
                    "Python ");
            pyTempFresh = pyTemp.fresh();
            tempRebuild = pyTemp.rebuild();
            outputDatasetId = pyTemp.datasetId();
            outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
            pyTempPrepared = true;
          }
        }
        // outputColumns 가 없어 들어온 소유 TEMP 를 그대로 쓰는 경우 — ensureStepTemp 를 거치지 않으므로 쓰기 전에 크래시 흔적을
        // 맞춘다(WD-256).
        if (!pyTempPrepared && ownedTempOutput && outputDatasetId != null) {
          tempDatasetService.reconcileTempDataset(outputDatasetId);
        }
        // 출력 등급(스펙 §4.5·공통 결정 R4): 실행 주체 자격 이하 & 허용 목록 아닌 최고 등급으로 상향(새 TEMP 는 정확히 맞춤). 들어온 출력
        // (지정 출력·재사용 TEMP)의 "쓰기 전 VIEW" 도 여기서 본다(enforceOutputLevel). 출력 비우기·맞바꿈·적재보다 먼저다. 거부되면 이
        // 트랜잭션의 상향도 롤백되고, 거부 감사는 트랜잭션 완료 후(커밋·롤백 무관) 기록된다.
        // 읽기 rank: 실행 주체 자격이 아니라 이 스텝이 다루는 데이터(선언 입력 + 자기 출력)의 최고 등급(자격이 상한). 슬롯 선택과 출력 등급이
        // 같은 값을 쓴다 — 외부 API 만 부르는 스크립트가 실행 주체 자격 때문에 출력이 '민감'으로 올라가던 결함 수정.
        int pyReadRank =
            pipelineSecurityGate.pythonReadRank(outputDatasetId, step.inputDatasetIds(), pyRunAs);
        if (outputDatasetId != null) {
          pipelineSecurityGate.enforcePythonOutputLevel(
              outputDatasetId, step.id(), pyTempFresh, pyRunAs, pyReadRank);
        }
        // 등급별 읽기 슬롯(WD-29): 실행 직전 GRANT 를 계산값에 맞추고(JIT) 실행 주체 슬롯을 정한다. 실패하면 executor·자식 프로세스를 부르지
        // 않는다(fail-closed) — 테넌트 실행 롤로 대체하는 경로는 없다.
        // 위치: 위 보안 판정(enforcePythonOutputLevel 의 출력 VIEW·등급 상향)이 먼저 돌아 그 판정 테스트가
        // 공허해지지 않고, REPLACE 임시 테이블 생성보다는 앞이라 준비 실패가 임시 테이블을 만들거나 지우지 않는다.
        // 트랜잭션: prepareForRun 은 REQUIRES_NEW 트랜잭션에서 advisory 잠금·REVOKE/GRANT 를 한다.
        // 이 러너(executeAsync·executeStep)는 @Transactional 이 아니고(비동기 스레드,
        // PipelineExecutionService 가 커밋 후 위임), 위 TEMP 생성 DDL 도 자기 트랜잭션에서 이미
        // 커밋됐다 — 같은 테이블에 미커밋 DDL 을 쥔 바깥 트랜잭션이 없어 자기 교착이 생기지 않는다.
        int readSlot;
        // 실행 중 등급 정의 변경으로 슬롯 롤 세션이 끊겼는지 가리는 기준 시각(실패 문구용 — 판정 아님).
        final long pyStartedAt = System.currentTimeMillis();
        final long pyTenantId = pyRunAs.clearance().tenantId();
        try {
          readSlot = pythonReadGrants.prepareForRun(pyRunAs.clearance(), pyReadRank);
        } catch (PythonReadAccessException e) {
          throw new ScriptExecutionException(e.getMessage(), e);
        }
        // Tibero(WD-78): 스크립트는 슬롯 계정으로 db-executor 를 거쳐 읽는다 — 그 자격인 스크립트 토큰을 슬롯 준비 **뒤에** 발급한다.
        // prepareForRun 이 등급 변경을 반영하며 세대를 올릴 수 있으므로(GRANT 전에 올림) 세대는 그 뒤에 읽어야 새 토큰이 거부되지 않는다.
        // 발급 실패는 스테이징을 만들기 전·실행 전에 단계를 멈춘다(fail-closed). PG 는 토큰이 없다(요청 본문 불변).
        final String scriptToken =
            dialect.issueScriptToken(
                pyTenantId,
                readSlot,
                executorEnabled
                    ? Duration.ofSeconds(
                        executorPythonTimeoutSeconds + PYTHON_TOKEN_TTL_MARGIN_SECONDS)
                    : LOCAL_PYTHON_TOKEN_TTL);

        if (outputDatasetId == null) {
          log.warn("Python 스텝 '{}': 출력 데이터셋이 지정되지 않았습니다. 결과가 저장되지 않습니다.", step.name());
        }
        // 컬럼 타입 맵 — executor 와 로컬 적재가 같은 규칙으로 stdout JSON 값을 변환한다(API_CALL 블록과 동일 패턴).
        Map<String, String> columnTypeMap = null;
        if (outputDatasetId != null) {
          columnTypeMap = new HashMap<>();
          for (DatasetColumnResponse col : columnRepository.findByDatasetId(outputDatasetId)) {
            columnTypeMap.put(col.columnName(), col.dataType());
          }
        }
        final String pyOutputTable = outputTableName;
        final Map<String, String> pyColumnTypes = columnTypeMap;

        if (executorEnabled) {
          // REPLACE 판정은 의도적으로 enum(strategy)이 아니라 원본 문자열을 본다 — strategy 는 알 수
          // 없는 값을 REPLACE 로 폴백하지만, 이 경로의 맞바꿈은 "명시적으로 REPLACE 라고 적힌" 경우에만
          // 탄다(예전부터의 동작, 바꾸지 않는다).
          boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy) && pyOutputTable != null;
          // Tibero 는 APPEND 도 스테이징을 거친다(StepDialect#stagesAppend 참고). PG APPEND 는 예전처럼 출력에 직접 적재한다.
          boolean tiberoAppend = dialect.stagesAppend(isReplace, pyOutputTable);
          boolean staged = isReplace || tiberoAppend;
          executionLog =
              loadPythonOutput(
                      pyOutputTable,
                      isReplace,
                      tiberoAppend,
                      targetTable -> {
                        Map<String, Object> request = new LinkedHashMap<>();
                        request.put("script", step.scriptContent());
                        if (targetTable != null) {
                          request.put("output_table", targetTable);
                        }
                        if (pyColumnTypes != null) {
                          request.put("column_type_map", pyColumnTypes);
                        }
                        // Tibero 에만 싣는다 — PG 요청 본문에는 키 자체가 없다(PG 동작 불변).
                        if (scriptToken != null) {
                          request.put("script_token", scriptToken);
                        }
                        // 취소 체크포인트(WD-153) — 실행기 호출 직전. 스테이징이면 빈 임시 테이블을 loadPythonOutput 의 실패
                        // 정리가 버린다.
                        cancel.checkpoint(0, staged);
                        var result = executorClient.executePython(request, readSlot);
                        if (!result.success()) {
                          // 적재 데이터 오류(pg_error 22xxx)는 로컬 경로와 같은 한국어 사유 문구로 바꾼다(WD-211). 그 밖은
                          // executor 가
                          // 이미 분류한 문구(또는 사용자 스크립트 stderr)에 접두만 붙인다.
                          ExecutorClient.PgError pg = result.pgError();
                          throw pythonRunFailure(
                              PythonStepErrorMessages.fromExecutor(
                                  result.error(),
                                  pg != null ? pg.sqlState() : null,
                                  pg != null ? pg.column() : null,
                                  pg != null ? pg.message() : null),
                              pyTenantId,
                              pyStartedAt);
                        }
                        // 취소 체크포인트(WD-153) — 실행기 호출 직후·맞바꿈 전. 스테이징이면 실행기가 채운 임시 테이블을 버려 원본을 지키고,
                        // PG APPEND 면 실행기가 이미 넣은 행이 남는다(그 수를 문구에 남긴다).
                        cancel.checkpoint(staged ? 0 : result.rowsLoaded(), staged);
                        return new PythonLoad(result.output(), result.rowsLoaded());
                      })
                  .log();
        } else {
          // 로컬 경로(실행기 끔) — executor 와 같은 계약(R5): 스크립트는 읽기 슬롯 롤로 돌고 stdout 에
          // JSON 행을 내며, 적재는 API 가 앱 연결로 한다(LocalPythonOutputLoader). 실행 로그는 출력
          // 테이블이 있으면 stderr, 없으면 stdout+stderr(executor 와 동일). REPLACE 판정은 예전 로컬
          // 경로 그대로 해석된 전략(알 수 없는 값 → REPLACE 폴백)을 쓴다.
          boolean isReplace = strategy != LoadStrategy.APPEND && pyOutputTable != null;
          // Tibero 는 APPEND 도 스테이징 적재 후 옮긴다(실행기 켠 경로와 같다 — StepDialect#stagesAppend 참고).
          boolean tiberoAppend = dialect.stagesAppend(isReplace, pyOutputTable);
          // 취소 체크포인트(WD-153) — 스크립트 실행 직전.
          cancel.checkpoint(0, false);
          // Tibero 자식은 PG 슬롯 롤이 아니라 조회 도우미 환경(db-executor 주소·스크립트 토큰)으로 띄운다.
          PythonScriptExecutor.RunResult run =
              dialect.runLocalPython(step.scriptContent(), readSlot, scriptToken);
          String outputText = pyOutputTable != null ? run.stderr() : run.stdout() + run.stderr();
          if (!run.succeeded()) {
            throw pythonRunFailure(
                "Python 실행 실패(exit code " + run.exitCode() + "): " + outputText,
                pyTenantId,
                pyStartedAt);
          }
          // 취소 체크포인트(WD-153) — 스크립트 실행 직후·적재 전. 로컬 경로는 API 가 stdout 을 적재하므로 여기서 멈추면 출력에 아무것도 쓰지
          // 않는다.
          cancel.checkpoint(0, false);
          if (pyOutputTable == null) {
            executionLog = outputText;
          } else {
            Supplier<PythonLoad> load =
                () ->
                    loadPythonOutput(
                        pyOutputTable,
                        isReplace,
                        tiberoAppend,
                        targetTable ->
                            new PythonLoad(
                                outputText,
                                // Tibero 대상은 언제나 스테이징이다 — PG 트랜잭션 없이 스테이징 창구로 넣는다(방언이 고른다).
                                dialect.loadLocalPythonOutput(
                                    targetTable, run.stdout(), pyColumnTypes)));
            // REPLACE 는 출력 테이블 세션 잠금 아래에서 임시 테이블 생성 → 적재 → 맞바꿈을 한다(#735).
            // 임시 테이블 이름은 WD-151 부터 실행마다 달라 임시 테이블 충돌만 놓고 보면 이 잠금이 없어도 되지만, 예전 동작(같은 출력의 로컬
            // REPLACE 끼리, 그리고 SQL 스텝의 비우기+적재와 직렬화 — 잠금 키가 같다)을 바꾸지 않으려고 그대로 둔다. 스크립트 실행(읽기
            // 전용)은 잠금 밖 — 쓰는 구간만 덮는다.
            // Tibero 는 이 잠금을 잡지 않는다 — 잠금의 목적(같은 출력의 PG SQL 비우기+적재와의 직렬화)이 Tibero 에는 없고(Tibero SQL
            // 단계는 이
            // 키를 잡지 않는다), 잡으면 Tibero 작업 내내 PG 풀 연결만 쥔다. 원자성은 스테이징 + 옮기기(한 Tibero 트랜잭션)가 맡는다.
            boolean takePgLock = dialect.takesOutputSessionLock(isReplace);
            executionLog =
                (takePgLock ? outputTableSessionLock.callLocked(pyOutputTable, load) : load.get())
                    .log();
          }
        }
      } else if ("API_CALL".equals(step.scriptType())) {
        ApiCallConfig apiCallConfig =
            objectMapper.convertValue(step.apiConfig(), ApiCallConfig.class);

        // apiConnectionId가 있으면 connection 정보 로드 (baseUrl + auth)
        ApiConnectionResponse apiConn = null;
        Map<String, String> decryptedAuth = null;
        if (step.apiConnectionId() != null) {
          try {
            apiConn = apiConnectionService.getById(step.apiConnectionId());
            decryptedAuth = apiConnectionService.getDecryptedAuthConfig(step.apiConnectionId());
          } catch (ApiConnectionNotFoundException e) {
            // 실행 기록(스텝 오류)에 남는 문구라 사용자가 할 일을 담은 한국어로 바꾼다(WD-180) — 예전에는 「ApiConnection not
            // found: 3」 영어 원문이 그대로 남았다. 다른 워크스페이스의 연결과 없는 연결은 RLS 로 같은 경로라 같은 문구다(WD-176).
            throw new ScriptExecutionException(API_CONNECTION_NOT_FOUND_AT_RUN_MESSAGE, e);
          }
        } else if (apiCallConfig.inlineAuth() != null) {
          decryptedAuth = apiCallConfig.inlineAuth();
        }

        // 보안 등급(코드리뷰 CR2·후속 F1): 이미 있던 출력은 실행 주체가 볼 수 있어야 쓴다 — REPLACE 맞바꿈·적재보다 먼저다. 외부 API 데이터라
        // 판정할 입력이 없으므로 등급 전파·하향 판정은 없다. 실행 주체 자격은 이 스텝에서 한 번만 계산한다.
        PipelineSecurityGate.RunAs apiRunAs = pipelineSecurityGate.runAs(userId);
        requireExistingOutputVisible(outputDatasetId, apiRunAs);

        // 출력이 없거나 이 스텝 소유 TEMP 면 임시 데이터셋 준비(WD-222 — 열이 바뀌면 재생성)
        if (ownedTempOutput) {
          List<ColumnInfo> apiColumns = inferApiCallColumns(apiCallConfig);
          StepTemp apiTemp =
              ensureUnpropagatedStepTemp(
                  step,
                  existingStepTemp.get(),
                  apiColumns,
                  pipelineId,
                  pipelineName,
                  userId,
                  apiRunAs,
                  "API_CALL ");
          outputDatasetId = apiTemp.datasetId();
          tempRebuild = apiTemp.rebuild();
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 truncateTable 하지 않는다 — 다시 넣지 말 것.
          // 이유 셋:
          //  (1) 로드 전략을 무시했다 — 재사용된 임시 데이터셋(ptmp_*)은 APPEND 스텝에서도 매 실행
          //      통째로 비워졌다. APPEND 는 절대 비우면 안 된다.
          //  (2) API 호출보다 **먼저** 커밋됐다(DataTableRowService 에는 @Transactional 이 없어
          //      truncate 가 즉시 커밋된다). 그 뒤 호출이 실패하면 출력 데이터셋이 빈 채로 남는다 —
          //      이 브랜치가 SQL 경로에서 없앤 바로 그 결함이다.
          //  (3) 빈 결과 가드(#685: "0행은 기존 데이터를 파괴하지 않는다")를 무력화했다. 아래
          //      finishReplace 가 0행이면 맞바꾸지 않고 원본을 지키는데, 앞에서 이미 비워 버리면
          //      지킬 원본이 없다.
          // REPLACE 비우기는 아래 두 경로가 이미 원자적으로 처리한다 — 실행기 켠 경로는
          // createTempTable → finishReplace 맞바꿈(이 블록 바로 아래), 끈 경로는 ApiCallExecutor
          // 내부의 동일한 임시 테이블 맞바꿈이다. 실패 시 dropTempTable 로 이전 행이 그대로 남는다.
        }

        // 정확한 타입 변환을 위해 데이터셋 메타데이터에서 컬럼 타입 맵 구성
        Map<String, String> columnTypeMap = null;
        if (outputDatasetId != null) {
          List<DatasetColumnResponse> columns = columnRepository.findByDatasetId(outputDatasetId);
          columnTypeMap = new HashMap<>();
          for (DatasetColumnResponse col : columns) {
            columnTypeMap.put(col.columnName(), col.dataType());
          }
        }

        if (executorEnabled) {
          // REPLACE 전략: API가 DDL 오케스트레이션 (executor는 INSERT만 수행)
          // PYTHON 블록과 같은 이유로 enum(strategy)이 아니라 원본 문자열을 본다 — 알 수 없는 값의
          // REPLACE 폴백을 맞바꿈 경로까지 끌고 오면 동작이 바뀐다.
          boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy) && outputTableName != null;
          // 이번 실행 전용 임시 테이블(WD-151) — 이름이 실행마다 달라 같은 출력에 겹친 실행과
          // 생성·적재·정리가 서로 닿지 않는다. 실행기에는 이 이름을 그대로 적재 대상으로 넘긴다(실행기는
          // 임시 테이블 이름을 스스로 만들지 않는다).
          // Tibero 는 REPLACE 뿐 아니라 APPEND 도 스테이징을 거친다(StepDialect#stagesAppend 참고).
          // PG 의 APPEND 는 그대로 출력에 직접 적재한다(finishAppend 는 PG 에서 예외다 — 부르지 않는다).
          // 생성 → 적재 → 마무리(finishReplace/finishAppend) → 실패 시 스테이징 삭제는 PYTHON 과 같은
          // runStaged 가 맡는다. 빈 결과 가드는 finishReplace 가 단독으로 갖는다(#685) — API 가 0행을 돌려주면
          // 맞바꾸지 않고 임시 테이블만 버려 원본을 지킨다. 예전에는 여기서 swapTable 을 바로 불러 빈 테이블이
          // 원본을 덮었다(같은 결함이 아직 터지지 않은 상태였다).
          boolean tiberoAppend = dialect.stagesAppend(isReplace, outputTableName);
          boolean useStaging = isReplace || tiberoAppend;
          final ApiConnectionResponse apiConnFinal = apiConn;
          final Map<String, String> apiAuth = decryptedAuth;
          final Map<String, String> apiColumnTypes = columnTypeMap;
          executionLog =
              runStaged(
                      outputTableName,
                      isReplace,
                      tiberoAppend,
                      "Failed to drop temp table after API call failure: {}",
                      targetTable -> {
                        Map<String, Object> request =
                            buildApiCallExecutorRequest(
                                apiCallConfig, targetTable, apiAuth, apiColumnTypes, apiConnFinal);
                        // 취소 체크포인트(WD-153) — 실행기 호출 직전. 실행기 안의 페이지·재시도는 이 API 가 들여다볼 수 없어 호출
                        // 직전·직후만 본다.
                        cancel.checkpoint(0, useStaging);
                        var result = executorClient.executeApiCall(request);
                        if (!result.success()) {
                          throw new ScriptExecutionException("API_CALL 실행 실패: " + result.error());
                        }
                        // 취소 체크포인트(WD-153) — 실행기 호출 직후·맞바꿈 전. 스테이징이면 runStaged 의 실패 정리가 임시
                        // 테이블을 버려 원본을 지키고, PG APPEND 면 이미 넣은 행이 남는다.
                        cancel.checkpoint(useStaging ? 0 : result.rowsLoaded(), useStaging);
                        return result;
                      },
                      ExecutorClient.ApiCallExecuteResult::rowsLoaded)
                  .executionLog();
        } else {
          ApiCallExecutor.ApiCallResult result =
              apiCallExecutor.execute(
                  apiCallConfig,
                  outputTableName,
                  decryptedAuth,
                  loadStrategy,
                  columnTypeMap,
                  apiConn,
                  cancel);
          executionLog = result.log();
        }
      } else if ("AI_CLASSIFY".equals(step.scriptType())) {
        if (!permissionChecker.hasPermission(userId, "pipeline:ai_execute")) {
          throw new ScriptExecutionException(
              "AI 분류 스텝 실행에는 'pipeline:ai_execute' 권한이 필요합니다. 관리자에게 이 기능 활성화를 요청하세요.");
        }

        // 명시적 inputDatasetIds가 없으면 의존 스텝의 출력 데이터셋에서 자동 해결
        List<Long> resolvedInputDatasetIds = step.inputDatasetIds();
        if ((resolvedInputDatasetIds == null || resolvedInputDatasetIds.isEmpty())
            && step.dependsOnStepNames() != null
            && !step.dependsOnStepNames().isEmpty()) {

          List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);
          Map<String, PipelineStepResponse> stepByName =
              allSteps.stream().collect(Collectors.toMap(PipelineStepResponse::name, s -> s));

          resolvedInputDatasetIds = new ArrayList<>();
          for (String depName : step.dependsOnStepNames()) {
            PipelineStepResponse depStep = stepByName.get(depName);
            if (depStep == null) continue;

            Long depOutputId = depStep.outputDatasetId();
            if (depOutputId == null) {
              depOutputId = datasetRepository.findBySourcePipelineStepId(depStep.id()).orElse(null);
            }
            if (depOutputId != null) {
              resolvedInputDatasetIds.add(depOutputId);
            }
          }

          if (!resolvedInputDatasetIds.isEmpty()) {
            log.info(
                "[AI_CLASSIFY] Step '{}': Auto-resolved {} input dataset(s) from dependencies: {}",
                step.name(),
                resolvedInputDatasetIds.size(),
                resolvedInputDatasetIds);
          }
        }

        // WD-216: 명시 입력도 없고 의존 스텝에서도 출력 데이터셋을 얻지 못했으면 분류할 입력이 없다 — 설정 오류로 실패시킨다. 저장 시점
        // 검증(PipelineService) 이전에 저장된 스텝이나, 의존 스텝이 출력을 내지 않은 경우(예: 출력 미지정 DML SQL)가 여기 온다. 출력
        // 임시 데이터셋을 만들기 전에 던져 빈 TEMP 를 남기지 않는다. 실행기도 같은 문구로 한 번 더 막는다.
        if (resolvedInputDatasetIds == null || resolvedInputDatasetIds.isEmpty()) {
          throw new ScriptExecutionException(AiClassifyExecutor.NO_INPUT_DATASET_MESSAGE);
        }

        // 보안 등급(최종 리뷰 C3): 실행 주체가 해석된 입력(명시 + 의존 스텝 출력 자동 해석)을 모두 볼 수 있어야 한다 — 입력을 읽기 전,
        // 출력 TEMP 를 만들거나 지우기 전에 판정한다. 거부는 SQL 스텝과 같은 구분 불가 메시지로 스텝 실패가 된다.
        PipelineSecurityGate.RunAs aiRunAs = pipelineSecurityGate.runAs(userId);
        SqlAccessResult aiInputAccess =
            pipelineSecurityGate.checkStepInputsForRun(
                aiRunAs, resolvedInputDatasetIds == null ? List.of() : resolvedInputDatasetIds);
        // S3 §4.3: 실행 주체 기준 AI 판정(분류 호스팅) — 입력을 읽거나 TEMP 를 만들기 전. 이 러너는 별도 스레드라 요청 속성·AiCallContext
        // ThreadLocal 이 없다 — 그래서 문맥에 기대지 않고 AiCall 을 명시로 넘긴다(fail-open 방지).
        datasetAccessGuard.requireAiForDatasets(
            aiRunAs.clearance(),
            resolvedInputDatasetIds == null ? List.of() : resolvedInputDatasetIds,
            new AiCall(aiHostingResolver.classify(), false));
        // 이번 실행에서 TEMP 를 새로 만들었는지(빈 테이블) — SQL 스텝과 같은 이유로 새 TEMP 만 입력 최대 등급으로 "정확히" 맞춘다.
        boolean aiTempFresh = false;

        // 출력이 없거나 이 스텝 소유 TEMP 면 임시 데이터셋 준비(WD-222 — 열이 바뀌면 재생성)
        if (ownedTempOutput) {
          com.smartfirehub.pipeline.dto.AiClassifyConfig aiClassifyConfig =
              objectMapper.convertValue(
                  step.aiConfig(), com.smartfirehub.pipeline.dto.AiClassifyConfig.class);
          List<ColumnInfo> aiColumns = buildAiClassifyColumns(aiClassifyConfig);
          StepTemp temp =
              ensureStepTemp(
                  step,
                  existingStepTemp.get(),
                  aiColumns,
                  pipelineId,
                  pipelineName,
                  userId,
                  aiRunAs,
                  "AI_CLASSIFY ");
          aiTempFresh = temp.fresh();
          tempRebuild = temp.rebuild();
          outputDatasetId = temp.datasetId();
          outputTableName = datasetRepository.findTableNameById(outputDatasetId).orElseThrow();
          // 여기서 truncateTable 하지 않는다 — 다시 넣지 말 것. API_CALL 블록과 같은 이유다:
          //  (1) APPEND 스텝의 재사용 임시 데이터셋까지 매 실행 비웠다.
          //  (2) AI 분류가 돌기 **전에** 즉시 커밋돼, 분류가 실패하면 출력이 빈 채로 남았다.
          //  (3) #685 의 빈 결과 가드를 무력화했다(지킬 원본을 미리 없애 버린다).
          // REPLACE 비우기는 AiClassifyExecutor 가 loadStrategy 를 직접 읽어 createTempTable →
          // swapTable 로 처리한다(AiClassifyExecutor:128-136, :268-269). 여기만 finishReplace 가
          // 아니라 swapTable 을 직접 부르는데, AI_CLASSIFY 에서 0행은 정상 결과가 아니라
          // :255 에서 먼저 예외를 던지기 때문이다 — 즉 swapTable 에 도달하면 이미 비어 있지 않다.
          // 실패하면 :273 의 dropTempTable 로 이전 행이 그대로 남는다.
        }

        // 출력 등급(판단 사항 5, SQL SELECT 스텝과 같은 규칙): 러너 TEMP 는 입력 최대 등급으로 상향·시드, 사용자 지정 출력은 실행 주체가
        // 볼 수 있어야 하고 입력보다 낮으면 자동 상향(S4, 스펙 §4.5). 실행기(AiClassifyExecutor)가 출력을 비우거나 쓰기 전에 둔다.
        if (outputDatasetId != null) {
          allowlistPlan =
              pipelineSecurityGate.enforceOutputLevel(
                  aiInputAccess,
                  outputDatasetId,
                  step.id(),
                  aiTempFresh,
                  // AiClassifyExecutor 는 명시적 "REPLACE" 일 때만 스테이징 맞바꿈으로 출력을 전부 교체한다(같은 판정) — WD-30
                  // 쓰기 후 넓힘 허용
                  "REPLACE".equalsIgnoreCase(loadStrategy),
                  aiRunAs);
        }

        // AiClassifyExecutor에 전달할 스텝 래퍼: 해결된 outputDatasetId 및 inputDatasetIds 반영
        final Long resolvedOutputDatasetId = outputDatasetId;
        final List<Long> finalInputDatasetIds = resolvedInputDatasetIds;
        PipelineStepResponse resolvedStep =
            new PipelineStepResponse(
                step.id(),
                step.name(),
                step.description(),
                step.scriptType(),
                step.scriptContent(),
                resolvedOutputDatasetId,
                step.outputDatasetName(),
                finalInputDatasetIds,
                step.dependsOnStepNames(),
                step.stepOrder(),
                step.loadStrategy(),
                step.apiConfig(),
                step.aiConfig(),
                step.pythonConfig(),
                step.apiConnectionId());

        AiClassifyExecutor.ExecutionResult aiResult =
            aiClassifyExecutor.execute(resolvedStep, stepExecId, userId, cancel);
        executionLog = aiResult.executionLog();
        // AI_CLASSIFY는 자체적으로 출력 행 수를 관리
      } else {
        throw new ScriptExecutionException("Unsupported script type: " + step.scriptType());
      }

      // 재생성 TEMP 확정(WD-238) — 여기 오면 출력이 커밋됐다. 이후 단계(책갈피·행 수·상태 갱신)가 실패해도 이미 커밋된 새 출력을 되돌리지 않도록 가장
      // 먼저 보관본을 버리고 재료를 비운다. 버리기 실패는 디스크만 차지하는 보관본이 남을 뿐이라(다음 재생성·데이터셋 삭제가 지운다) 스텝을 실패시키지 않는다.
      if (tempRebuild != null) {
        DatasetService.PipelineTempRebuild committed = tempRebuild;
        tempRebuild = null;
        try {
          tempDatasetService.commitRebuild(committed);
        } catch (Exception commitEx) {
          log.warn(
              "Step {}: 재생성 TEMP {} 의 보관본 {} 정리 실패 — 다음 재생성 때 지워진다",
              step.name(),
              committed.datasetId(),
              committed.backupTableName(),
              commitEx);
        }
      }

      // 증분 책갈피 전진 — 반드시 "출력이 커밋된 뒤"다.
      //
      // 출력(테넌트 파이프라인 롤 커넥션)과 책갈피(앱 커넥션)는 서로 다른 커넥션이라 한 트랜잭션으로
      // 묶을 수 없다. 그래서 순서가 곧 안전장치다: 출력 커밋 → 책갈피 전진. 이 사이에서 죽으면 다음
      // 실행이 같은 구간을 다시 읽고, MERGE 의 멱등성이 그 중복을 흡수한다. 반대로 먼저 전진시키면
      // 출력 실패 시 그 구간이 영원히 비는 데이터 손실이 된다.
      //
      // 실패 경로(catch)에서는 전진하지 않는다 — 여기까지 도달하지 못하기 때문이다. 전체 재생성 예약은
      // "이번 실행이 실제로 전체 재생성이었을 때"만 해제한다(advanceCursor Javadoc) — 실패하면 예약이
      // 그대로 유지돼 다음 실행이 다시 시도하고, 이번 실행 도중 새로 켜진 예약도 잃지 않는다.
      if (incrementalStep) {
        // 실제로 쓴 출력(outputDatasetId — 러너 TEMP 포함)을 함께 넘긴다(WD-108): 저장이 스텝을 제자리 갱신하므로 실행 도중 출력이 바뀌어
        // 저장됐으면 새 출력의 책갈피를 전진시키지 않아야 한다(리포지토리 Javadoc).
        stepRepository.advanceCursor(
            step.id(), stepCursorCandidate, stepWasFullRebuild, outputDatasetId);
        // 책갈피가 전진했으니 기다리던 다음 실행을 바로 풀어 준다(행 수 계산·상태 갱신까지 붙잡지 않는다, WD-223).
        if (incrementalLock != null) {
          incrementalLock.close();
        }
      }

      // WD-30: 출력이 커밋된 뒤에만 러너 TEMP 허용 목록을 시드로 확정한다(넓힘 포함 — 출력이 전부 교체된 실행만 계획이 있다). 실패 경로(catch)는 여기
      // 오지 않는다 — 쓰기 전 좁히기만
      // 남는다(이전 실행 데이터가 남은 출력에 새 열람자를 넣지 않는다). 같은 스텝의 다른 실행과 겹쳤으면 확정하지 않는다(코드리뷰 CR1 — 마지막으로
      // 쓴 실행이 이 실행이라고 단정할 수 없다). 그 판정은 아래 COMPLETED 갱신(completed_at)보다 앞서야 겹친 실행 쪽에서도 이 실행이 RUNNING
      // 으로 보인다.
      pipelineSecurityGate.completeOutputAllowlist(allowlistPlan, stepExecId);

      // 출력 행 수 계산 (출력 테이블이 있는 경우)
      Long outputRows = null;
      if (outputTableName != null) {
        outputRows = dataTableRowService.countRows(outputTableName);
      }

      // 스텝 실행 완료 처리
      executionRepository.updateStepExecution(
          stepExecId,
          "COMPLETED",
          outputRows != null ? outputRows.intValue() : null,
          executionLog,
          null,
          null,
          LocalDateTime.now(ZoneOffset.UTC));

      log.info("Step {} completed successfully. Output rows: {}", step.name(), outputRows);
      return "COMPLETED";

    } catch (Exception e) {
      // 취소 체크포인트가 던진 예외(WD-153) — 실패가 아니라 CANCELLED 로 기록한다. 실행기가 감싸 던졌어도 원인 사슬에서 찾는다. REPLACE 스테이징
      // 정리는 실행기·러너의 실패 정리 경로가 이미 했다(예외를 다시 던지기 전에).
      PipelineExecutionCancelledException cancelled = PipelineExecutionCancelledException.findIn(e);
      // 재생성 TEMP 되돌리기(WD-238) — 실패·취소 모두 이전 테이블·열 메타·등급을 되살린다. 상태 기록보다 먼저 해 실행 이력이 「실패」로 보일 때 출력은
      // 이미 이전 결과다. 되돌리기 실패는 로그만 남기고 원래 실행 오류를 그대로 기록한다.
      rollbackTempRebuild(tempRebuild, step, userId);
      if (cancelled != null) {
        log.info("Step {} 중지 요청 또는 외부 마감으로 실행을 멈췄습니다", step.name());
        executionRepository.updateStepExecution(
            stepExecId,
            "CANCELLED",
            null,
            null,
            cancelled.stepMessage(),
            null,
            LocalDateTime.now(ZoneOffset.UTC));
        return "CANCELLED";
      }
      log.error("Step {} failed", step.name(), e);
      executionRepository.updateStepExecution(
          stepExecId,
          "FAILED",
          null,
          null,
          e.getMessage(),
          null,
          LocalDateTime.now(ZoneOffset.UTC));
      return "FAILED";
    } finally {
      // 증분 스텝 잠금 안전망(WD-223) — 실패·취소·예외로 전진 전에 끝났으면 여기서 푼다(이미 풀었으면 아무것도 하지 않는다).
      if (incrementalLock != null) {
        incrementalLock.close();
      }
    }
  }

  /**
   * 실패·취소한 스텝의 재생성 TEMP 를 되돌린다(WD-238). 재생성하지 않았으면(null) 아무것도 하지 않는다. 되돌리기가 실패하면 새 열 구성의 빈(또는 일부만
   * 채운) 테이블과 보관본이 남는다 — 오류 로그로 알리고 예외는 삼킨다(원래 실행 오류를 가리지 않는다). 보관본은 다음 재생성·데이터셋 삭제가 지운다.
   */
  private void rollbackTempRebuild(
      DatasetService.PipelineTempRebuild rebuild, PipelineStepResponse step, Long userId) {
    if (rebuild == null) {
      return;
    }
    try {
      tempDatasetService.rollbackRebuild(rebuild, userId);
      log.info(
          "Step {} failed — restored temp dataset {} to its previous table and columns",
          step.name(),
          rebuild.datasetId());
    } catch (Exception restoreEx) {
      log.error(
          "Step {}: 재생성 TEMP {} 되돌리기 실패(보관본 {}) — 이전 결과가 복원되지 않았습니다",
          step.name(),
          rebuild.datasetId(),
          rebuild.backupTableName(),
          restoreEx);
    }
  }

  /**
   * Tibero 결과 컬럼이 따옴표 소문자·대소문자 섞인 이름이라 적재 대상으로 쓸 수 없을 때의 안내(이름은 뒤에 붙인다). 쓰는 곳은 {@link
   * TiberoStepDialect} 로 옮겼고(WD-231), 기존 러너 테스트가 이 이름으로 문구를 확인하므로 별칭만 남긴다.
   */
  static final String TIBERO_QUOTED_RESULT_COLUMN_PREFIX =
      TiberoStepDialect.QUOTED_RESULT_COLUMN_PREFIX;

  /**
   * 증분({{last_run_at}}) 자동 생성 스텝의 SELECT 열이 바뀌었는데 변경분만 읽는 실행일 때의 실패 문구(WD-222). 임시 데이터셋을 새 열로 다시
   * 만들면 비어 있는 채로 변경분만 채워지므로, 전체 재생성을 예약해 처음부터 다시 채우게 안내한다.
   */
  static final String INCREMENTAL_TEMP_SCHEMA_CHANGED_MESSAGE =
      "증분 스텝의 결과 열이 바뀌어 임시 출력 데이터셋을 다시 만들어야 합니다. 변경분만 읽으면 이전 행이 빠지므로, 스텝에서 「전체 재생성」을 예약한 뒤 다시"
          + " 실행하세요.";

  /**
   * 교체(REPLACE) SELECT 가 이 스텝의 러너 TEMP 자신을 읽는데 결과 열이 바뀐 실행의 실패 문구(WD-426). TEMP 를 새 열로 다시 만들면 SQL 이
   * 다시 만든 빈 테이블을 읽어 기존 행이 모두 사라지므로, 다시 만들기 전에(아무것도 지우기 전에) 실패시키고 할 일을 안내한다.
   */
  static final String SELF_READ_TEMP_SCHEMA_CHANGED_MESSAGE =
      "교체(REPLACE) 스텝의 SQL 이 이 스텝의 임시 출력 데이터셋 자신을 읽는데 결과 열이 바뀌어, 임시 데이터셋을 다시 만들어야 합니다. 다시 만든 빈"
          + " 테이블을 읽으면 기존 행이 모두 사라지므로 실행하지 않았습니다(출력은 그대로입니다). 결과 열을 이전과 같게 두거나, 열을 바꾸는 동안에는 SQL"
          + " 이 자기 출력을 읽지 않게 하거나, 새 열 구성의 출력 데이터셋을 따로 지정한 뒤 다시 실행하세요.";

  /**
   * Tibero 결과 컬럼 이름을 메타데이터 이름으로 바꾼다 — 본문과 「왜」는 {@link
   * TiberoStepDialect#normalizeTiberoResultColumn} 으로 옮겼다(WD-231). 맞춤 계산도 방언으로 옮겼고, 기존 러너 테스트가 이
   * 이름을 직접 부르므로 위임만 남긴다.
   */
  static String normalizeTiberoResultColumn(String name) {
    return TiberoStepDialect.normalizeTiberoResultColumn(name);
  }

  /**
   * 처음 부를 때 한 번만 계산하고 그 뒤로는 같은 값을 돌려준다 — 한 스텝 실행 안의 지역 값 전용(스레드 간 공유하지 않는다). 계산 결과는 null 이 아니어야
   * 한다(null 이면 다음에 다시 계산한다).
   */
  private static <T> Supplier<T> once(Supplier<T> compute) {
    return new Supplier<>() {
      private T value;

      @Override
      public T get() {
        if (value == null) {
          value = compute.get();
        }
        return value;
      }
    };
  }

  /**
   * 러너 소유 TEMP 출력 — 데이터셋 id, 이번 실행에서 새로 만든(빈) 테이블인지, 그리고 열 구성이 바뀌어 다시 만들었으면 그 되돌리기 재료(WD-238, 아니면
   * null). 되돌리기 재료가 있으면 {@link #executeStep} 이 스텝 결과에 따라 보관본을 버리거나 되돌린다.
   */
  private record StepTemp(
      Long datasetId, boolean fresh, DatasetService.PipelineTempRebuild rebuild) {}

  /**
   * 러너 소유 TEMP 출력(SQL SELECT·AI_CLASSIFY·PYTHON, 그리고 {@link #ensureUnpropagatedStepTemp} 를 거친
   * API_CALL)을 준비한다 — 없으면 만들고, 있으면 스키마가 같을 때 재사용, 바뀌었으면 지우고 다시 만든다.
   *
   * <p>코드리뷰 CR3: 재사용 TEMP 를 지우기 <b>전에</b> 실행 주체가 볼 수 있는지 본다. 이후 출력 등급 처리(enforceOutputLevel)는 새로 만든
   * TEMP 만 보므로, 여기서 막지 않으면 볼 수 없는 실행 주체(B)가 이전 실행 주체(A)의 결과 TEMP 를 통째로 지운다. 삭제·재생성 경로를 두 스텝 타입이 이 한
   * 곳으로 지나가게 해, 판정을 빠뜨린 복사본이 생기지 않게 한다. 사용자 SQL 을 실행하는 probe 는 여기서 부르지 않는다(호출자가 실행 주체 판정 뒤에 부른다).
   *
   * @param existingTemp 이 스텝의 기존 러너 TEMP({@code executeStep} 이 한 번 찾은 결과 — 여기서 다시 찾지 않는다)
   * @param logLabel 로그 문구의 스텝 종류 접두("" 또는 "AI_CLASSIFY ")
   */
  private StepTemp ensureStepTemp(
      PipelineStepResponse step,
      Optional<Long> existingTemp,
      List<ColumnInfo> columns,
      Long pipelineId,
      String pipelineName,
      Long userId,
      PipelineSecurityGate.RunAs runAs,
      String logLabel) {
    return ensureStepTemp(
        step, existingTemp, columns, pipelineId, pipelineName, userId, runAs, logLabel, null);
  }

  /**
   * {@link #ensureStepTemp} 본체 — {@code rebuildRefusal} 이 있으면 열이 바뀐 TEMP 를 다시 만들지 않고, 아무것도 지우기 전에 그
   * 안내로 실패한다. 다시 만든 빈 TEMP 가 조용한 누락이 되는 실행이 쓴다: 증분 스텝이 변경분만 읽는 실행(WD-222 — 빈 TEMP 에 변경분만 채워진다)과
   * REPLACE SELECT 가 TEMP 자신을 읽는 실행(WD-426 — 빈 TEMP 를 읽는다).
   *
   * @param rebuildRefusal 다시 만들기를 거부할 때의 실패 문구(null 이면 다시 만든다)
   */
  private StepTemp ensureStepTemp(
      PipelineStepResponse step,
      Optional<Long> existingTemp,
      List<ColumnInfo> columns,
      Long pipelineId,
      String pipelineName,
      Long userId,
      PipelineSecurityGate.RunAs runAs,
      String logLabel,
      String rebuildRefusal) {
    Long stepId = step.id();
    if (existingTemp.isPresent()) {
      Long dsId = existingTemp.get();
      // 크래시로 남은 재생성 보관본을 먼저 맞춘다(WD-256) — Tibero 는 재생성 DDL 이 PG 트랜잭션 밖이라, 이전 실행이 마무리 전에 멈췄으면 메타와
      // 물리 테이블이 어긋난 채 남을 수 있다. 맞춘 뒤의 메타로 스키마를 비교해야 재사용·재생성 판단이 맞다. PG 는 아무것도 하지 않는다.
      tempDatasetService.reconcileTempDataset(dsId);
      if (!tempDatasetService.hasSchemaChanged(dsId, columns)) {
        log.info("Reusing existing temp dataset {} for {}step {}", dsId, logLabel, step.name());
        return new StepTemp(dsId, false, null);
      }
      if (rebuildRefusal != null) {
        throw new ScriptExecutionException(rebuildRefusal);
      }
      // 새 컬럼을 옛 TEMP 를 비우기 전에 검증한다 — 비운 뒤 생성이 실패하면 이전 출력이 사라진다.
      tempDatasetService.validateColumnsForNewTemp(columns);
      log.info(
          "Schema changed for {}step {}, rebuilding temp dataset {}", logLabel, step.name(), dsId);
      pipelineSecurityGate.requireOutputVisible(dsId, runAs);
      // 같은 데이터셋 id 로 테이블·컬럼만 다시 만든다(WD-222) — 삭제 후 생성은 화면 저장으로 출력 id 가 TEMP 로 고정된 스텝에서 출력 참조
      // 거부(DatasetInUseException)로 실패하고, id 가 바뀌면 다른 스텝 입력·열린 편집기의 참조가 깨진다. 테이블이 비었으므로 fresh 다 —
      // 출력 등급 처리가 입력 최대 등급으로 정확히 맞춘다(삭제 후 생성 때와 같은 규칙).
      // 옛 테이블은 지우지 않고 보관한다(WD-238) — 이 스텝이 실패하면 executeStep 이 되돌린다.
      DatasetService.PipelineTempRebuild rebuild =
          tempDatasetService.rebuildTempDataset(dsId, columns);
      return new StepTemp(dsId, true, rebuild);
    } else {
      log.info("Creating new temp dataset for {}step {}", logLabel, step.name());
    }
    return new StepTemp(
        tempDatasetService.createTempDataset(
            columns, pipelineId, pipelineName, stepId, step.name(), userId),
        true,
        null);
  }

  /**
   * API_CALL 스텝의 러너 소유 TEMP 출력을 준비한다(후속 F1). PYTHON 은 S4 부터 출력 등급 전파(enforcePythonOutputLevel)가 재사용
   * TEMP 판정까지 하므로 이 메서드를 쓰지 않는다. 삭제·재생성은 {@link #ensureStepTemp} 를 그대로 지나가 "삭제 전 판정"을
   * SQL·AI_CLASSIFY 와 공유하고, 재사용 TEMP 는 여기서 바로 "쓰기 전 판정"을 한다.
   *
   * <p>왜 재사용 판정을 여기서 하나: SQL·AI_CLASSIFY 는 입력 등급 전파(상향·시드) 뒤 {@code enforceOutputLevel} 이 재사용 TEMP
   * 를 판정하지만, API_CALL 은 전파할 입력 등급이 없어(외부 API 데이터 — 기본 등급, 스펙 §4.5) 그 단계가 없다. 판정이 없으면 관리자가 TEMP 등급을
   * 실행 주체 자격보다 높인 뒤에도 실행 주체가 TEMP 를 비우고(REPLACE) 덮어쓴다. 새로 만든(빈) TEMP 는 이번 실행 주체가 방금 만든 것이라 제외한다(SQL
   * 경로와 같은 규칙).
   *
   * @return 준비된 TEMP(재생성했으면 되돌리기 재료 포함 — 호출자가 스텝 결과 처리에 넘긴다)
   */
  private StepTemp ensureUnpropagatedStepTemp(
      PipelineStepResponse step,
      Optional<Long> existingTemp,
      List<ColumnInfo> columns,
      Long pipelineId,
      String pipelineName,
      Long userId,
      PipelineSecurityGate.RunAs runAs,
      String logLabel) {
    StepTemp temp =
        ensureStepTemp(
            step, existingTemp, columns, pipelineId, pipelineName, userId, runAs, logLabel);
    if (!temp.fresh()) {
      pipelineSecurityGate.requireOutputVisible(temp.datasetId(), runAs);
    }
    return temp;
  }

  /** PYTHON 적재 결과 — 실행 로그와 적재 행 수(REPLACE 맞바꿈 여부 판단 재료). */
  private record PythonLoad(String log, long rowsLoaded) {}

  /**
   * PYTHON 출력 적재의 스테이징 준비·마무리 — 실행기 켠 경로(executor 가 적재)와 끈 경로(API 가 stdout JSON 적재)가 같이 쓴다.
   *
   * <p>REPLACE 면 이번 실행 전용 임시 테이블({@link DataTableService#createTempTable} 이 돌려준 이름, WD-151)을 만들고 그
   * 이름으로 {@code load} 를 부른 뒤, 적재 행 수로 맞바꿈 여부를 {@link DataTableService#finishReplace} 에 맡긴다(0행·JSON
   * 없음이면 원본 유지 — 판단은 거기 단독, #685). Tibero APPEND({@code tiberoAppend}, WD-78)도 같은 스테이징을 거쳐 {@link
   * DataTableService#finishAppend} 로 옮긴다. 실패하면 임시 테이블을 지우고 예외를 다시 던진다 — 원본은 그대로다. 둘 다 아니면(PG
   * APPEND·출력 없음) 출력 테이블(없으면 null)로 바로 부른다.
   */
  private PythonLoad loadPythonOutput(
      String outputTable,
      boolean isReplace,
      boolean tiberoAppend,
      Function<String, PythonLoad> load) {
    return runStaged(
        outputTable,
        isReplace,
        tiberoAppend,
        "Failed to drop temp table after Python execution failure: {}",
        load,
        PythonLoad::rowsLoaded);
  }

  /**
   * 「스테이징 만들기 → 적재 → 마무리(finishReplace/finishAppend) → 실패 시 스테이징 삭제」 반복을 한 곳에 모은 도우미(WD-231) —
   * PYTHON({@link #loadPythonOutput}, 실행기 켬/끔)과 API_CALL(실행기 켬)이 같이 쓴다. 두 곳이 거의 같은 코드를 따로 갖고 있어 한쪽만
   * 고쳐지는 일이 생겼다.
   *
   * <p>REPLACE 면 이번 실행 전용 임시 테이블({@link DataTableService#createTempTable}, WD-151)을 만들고 그 이름으로
   * {@code body} 를 부른 뒤 적재 행 수로 맞바꿈 여부를 {@link DataTableService#finishReplace} 에 맡긴다(0행이면 원본 유지 —
   * 판단은 거기 단독, #685). 스테이징 APPEND({@code stagesAppend}, Tibero)는 {@link
   * DataTableService#finishAppend} 로 옮긴다. 둘 다 아니면(PG APPEND·출력 없음) 스테이징 없이 출력 테이블(없으면 null)로 바로
   * 부른다.
   *
   * <p><b>잡는 예외는 넓은 쪽({@link Exception}, 예전 API_CALL 블록과 같다)이다.</b> 예전 PYTHON 경로는 {@link
   * RuntimeException} 만 잡았지만 새로 잡히는 예외는 없다 — try 본문({@code body}·finishReplace/finishAppend)이 검사
   * 예외를 선언하지 않아(본문이 {@link Function} 으로 컴파일되고, {@code throw e} 가 throws 절 없이 정확한 재던지기로 컴파일되는 것이 그
   * 증거다) 실제로 잡히는 것은 RuntimeException 뿐이다. 넓게 잡는 쪽이 혹시 모를 몰래 던진 검사 예외에도 스테이징을 지운다. 실패하면 스테이징을 지우고(그
   * 실패는 경로별 문구로 경고만 남긴다) 원래 예외를 그대로 다시 던진다 — 원본은 그대로다.
   *
   * @param stagesAppend APPEND 도 스테이징을 거치는가(호출자가 방언에 물어 넘긴다 — REPLACE 해석은 경로마다 달라 호출자가 한다)
   * @param dropFailureLog 스테이징 삭제 실패 경고 문구(경로별로 예전 문구 그대로, {@code {}} 자리에 사유)
   * @param body 적재 대상 테이블 이름(스테이징·출력·null)을 받아 적재하고 결과를 돌려준다
   * @param rowsLoaded 결과에서 적재 행 수를 꺼낸다(맞바꿈·옮기기 판단 재료)
   */
  private <T> T runStaged(
      String outputTable,
      boolean isReplace,
      boolean stagesAppend,
      String dropFailureLog,
      Function<String, T> body,
      ToLongFunction<T> rowsLoaded) {
    if (!isReplace && !stagesAppend) {
      return body.apply(outputTable);
    }
    String tempTable = dataTableService.createTempTable(outputTable);
    try {
      T result = body.apply(tempTable);
      if (isReplace) {
        dataTableService.finishReplace(outputTable, tempTable, rowsLoaded.applyAsLong(result));
      } else {
        // 스테이징의 행을 출력에 한 Tibero 트랜잭션으로 옮긴다(0행이면 스테이징만 버린다).
        // 옮기기가 커밋된 뒤 스테이징 DROP 실패는 저장소가 삼킨다.
        dataTableService.finishAppend(outputTable, tempTable, rowsLoaded.applyAsLong(result));
      }
      return result;
    } catch (Exception e) {
      try {
        dataTableService.dropTempTable(outputTable, tempTable);
      } catch (Exception dropEx) {
        log.warn(dropFailureLog, dropEx.getMessage());
      }
      throw e;
    }
  }

  /**
   * API_CALL 스텝에 들어온 출력(사용자 지정 출력, 또는 재실행에서 {@code PipelineStepRepository.findByPipelineId} 의
   * coalesce 폴백으로 들어온 이 스텝의 재사용 TEMP)을 실행 주체가 볼 수 있어야 한다(코드리뷰 CR2·후속 F1). 반드시 출력 비우기·맞바꿈용 임시 테이블
   * 생성·적재 전에 부른다. PYTHON 은 {@code PipelineSecurityGate#enforcePythonOutputLevel} 이 같은 판정을 하므로 부르지
   * 않는다(코드리뷰 8).
   *
   * <p>들어온 출력은 이번 실행 전부터 있던 데이터셋이라 러너 TEMP 든 아니든 같은 판정을 한다. 예전 판정({@code
   * PipelineSecurityGate.requireExplicitOutputVisible})은 러너 TEMP 를 건너뛰도록 쓰여 있었는데, 그 TEMP 판별이 트랜잭션
   * 밖(러너는 트랜잭션이 없어 RLS GUC 가 없다)에서 돌아 언제나 "TEMP 아님"으로 나와 우연히 판정이 걸려 있었다 — 의도대로 고치면 재사용 TEMP 를 볼 수
   * 없는 실행 주체가 비우고 덮어쓰게 되므로, 판별을 없애고 의도를 명시했다. 출력이 없으면(null) 아무것도 하지 않는다 — 그 경우 TEMP 는 {@link
   * #ensureUnpropagatedStepTemp} 가 판정한다.
   */
  private void requireExistingOutputVisible(
      Long outputDatasetId, PipelineSecurityGate.RunAs runAs) {
    if (outputDatasetId != null) {
      pipelineSecurityGate.requireOutputVisible(outputDatasetId, runAs);
    }
  }

  /**
   * 외부 executor(Python 기반)로 전달할 API 호출 요청 Map을 구성한다. Phase 9: apiConn이 있으면 baseUrl+path로 최종 URL을
   * 계산하여 "url" 필드에 설정.
   */
  private Map<String, Object> buildApiCallExecutorRequest(
      ApiCallConfig config,
      String outputTable,
      Map<String, String> decryptedAuth,
      Map<String, String> columnTypeMap) {
    return buildApiCallExecutorRequest(config, outputTable, decryptedAuth, columnTypeMap, null);
  }

  private Map<String, Object> buildApiCallExecutorRequest(
      ApiCallConfig config,
      String outputTable,
      Map<String, String> decryptedAuth,
      Map<String, String> columnTypeMap,
      ApiConnectionResponse apiConn) {
    // URL 결정: ApiCallExecutor.resolveTargetUrl 규칙과 정확히 일치시킨다.
    // apiConn 설정 시 path 필수 — customUrl/url 폴백 금지(baseUrl 우회 방지).
    String resolvedUrl;
    if (apiConn != null) {
      if (config.path() == null || config.path().isBlank()) {
        throw new ScriptExecutionException("API_CALL: apiConnectionId 설정 시 path가 필수입니다");
      }
      resolvedUrl =
          com.smartfirehub.apiconnection.service.UrlUtils.joinUrl(apiConn.baseUrl(), config.path());
    } else if (config.customUrl() != null && !config.customUrl().isBlank()) {
      resolvedUrl = config.customUrl();
    } else if (config.url() != null && !config.url().isBlank()) {
      resolvedUrl = config.url();
    } else {
      throw new ScriptExecutionException(
          "API_CALL: apiConnectionId 없이 호출하려면 customUrl(또는 레거시 url)이 필수입니다");
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("url", resolvedUrl);
    request.put("method", config.method() != null ? config.method() : "GET");
    if (config.headers() != null) request.put("headers", config.headers());
    if (config.queryParams() != null) request.put("query_params", config.queryParams());
    if (config.body() != null) request.put("body", config.body());
    request.put("data_path", config.dataPath());

    // 필드 매핑 변환
    List<Map<String, Object>> mappings = new ArrayList<>();
    if (config.fieldMappings() != null) {
      for (ApiCallConfig.FieldMapping fm : config.fieldMappings()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_field", fm.sourceField());
        m.put("target_column", fm.targetColumn());
        if (fm.dataType() != null) m.put("data_type", fm.dataType());
        if (fm.dateFormat() != null) m.put("date_format", fm.dateFormat());
        if (fm.numberFormat() != null) m.put("number_format", fm.numberFormat());
        if (fm.sourceTimezone() != null) m.put("source_timezone", fm.sourceTimezone());
        mappings.add(m);
      }
    }
    request.put("field_mappings", mappings);

    // 페이지네이션
    if (config.pagination() != null) {
      Map<String, Object> pag = new LinkedHashMap<>();
      pag.put("type", config.pagination().type());
      if (config.pagination().pageSize() != null)
        pag.put("page_size", config.pagination().pageSize());
      if (config.pagination().offsetParam() != null)
        pag.put("offset_param", config.pagination().offsetParam());
      if (config.pagination().limitParam() != null)
        pag.put("limit_param", config.pagination().limitParam());
      if (config.pagination().totalPath() != null)
        pag.put("total_path", config.pagination().totalPath());
      request.put("pagination", pag);
    }

    // 재시도 설정
    if (config.retry() != null) {
      Map<String, Object> retry = new LinkedHashMap<>();
      if (config.retry().maxRetries() != null)
        retry.put("max_retries", config.retry().maxRetries());
      if (config.retry().initialBackoffMs() != null)
        retry.put("initial_backoff_ms", config.retry().initialBackoffMs());
      if (config.retry().maxBackoffMs() != null)
        retry.put("max_backoff_ms", config.retry().maxBackoffMs());
      request.put("retry", retry);
    }

    if (config.timeoutMs() != null) request.put("timeout_ms", config.timeoutMs());
    if (config.maxDurationMs() != null) request.put("max_duration_ms", config.maxDurationMs());
    if (config.maxResponseSizeMb() != null)
      request.put("max_response_size_mb", config.maxResponseSizeMb());

    request.put("output_table", outputTable);
    // executor는 항상 APPEND (INSERT only) — 로드 전략은 API 레이어에서 처리
    request.put("load_strategy", "APPEND");

    if (columnTypeMap != null) request.put("column_type_map", columnTypeMap);
    if (decryptedAuth != null) request.put("auth", decryptedAuth);

    return request;
  }

  /**
   * API_CALL 스텝의 fieldMappings에서 임시 데이터셋 컬럼 목록을 추론한다.
   *
   * @param config API 호출 설정
   * @return 컬럼 정보 목록
   */
  private List<ColumnInfo> inferApiCallColumns(ApiCallConfig config) {
    if (config.fieldMappings() == null || config.fieldMappings().isEmpty()) {
      return List.of();
    }
    return config.fieldMappings().stream()
        .map(
            fm -> {
              String appType = "TEXT";
              if (fm.dataType() != null) {
                String dt = fm.dataType().toUpperCase();
                if (dt.contains("INT")) appType = "INTEGER";
                else if (dt.contains("NUMERIC")
                    || dt.contains("DECIMAL")
                    || dt.contains("FLOAT")
                    || dt.contains("DOUBLE")) appType = "DECIMAL";
                else if (dt.contains("BOOL")) appType = "BOOLEAN";
                else if (dt.equals("DATE")) appType = "DATE";
                else if (dt.contains("TIMESTAMP")) appType = "TIMESTAMP";
              }
              return new ColumnInfo(fm.targetColumn(), appType);
            })
        .collect(Collectors.toList());
  }

  /**
   * AI_CLASSIFY 스텝의 출력 컬럼 목록을 구성한다. source_id는 항상 첫 번째 컬럼으로 포함된다.
   *
   * @param config AI 분류 설정
   * @return 컬럼 정보 목록
   */
  private List<ColumnInfo> buildAiClassifyColumns(
      com.smartfirehub.pipeline.dto.AiClassifyConfig config) {
    List<ColumnInfo> columns = new ArrayList<>();
    // source_id는 입력 행 추적용으로 항상 첫 번째에 포함
    columns.add(new ColumnInfo("source_id", "INTEGER"));
    if (config.outputColumns() != null) {
      config.outputColumns().stream()
          .map(col -> new ColumnInfo(col.name(), col.type()))
          .forEach(columns::add);
    }
    return columns;
  }

  /**
   * SQL 내 {@code {{#N}}} 스텝 참조를 실제 테이블명으로 치환한다.
   *
   * @param sql 원본 SQL 문자열
   * @param pipelineId 파이프라인 ID
   * @param currentStep 현재 실행 중인 스텝 (자기 참조 방지 + 의존성 체인 검증에 사용)
   * @return 참조가 치환된 SQL 문자열
   */
  private String resolveStepReferences(
      String sql, Long pipelineId, PipelineStepResponse currentStep) {
    Pattern pattern = Pattern.compile("\\{\\{#(\\d+)\\}\\}");
    Matcher matcher = pattern.matcher(sql);
    if (!matcher.find()) {
      return sql;
    }

    List<PipelineStepResponse> allSteps = stepRepository.findByPipelineId(pipelineId);

    // 현재 스텝의 실제 선행(조상) 스텝 이름 집합 — dependsOnStepNames 체인을 재귀적으로 따라 수집한다.
    // {{#N}}의 N은 단순 배열 인덱스(step_order)일 뿐이라, "스텝 삽입"으로 DAG가 비선형이 되면
    // 인덱스 범위 검사만으로는 아직 실행되지 않은(산출물 없는) 후행 스텝까지 참조가 허용된다 (#531).
    Set<String> ancestorStepNames = collectAncestorStepNames(currentStep, allSteps);

    matcher.reset();
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      int stepNumber = Integer.parseInt(matcher.group(1)); // 1-based
      int stepIndex = stepNumber - 1; // 0-based

      if (stepIndex < 0 || stepIndex >= allSteps.size()) {
        throw new ScriptExecutionException(
            "{{#" + stepNumber + "}} 참조 실패: 스텝 번호 " + stepNumber + "이 존재하지 않습니다");
      }

      if (stepIndex == currentStep.stepOrder()) {
        throw new ScriptExecutionException("{{#" + stepNumber + "}} 참조 실패: 자기 자신을 참조할 수 없습니다");
      }

      PipelineStepResponse refStep = allSteps.get(stepIndex);

      if (!ancestorStepNames.contains(refStep.name())) {
        throw new ScriptExecutionException(
            "{{#"
                + stepNumber
                + "}} 참조 실패: 스텝 '"
                + refStep.name()
                + "'은(는) 현재 스텝의 선행 스텝(의존성 체인)이 아닙니다. 실행 순서상 먼저 실행되는 스텝만 참조할 수 있습니다");
      }

      Long datasetId = refStep.outputDatasetId();

      if (datasetId == null) {
        datasetId =
            datasetRepository
                .findBySourcePipelineStepId(refStep.id())
                .orElseThrow(
                    () ->
                        new ScriptExecutionException(
                            "{{#"
                                + stepNumber
                                + "}} 참조 실패: 스텝 '"
                                + refStep.name()
                                + "'의 출력 데이터셋이 아직 생성되지 않았습니다"));
      }

      final Long resolvedDatasetId = datasetId;
      String tableName =
          datasetRepository
              .findTableNameById(resolvedDatasetId)
              .orElseThrow(
                  () ->
                      new ScriptExecutionException(
                          "{{#" + stepNumber + "}} 참조 실패: 데이터셋 테이블을 찾을 수 없습니다"));

      // {{#n}} 참조를 실제 테이블 FQN 으로 치환한다 — 스키마는 현재 테넌트에서 파생시킨다.
      // Tibero 테넌트는 "GENIA_T{id}"."<대문자 물리명>"(WD-77, 스펙 §5.0),
      // PG 는 DataSchema.qualify 그대로다(PipelineDataSchema 가 고른다).
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(pipelineDataSchema.qualify(tableName)));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * 현재 스텝의 실제 선행(조상) 스텝 이름 집합을 dependsOnStepNames 체인을 따라 재귀적으로 수집한다.
   *
   * <p>{{#N}} 스텝 참조가 배열 인덱스만으로 임의의 스텝을 가리키지 못하도록, 실제 DAG 의존성 체인에 포함된 스텝만 참조 가능하도록 검증하는 데 사용한다
   * (#531). 순환 참조가 있더라도 방문 집합으로 무한루프를 방지한다.
   *
   * @param currentStep 현재 실행 중인 스텝
   * @param allSteps 파이프라인의 전체 스텝 목록
   * @return 현재 스텝의 직접·간접 선행 스텝 이름 집합
   */
  private Set<String> collectAncestorStepNames(
      PipelineStepResponse currentStep, List<PipelineStepResponse> allSteps) {
    Map<String, PipelineStepResponse> stepByName = new HashMap<>();
    for (PipelineStepResponse s : allSteps) {
      stepByName.put(s.name(), s);
    }

    Set<String> visited = new HashSet<>();
    Deque<String> stack = new ArrayDeque<>();
    if (currentStep.dependsOnStepNames() != null) {
      stack.addAll(currentStep.dependsOnStepNames());
    }

    while (!stack.isEmpty()) {
      String name = stack.pop();
      if (name == null || visited.contains(name)) {
        continue;
      }
      visited.add(name);
      PipelineStepResponse depStep = stepByName.get(name);
      if (depStep != null && depStep.dependsOnStepNames() != null) {
        stack.addAll(depStep.dependsOnStepNames());
      }
    }

    return visited;
  }

  /**
   * 백엔드에 맞는 스텝 방언을 만든다(WD-231, 스펙 §3.2). 스프링 빈이 아니다 — 러너가 자기 협력 객체로 스텝마다 만들고(생성자 호출만이라 가볍다), 러너
   * 생성자·필드 목록은 그대로다. 테넌트 백엔드는 생성 후 불변이라 스텝 안에서 같은 방언(이 메서드가 만든 하나)을 쓴다.
   *
   * <p>Tibero 전용 열 탐침·SQL 실행 본체와 PG 래핑 조립·실패 번역은 {@link StepDialect} 인터페이스에 없는 공개 메서드라, {@code
   * executeStep} 의 SQL 분기가 구체 타입으로 갈라 부른다 — PG 싱크가 보안 불변식 때문에 그 메서드에 남아 있어서다.
   */
  private StepDialect dialectFor(DataStoreBackend backend) {
    if (backend == DataStoreBackend.TIBERO) {
      return new TiberoStepDialect(
          pipelineDataSchema,
          dataTableService,
          tiberoIncrementalCursor,
          dbExecutorClient,
          scriptTokenIssuer,
          tenantDataStoreRepository,
          pythonExecutor,
          localPythonOutputLoader,
          columnRepository);
    }
    return new PgStepDialect(
        incrementalCursorService, pythonExecutor, localPythonOutputLoader, columnRepository);
  }

  /**
   * PYTHON 실행 실패 예외. 실행 중 등급 정의 변경으로 이 테넌트의 슬롯 롤 세션이 끊겼다면(PythonReadGrantSync 가 새 GRANT 커밋 전에 끊는다)
   * 원인을 앞에 밝힌다 — 스크립트 출력에는 "terminating connection due to administrator command" 만 남아 사용자가 원인을 알 수
   * 없다. 판정에는 쓰지 않는다(끊김 자체가 fail-closed 이고, 이 문구는 안내일 뿐).
   */
  private ScriptExecutionException pythonRunFailure(
      String message, long tenantId, long startedAtMillis) {
    if (pythonReadGrants.slotSessionsTerminatedSince(tenantId, startedAtMillis)) {
      return new ScriptExecutionException(
          "실행 중 보안 등급 구성이 바뀌어 Python 읽기 연결을 끊었습니다. 다시 실행하세요. — " + message);
    }
    return new ScriptExecutionException(message);
  }

  /**
   * SQL SELECT 결과로 러너 TEMP 를 만들기 전에 결과 컬럼명을 데이터셋 이름 규칙({@link DataTableNames#isValid})으로 확인하고, 어긋나면
   * SQL 에서 고칠 방법을 담은 한국어 안내로 실패시킨다(WD-111).
   *
   * <p>별칭 없는 식 컬럼은 PostgreSQL 이 {@code ?column?} 같은 이름을 붙이고, 따옴표로 대문자·공백을 넣은 별칭도 규칙에 맞지 않는다. 데이터셋
   * 공용 검증 문구는 데이터셋 생성 화면 등 다른 호출처와 공유하므로 바꾸지 않고, SQL 문맥의 안내(AS 로 이름 붙이기)는 이 경로에서만 낸다. 예약어 별칭 처리 뒤의
   * 이름을 받는다 — {@code _updated_at} 같은 예약어는 이미 {@code updated_at_1} 로 바뀌어 여기 걸리지 않는다.
   */
  private static void rejectInvalidResultColumnNames(List<ColumnInfo> columns) {
    for (ColumnInfo column : columns) {
      if (!DataTableNames.isValid(column.name())) {
        throw new ScriptExecutionException(
            "SQL 결과 컬럼 이름 '"
                + column.name()
                + "' 은 쓸 수 없습니다. 계산 컬럼에는 AS 로 이름을 붙여 주세요(예: SELECT 1 AS value)."
                + " 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다.");
      }
    }
  }

  /**
   * {@link SqlOutputColumns#renameReservedColumnNames(List)}의 {@link ColumnInfo} 목록 버전 (타입 정보는 그대로
   * 보존).
   */
  private static List<ColumnInfo> renameReservedColumns(List<ColumnInfo> columns) {
    List<String> renamedNames =
        SqlOutputColumns.renameReservedColumnNames(columns.stream().map(ColumnInfo::name).toList());
    List<ColumnInfo> result = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      result.add(new ColumnInfo(renamedNames.get(i), columns.get(i).appType()));
    }
    return result;
  }
}
