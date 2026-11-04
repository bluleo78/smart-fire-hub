package com.smartfirehub.pipeline.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PipelineExecutionRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Slf4j
@Transactional
public class JooqPipelineExecutionRepository implements PipelineExecutionRepository {

  private final DSLContext dsl;

  /** DB 오류 판정 창구 — FK 위반 제약 이름을 벤더 중립으로 알아본다(WD-486). */
  private final PlatformDbErrors platformDbErrors;

  // Table constants
  private static final Table<?> PIPELINE_EXECUTION = table(name("pipeline_execution"));
  private static final Field<Long> PE_ID = field(name("pipeline_execution", "id"), Long.class);
  private static final Field<Long> PE_PIPELINE_ID =
      field(name("pipeline_execution", "pipeline_id"), Long.class);
  private static final Field<String> PE_STATUS =
      field(name("pipeline_execution", "status"), String.class);
  // 테넌트 열(V93) — 스윕 게이트가 RLS 에 더해 테넌트를 명시적으로 좁힐 때 쓴다.
  private static final Field<Long> PE_TENANT_ID =
      field(name("pipeline_execution", "tenant_id"), Long.class);
  // 하트비트 시각(V148, WD-230) — UTC. 실행을 쥔 api 가 주기적으로 갱신해, api 재시작으로 고아가 된 실행을 가려낸다.
  private static final Field<LocalDateTime> PE_HEARTBEAT_AT =
      field(name("pipeline_execution", "heartbeat_at"), LocalDateTime.class);
  private static final Field<Long> PE_EXECUTED_BY =
      field(name("pipeline_execution", "executed_by"), Long.class);
  private static final Field<LocalDateTime> PE_STARTED_AT =
      field(name("pipeline_execution", "started_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PE_COMPLETED_AT =
      field(name("pipeline_execution", "completed_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PE_CREATED_AT =
      field(name("pipeline_execution", "created_at"), LocalDateTime.class);
  private static final Field<String> PE_TRIGGERED_BY =
      field(name("pipeline_execution", "triggered_by"), String.class);
  private static final Field<Long> PE_TRIGGER_ID =
      field(name("pipeline_execution", "trigger_id"), Long.class);
  private static final Field<String> PE_ERROR_MESSAGE =
      field(name("pipeline_execution", "error_message"), String.class);
  // 취소 요청 시각·요청자(V141, WD-153). 러너 체크포인트가 이 값을 읽어 취소를 알아챈다 — 여러 API 인스턴스 중 어디서 요청을 받아도 보이도록
  // 메모리가 아니라 DB 에 둔다.
  private static final Field<LocalDateTime> PE_CANCEL_REQUESTED_AT =
      field(name("pipeline_execution", "cancel_requested_at"), LocalDateTime.class);
  private static final Field<Long> PE_CANCEL_REQUESTED_BY =
      field(name("pipeline_execution", "cancel_requested_by"), Long.class);

  /** 아직 끝나지 않은 실행 상태 — 실행 상태 쓰기는 이 상태에서만 일어난다(끝난 실행을 덮어쓰지 않는다, WD-153). */
  private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING");

  private static final Table<?> PIPELINE_TRIGGER = table(name("pipeline_trigger"));
  private static final Field<Long> PT_ID = field(name("pipeline_trigger", "id"), Long.class);
  private static final Field<String> PT_NAME =
      field(name("pipeline_trigger", "name"), String.class);

  private static final Table<?> PIPELINE_STEP_EXECUTION = table(name("pipeline_step_execution"));
  private static final Field<Long> PSE_ID =
      field(name("pipeline_step_execution", "id"), Long.class);
  private static final Field<Long> PSE_EXECUTION_ID =
      field(name("pipeline_step_execution", "execution_id"), Long.class);
  private static final Field<Long> PSE_STEP_ID =
      field(name("pipeline_step_execution", "step_id"), Long.class);
  private static final Field<String> PSE_STATUS =
      field(name("pipeline_step_execution", "status"), String.class);
  private static final Field<Integer> PSE_OUTPUT_ROWS =
      field(name("pipeline_step_execution", "output_rows"), Integer.class);
  private static final Field<String> PSE_LOG =
      field(name("pipeline_step_execution", "log"), String.class);
  private static final Field<String> PSE_ERROR_MESSAGE =
      field(name("pipeline_step_execution", "error_message"), String.class);
  private static final Field<LocalDateTime> PSE_STARTED_AT =
      field(name("pipeline_step_execution", "started_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PSE_COMPLETED_AT =
      field(name("pipeline_step_execution", "completed_at"), LocalDateTime.class);

  // 이번 실행이 {{last_run_at}} 자리에 실제로 넣은 값(V123). null 이면 전체 읽기(-infinity)였다는 뜻이다.
  // 운영 진단용 — "이 실행이 어디서부터 읽었는가"를 사후에 확인할 유일한 기록이다.
  private static final Field<java.time.OffsetDateTime> PSE_INJECTED_LAST_RUN_AT =
      field(
          name("pipeline_step_execution", "injected_last_run_at"), java.time.OffsetDateTime.class);
  // 실행 당시 스텝 이름 스냅샷(V139, WD-108). 스텝이 정의에서 지워지면 step_id 는 NULL 이 되고 이 이름만 남는다.
  private static final Field<String> PSE_STEP_NAME =
      field(name("pipeline_step_execution", "step_name"), String.class);

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  // 취소 요청자 이름 조인용 별칭 — 실행자(executed_by) 조인과 같은 "user" 테이블을 한 번 더 붙인다.
  private static final Table<?> CANCELLER = table(name("user")).as("canceller");
  private static final Field<Long> CANCELLER_ID = field(name("canceller", "id"), Long.class);
  private static final Field<String> CANCELLER_NAME =
      field(name("canceller", "name"), String.class).as("canceller_name");

  private static final Table<?> PIPELINE = table(name("pipeline"));
  private static final Field<Long> P_ID = field(name("pipeline", "id"), Long.class);
  private static final Field<String> P_NAME = field(name("pipeline", "name"), String.class);

  private static final Table<?> PIPELINE_STEP = table(name("pipeline_step"));
  private static final Field<Long> PS_ID = field(name("pipeline_step", "id"), Long.class);
  private static final Field<String> PS_NAME = field(name("pipeline_step", "name"), String.class);

  @Override
  public Long createExecution(Long pipelineId, Long executedBy) {
    return createExecution(pipelineId, executedBy, "MANUAL", null);
  }

  @Override
  public Long createExecution(
      Long pipelineId, Long executedBy, String triggeredBy, Long triggerId) {
    var query =
        dsl.insertInto(PIPELINE_EXECUTION)
            .set(PE_PIPELINE_ID, pipelineId)
            .set(PE_STATUS, "PENDING")
            .set(PE_EXECUTED_BY, executedBy)
            // 제출 시점 하트비트(WD-230). created_at 은 DB 세션 시간대라 UTC 임계값과 어긋날 수 있다.
            // 판정은 Java UTC 로 찍은 이 값에 맡기며, 늘 채워지므로 created_at 폴백은 실제로 쓰이지 않는다.
            .set(PE_HEARTBEAT_AT, LocalDateTime.now(ZoneOffset.UTC))
            .set(PE_TRIGGERED_BY, triggeredBy != null ? triggeredBy : "MANUAL");

    if (triggerId != null) {
      query = query.set(PE_TRIGGER_ID, triggerId);
    }

    try {
      return query.returning(PE_ID).fetchOne(r -> r.get(PE_ID));
    } catch (DataIntegrityViolationException e) {
      // 파이프라인이 그 사이 삭제됐으면(WD-437 — 경쟁 정리는 PipelineRepository#lockById 참고) FK 위반 대신 404 로 알린다.
      if (platformDbErrors
          .violatedConstraint(e)
          .filter(PIPELINE_FK_CONSTRAINT::equals)
          .isPresent()) {
        throw new PipelineNotFoundException();
      }
      throw e;
    }
  }

  /** pipeline_execution.pipeline_id → pipeline(id) FK 이름(V3 기본 이름). */
  private static final String PIPELINE_FK_CONSTRAINT = "pipeline_execution_pipeline_id_fkey";

  @Override
  public boolean updateExecutionStatus(
      Long executionId, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    return updateExecutionStatus(executionId, status, startedAt, completedAt, null);
  }

  @Override
  public boolean updateExecutionStatus(
      Long executionId,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      String errorMessage) {
    var query = dsl.update(PIPELINE_EXECUTION).set(PE_STATUS, status);

    if (startedAt != null) {
      query = query.set(PE_STARTED_AT, startedAt);
    }

    // RUNNING 전환은 곧 첫 하트비트다(WD-230) — 시작 시각을 그대로 쓴다.
    if ("RUNNING".equals(status) && startedAt != null) {
      query = query.set(PE_HEARTBEAT_AT, startedAt);
    }

    if (completedAt != null) {
      query = query.set(PE_COMPLETED_AT, completedAt);
    }

    if (errorMessage != null) {
      query = query.set(PE_ERROR_MESSAGE, errorMessage);
    }

    // 끝나지 않은 실행만 갱신한다(WD-153) — 실행 상태를 쓰는 주체가 러너와 취소 API 둘이라, 무조건 쓰면 대기 중 취소로 이미 CANCELLED 가 된
    // 실행을 러너 시작 쓰기(RUNNING)가 되살리거나, 끝난 실행 상태를 다른 쪽이 덮어쓸 수 있다. 조건부 UPDATE 라 먼저 마감한 쪽이 이긴다.
    return query.where(PE_ID.eq(executionId)).and(PE_STATUS.in(ACTIVE_STATUSES)).execute() == 1;
  }

  @Override
  public boolean finishUnlessCancelRequested(
      Long executionId, String status, LocalDateTime completedAt) {
    return dsl.update(PIPELINE_EXECUTION)
            .set(PE_STATUS, status)
            .set(PE_COMPLETED_AT, completedAt)
            .where(PE_ID.eq(executionId))
            .and(PE_STATUS.in(ACTIVE_STATUSES))
            .and(PE_CANCEL_REQUESTED_AT.isNull())
            .execute()
        == 1;
  }

  @Override
  public int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now) {
    if (executionIds == null || executionIds.isEmpty()) {
      return 0;
    }
    return dsl.update(PIPELINE_EXECUTION)
        .set(PE_HEARTBEAT_AT, now)
        .where(PE_ID.in(executionIds))
        .and(PE_STATUS.in(ACTIVE_STATUSES))
        .execute();
  }

  @Override
  public List<StaleExecution> findStaleExecutions(long tenantId, LocalDateTime threshold) {
    return dsl.select(PE_ID, PE_PIPELINE_ID)
        .from(PIPELINE_EXECUTION)
        .where(PE_TENANT_ID.eq(tenantId))
        .and(PE_STATUS.in(ACTIVE_STATUSES))
        .and(coalesce(PE_HEARTBEAT_AT, PE_STARTED_AT, PE_CREATED_AT).lt(threshold))
        .fetch(r -> new StaleExecution(r.get(PE_ID), r.get(PE_PIPELINE_ID)));
  }

  @Override
  public boolean failInterruptedAndCloseSteps(long executionId, LocalDateTime now, String message) {
    boolean won =
        dsl.update(PIPELINE_EXECUTION)
                .set(PE_STATUS, "FAILED")
                .set(PE_COMPLETED_AT, now)
                .set(PE_ERROR_MESSAGE, message)
                .where(PE_ID.eq(executionId))
                .and(PE_STATUS.in(ACTIVE_STATUSES))
                .execute()
            == 1;
    if (!won) {
      return false;
    }
    // RUNNING 단계는 FAILED, 아직 시작 못 한 PENDING 은 SKIPPED. 실행이 이미 FAILED 라 updateStepExecution 의 「실행이
    // 미완료일 때만」
    // 조건을 타지 않는 별도 쓰기다.
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_STATUS, "FAILED")
        .set(PSE_ERROR_MESSAGE, message)
        .set(PSE_COMPLETED_AT, now)
        .where(PSE_EXECUTION_ID.eq(executionId))
        .and(PSE_STATUS.eq("RUNNING"))
        .execute();
    skipPendingSteps(executionId, message, now);
    return true;
  }

  @Override
  public boolean cancelAndSkipPendingSteps(Long executionId, LocalDateTime completedAt) {
    boolean won =
        updateExecutionStatus(
            executionId,
            "CANCELLED",
            null,
            completedAt,
            PipelineExecutionCancelledException.CANCELLED_MESSAGE);
    if (won) {
      skipPendingStepsForCancel(executionId);
    }
    return won;
  }

  @Override
  public boolean isStopRequested(long executionId) {
    var row =
        dsl.select(PE_STATUS, PE_CANCEL_REQUESTED_AT)
            .from(PIPELINE_EXECUTION)
            .where(PE_ID.eq(executionId))
            .fetchOptional();
    if (row.isEmpty()) {
      log.warn("Pipeline execution {} 실행 행이 없어 중지합니다(삭제되었거나 보이지 않음 — 사용자 취소 아님)", executionId);
      return true;
    }
    return row.get().get(PE_CANCEL_REQUESTED_AT) != null
        || !ACTIVE_STATUSES.contains(row.get().get(PE_STATUS));
  }

  @Override
  public ActiveExecutionState findActiveExecutionState(long pipelineId) {
    var counts =
        dsl.select(count(), count().filterWhere(PE_CANCEL_REQUESTED_AT.isNull()))
            .from(PIPELINE_EXECUTION)
            .where(PE_PIPELINE_ID.eq(pipelineId))
            .and(PE_STATUS.in(ACTIVE_STATUSES))
            .fetchOne();
    int active = counts.value1();
    int notCancelling = counts.value2();
    if (active == 0) {
      return ActiveExecutionState.NONE;
    }
    return notCancelling > 0 ? ActiveExecutionState.ACTIVE : ActiveExecutionState.CANCELLING;
  }

  @Override
  public Optional<CancelRequestResult> requestCancel(Long executionId, Long userId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    Optional<LocalDateTime> pendingCancelled =
        dsl.update(PIPELINE_EXECUTION)
            .set(PE_STATUS, "CANCELLED")
            .set(PE_COMPLETED_AT, now)
            .set(PE_ERROR_MESSAGE, PipelineExecutionCancelledException.CANCELLED_MESSAGE)
            .set(PE_CANCEL_REQUESTED_AT, now)
            .set(PE_CANCEL_REQUESTED_BY, userId)
            .where(PE_ID.eq(executionId))
            .and(PE_STATUS.eq("PENDING"))
            .returning(PE_CANCEL_REQUESTED_AT)
            .fetchOptional(r -> r.get(PE_CANCEL_REQUESTED_AT));
    if (pendingCancelled.isPresent()) {
      skipPendingStepsForCancel(executionId);
      return Optional.of(new CancelRequestResult("CANCELLED", pendingCancelled.get()));
    }
    return dsl.update(PIPELINE_EXECUTION)
        .set(PE_CANCEL_REQUESTED_AT, coalesce(PE_CANCEL_REQUESTED_AT, val(now)))
        .set(PE_CANCEL_REQUESTED_BY, coalesce(PE_CANCEL_REQUESTED_BY, val(userId)))
        .where(PE_ID.eq(executionId))
        .and(PE_STATUS.eq("RUNNING"))
        .returning(PE_CANCEL_REQUESTED_AT)
        .fetchOptional(r -> new CancelRequestResult("RUNNING", r.get(PE_CANCEL_REQUESTED_AT)));
  }

  @Override
  public void skipPendingStepsForCancel(Long executionId) {
    skipPendingSteps(
        executionId,
        PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE,
        LocalDateTime.now(ZoneOffset.UTC));
  }

  /**
   * 실행의 PENDING 단계를 주어진 사유·시각으로 SKIPPED 마감하는 공통 쓰기 — 취소 마감과 중단 정리가 같이 쓴다. 실행 상태 조건은 걸지 않는다(호출자가 실행을
   * 먼저 닫은 뒤에도 써야 하므로).
   */
  private void skipPendingSteps(Long executionId, String message, LocalDateTime now) {
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_STATUS, "SKIPPED")
        .set(PSE_ERROR_MESSAGE, message)
        .set(PSE_COMPLETED_AT, now)
        .where(PSE_EXECUTION_ID.eq(executionId))
        .and(PSE_STATUS.eq("PENDING"))
        .execute();
  }

  @Override
  public boolean hasRunningExecution(long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PIPELINE_EXECUTION)
            .where(PE_TENANT_ID.eq(tenantId))
            .and(PE_STATUS.eq("RUNNING")));
  }

  @Override
  public Optional<Long> findPipelineIdOfExecution(Long executionId) {
    return dsl.select(PE_PIPELINE_ID)
        .from(PIPELINE_EXECUTION)
        .where(PE_ID.eq(executionId))
        .fetchOptional(PE_PIPELINE_ID);
  }

  @Override
  public Long createStepExecution(Long executionId, Long stepId, String stepName) {
    return dsl.insertInto(PIPELINE_STEP_EXECUTION)
        .set(PSE_EXECUTION_ID, executionId)
        .set(PSE_STEP_ID, stepId)
        .set(PSE_STEP_NAME, stepName)
        .set(PSE_STATUS, "PENDING")
        .returning(PSE_ID)
        .fetchOne(r -> r.get(PSE_ID));
  }

  @Override
  public boolean hasOverlappingStepExecution(long stepExecId) {
    var me =
        dsl.select(PSE_STEP_ID, PSE_STARTED_AT)
            .from(PIPELINE_STEP_EXECUTION)
            .where(PSE_ID.eq(stepExecId))
            .fetchOptional()
            .orElse(null);
    if (me == null || me.get(PSE_STEP_ID) == null || me.get(PSE_STARTED_AT) == null) {
      return true;
    }
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PIPELINE_STEP_EXECUTION)
            .where(PSE_STEP_ID.eq(me.get(PSE_STEP_ID)))
            .and(PSE_ID.ne(stepExecId))
            .and(
                PSE_STATUS
                    .eq("RUNNING")
                    .or(
                        PSE_STATUS
                            .ne("PENDING")
                            .and(PSE_COMPLETED_AT.ge(me.get(PSE_STARTED_AT))))));
  }

  @Override
  public void updateStepProgress(Long stepExecId, Integer outputRows, String log) {
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_OUTPUT_ROWS, outputRows)
        .set(PSE_LOG, log)
        .where(PSE_ID.eq(stepExecId))
        .execute();
  }

  @Override
  public void setInjectedLastRunAt(Long stepExecId, java.time.OffsetDateTime value) {
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_INJECTED_LAST_RUN_AT, value)
        .where(PSE_ID.eq(stepExecId))
        .execute();
  }

  @Override
  public boolean updateStepExecution(
      Long stepExecId,
      String status,
      Integer outputRows,
      String log,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt) {
    var query = dsl.update(PIPELINE_STEP_EXECUTION).set(PSE_STATUS, status);

    if (outputRows != null) {
      query = query.set(PSE_OUTPUT_ROWS, outputRows);
    }

    if (log != null) {
      query = query.set(PSE_LOG, log);
    }

    if (errorMessage != null) {
      query = query.set(PSE_ERROR_MESSAGE, errorMessage);
    }

    if (startedAt != null) {
      query = query.set(PSE_STARTED_AT, startedAt);
    }

    if (completedAt != null) {
      query = query.set(PSE_COMPLETED_AT, completedAt);
    }

    int rows =
        query
            .where(PSE_ID.eq(stepExecId))
            // 상관 EXISTS — 이 단계의 부모 실행 한 건만 본다(비상관 IN 은 활성 실행 전체를 훑는다).
            .andExists(
                dsl.selectOne()
                    .from(PIPELINE_EXECUTION)
                    .where(PE_ID.eq(PSE_EXECUTION_ID))
                    .and(PE_STATUS.in(ACTIVE_STATUSES)))
            .execute();
    if (rows == 0) {
      // 매개변수 log(스텝 로그)가 Lombok 로거를 가려 클래스명으로 지정한다.
      JooqPipelineExecutionRepository.log.debug(
          "스텝 실행 {} 쓰기 무반영 — 부모 실행이 이미 끝났거나 행이 없다", stepExecId);
    }
    return rows == 1;
  }

  @Override
  public List<PipelineExecutionResponse> findExecutionsByPipelineId(Long pipelineId) {
    return dsl.select(
            PE_ID,
            PE_PIPELINE_ID,
            PE_STATUS,
            PE_EXECUTED_BY,
            PE_STARTED_AT,
            PE_COMPLETED_AT,
            PE_CREATED_AT,
            U_NAME,
            PE_TRIGGERED_BY,
            PT_NAME,
            PE_CANCEL_REQUESTED_AT)
        .from(PIPELINE_EXECUTION)
        .leftJoin(USER_TABLE)
        .on(PE_EXECUTED_BY.eq(U_ID))
        .leftJoin(PIPELINE_TRIGGER)
        .on(PE_TRIGGER_ID.eq(PT_ID))
        .where(PE_PIPELINE_ID.eq(pipelineId))
        .orderBy(PE_ID.desc())
        .fetch(
            r ->
                new PipelineExecutionResponse(
                    r.get(PE_ID),
                    r.get(PE_PIPELINE_ID),
                    r.get(PE_STATUS),
                    r.get(U_NAME) != null ? r.get(U_NAME) : String.valueOf(r.get(PE_EXECUTED_BY)),
                    r.get(PE_STARTED_AT),
                    r.get(PE_COMPLETED_AT),
                    r.get(PE_CREATED_AT),
                    r.get(PE_TRIGGERED_BY),
                    r.get(PT_NAME),
                    r.get(PE_CANCEL_REQUESTED_AT)));
  }

  @Override
  public Optional<ExecutionDetailResponse> findExecutionById(Long executionId) {
    // Get execution info
    var executionOpt =
        dsl.select(
                PE_ID,
                PE_PIPELINE_ID,
                PE_STATUS,
                PE_EXECUTED_BY,
                PE_STARTED_AT,
                PE_COMPLETED_AT,
                PE_CREATED_AT,
                PE_ERROR_MESSAGE,
                P_NAME,
                U_NAME,
                PE_CANCEL_REQUESTED_AT,
                CANCELLER_NAME)
            .from(PIPELINE_EXECUTION)
            .join(PIPELINE)
            .on(PE_PIPELINE_ID.eq(P_ID))
            .leftJoin(USER_TABLE)
            .on(PE_EXECUTED_BY.eq(U_ID))
            .leftJoin(CANCELLER)
            .on(PE_CANCEL_REQUESTED_BY.eq(CANCELLER_ID))
            .where(PE_ID.eq(executionId))
            .fetchOptional();

    if (executionOpt.isEmpty()) {
      return Optional.empty();
    }

    var exec = executionOpt.get();

    // Get step executions
    List<StepExecutionResponse> stepExecutions = findStepExecutionsByExecutionId(executionId);

    return Optional.of(
        new ExecutionDetailResponse(
            exec.get(PE_ID),
            exec.get(PE_PIPELINE_ID),
            exec.get(P_NAME),
            exec.get(PE_STATUS),
            exec.get(U_NAME) != null ? exec.get(U_NAME) : String.valueOf(exec.get(PE_EXECUTED_BY)),
            stepExecutions,
            exec.get(PE_STARTED_AT),
            exec.get(PE_COMPLETED_AT),
            exec.get(PE_CREATED_AT),
            exec.get(PE_ERROR_MESSAGE),
            false,
            exec.get(PE_CANCEL_REQUESTED_AT),
            exec.get(CANCELLER_NAME)));
  }

  @Override
  public Optional<Long> findExecutedById(Long executionId) {
    return dsl.select(PE_EXECUTED_BY)
        .from(PIPELINE_EXECUTION)
        .where(PE_ID.eq(executionId))
        .fetchOptional(PE_EXECUTED_BY);
  }

  @Override
  public List<StepExecutionResponse> findStepExecutionsByExecutionId(Long executionId) {
    Field<String> stepName = coalesce(PSE_STEP_NAME, PS_NAME).as("resolved_step_name");
    return dsl.select(
            PSE_ID,
            PSE_STEP_ID,
            stepName,
            PSE_STATUS,
            PSE_OUTPUT_ROWS,
            PSE_LOG,
            PSE_ERROR_MESSAGE,
            PSE_STARTED_AT,
            PSE_COMPLETED_AT)
        .from(PIPELINE_STEP_EXECUTION)
        .leftJoin(PIPELINE_STEP)
        .on(PSE_STEP_ID.eq(PS_ID))
        .where(PSE_EXECUTION_ID.eq(executionId))
        .orderBy(PSE_ID.asc())
        .fetch(
            r ->
                new StepExecutionResponse(
                    r.get(PSE_ID),
                    r.get(PSE_STEP_ID),
                    r.get(stepName),
                    r.get(PSE_STATUS),
                    r.get(PSE_OUTPUT_ROWS),
                    r.get(PSE_LOG),
                    r.get(PSE_ERROR_MESSAGE),
                    r.get(PSE_STARTED_AT),
                    r.get(PSE_COMPLETED_AT),
                    false));
  }

  @Override
  public int deleteCompletedCreatedBefore(LocalDateTime cutoff) {
    return dsl.deleteFrom(PIPELINE_EXECUTION)
        .where(PE_CREATED_AT.lt(cutoff))
        .and(PE_STATUS.eq("COMPLETED"))
        .execute();
  }
}
