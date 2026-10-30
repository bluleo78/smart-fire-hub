package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import com.smartfirehub.dataset.service.DataExceptionTranslator;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
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
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Slf4j
@Transactional
public class PipelineExecutionRepository {

  private final DSLContext dsl;

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

  public Long createExecution(Long pipelineId, Long executedBy) {
    return createExecution(pipelineId, executedBy, "MANUAL", null);
  }

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
      ServerErrorMessage err = DataExceptionTranslator.findServerError(e);
      if (err != null && PIPELINE_FK_CONSTRAINT.equals(err.getConstraint())) {
        throw new PipelineNotFoundException();
      }
      throw e;
    }
  }

  /** pipeline_execution.pipeline_id → pipeline(id) FK 이름(V3 기본 이름). */
  private static final String PIPELINE_FK_CONSTRAINT = "pipeline_execution_pipeline_id_fkey";

  public boolean updateExecutionStatus(
      Long executionId, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    return updateExecutionStatus(executionId, status, startedAt, completedAt, null);
  }

  /**
   * 파이프라인 실행 상태를 갱신한다. {@code errorMessage}가 주어지면 스텝 실행 레코드 생성 전에 발생한 최상위 예외의 메시지를 함께 저장한다(#517) —
   * 기존에는 FAILED 상태만 기록되고 원인이 완전히 유실됐다.
   *
   * @return 실제로 반영됐으면 true — 이미 끝난 실행(중단 정리·취소 등이 먼저 마감)이라 조건부 UPDATE 가 0행이면 false(WD-230)
   */
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

  /**
   * 스텝을 모두 돈 실행의 최종 상태(COMPLETED·FAILED)를 쓴다 — <b>취소 요청이 없을 때만</b>(WD-411). 마지막 스텝이 문장을 실행하는 동안 들어온
   * 취소 요청은 그 뒤 체크포인트가 없어 놓쳤고, 실행이 COMPLETED 로 끝나 연쇄 트리거가 하위를 돌렸다. 확인과 쓰기를 한 조건부 UPDATE 로 묶어, 러너의
   * 마지막 확인과 쓰기 사이에 취소가 들어와도 빠지지 않게 한다.
   *
   * @return 반영됐으면 true. 취소 요청이 있거나 이미 끝난 실행이면 false — 호출부가 취소 마감으로 넘긴다(조건부라 이미 끝난 실행엔 무동작)
   */
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

  /**
   * 살아 있는 실행들의 하트비트를 갱신한다(WD-230). 끝난 실행은 건드리지 않는다 — 중단 정리로 이미 FAILED 가 된 실행의 하트비트가 되살아나 정리 판정을 흐리지
   * 않게 한다.
   *
   * @return 갱신된 행 수
   */
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

  /** 하트비트가 끊긴 실행 한 건 — 정리기가 닫고 파이프라인 단위로 알린다. */
  public record StaleExecution(long executionId, long pipelineId) {}

  /**
   * 하트비트가 {@code threshold} 보다 오래된 미완료 실행을 찾는다(WD-230). 하트비트가 없는 옛 행은 started_at, 그것도 없으면
   * created_at 으로 판단한다 — created_at 은 DB {@code NOW()} 기본값이라 하트비트가 생성 시 채워지는 지금은 거의 쓰이지 않는 마지막
   * 폴백이다. 테넌트 조건을 RLS 에 더해 명시한다(호출자는 그 테넌트 컨텍스트 안에서 불러야 한다).
   */
  public List<StaleExecution> findStaleExecutions(long tenantId, LocalDateTime threshold) {
    return dsl.select(PE_ID, PE_PIPELINE_ID)
        .from(PIPELINE_EXECUTION)
        .where(PE_TENANT_ID.eq(tenantId))
        .and(PE_STATUS.in(ACTIVE_STATUSES))
        .and(coalesce(PE_HEARTBEAT_AT, PE_STARTED_AT, PE_CREATED_AT).lt(threshold))
        .fetch(r -> new StaleExecution(r.get(PE_ID), r.get(PE_PIPELINE_ID)));
  }

  /**
   * 고아 실행을 중단(FAILED)으로 닫고 그 단계들을 함께 닫는다(WD-230) — 한 트랜잭션이다. 실행만 FAILED 가 되고 프로세스가 죽으면 정리기는 종료된 실행을
   * 다시 보지 않으므로 단계가 영영 RUNNING/PENDING 으로 남는다. 그래서 둘을 묶어 모두 커밋되거나 모두 되돌려지게 한다.
   *
   * <p>실행 쓰기는 조건부(미완료만) UPDATE 라 그 사이 정상 마감·취소된 실행은 건드리지 않고, 두 번째 호출은 false 다. 진 호출은 단계도 건드리지 않는다.
   * 이벤트 발행은 호출자가 커밋 뒤(이 메서드가 true 로 돌아온 다음)에 한다.
   *
   * @return 이 호출이 닫았으면 true
   */
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

  /**
   * 러너가 취소를 마감한다(WD-230) — 실행을 CANCELLED 로 쓰고, <b>그 쓰기가 이긴 경우에만</b> 남은 PENDING 단계를 취소 사유로 SKIPPED
   * 처리한다. 한 트랜잭션이라 실행만 CANCELLED 가 되고 단계가 PENDING 으로 남는 상태가 생기지 않는다(종료된 실행은 정리기가 다시 보지 않는다). 진
   * 경우(정리기가 이미 FAILED 로 닫음 등)는 아무것도 바꾸지 않는다.
   *
   * @return 이 호출이 CANCELLED 로 닫았으면 true
   */
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

  /**
   * 러너가 멈춰야 하는가(WD-230) — 취소 요청이 있거나, 실행이 더는 PENDING/RUNNING 이 아니면(중단 정리로 FAILED 등) true.
   *
   * <p>행이 없어도 true 다(WD-437 — fail-closed): 실행 행이 사라진 러너(파이프라인 삭제 CASCADE 등)가 기록 없이 출력 데이터셋을 바꾸지 않게
   * 멈춘다. 러너는 실행 행 커밋 뒤에만 시작하고(afterCommit) 같은 테넌트 컨텍스트로 돌므로 정상 실행에서는 늘 행이 보인다. 사용자 취소와 구분되도록
   * 경고 로그를 남긴다(러너는 첫 true 에서 멈추므로 실행당 한 번). 행이 사라진 실행의 마감 쓰기는 모두 조건부 UPDATE 라 무동작으로 끝난다.
   */
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

  /** 파이프라인의 진행 중 실행 상태(WD-437 — 삭제 거부 판정·삭제 확인 창 안내). */
  public enum ActiveExecutionState {
    /** 진행 중(PENDING/RUNNING) 실행이 없다 — 지울 수 있다. */
    NONE,
    /** 취소 요청이 없는 진행 중 실행이 있다. */
    ACTIVE,
    /** 진행 중 실행이 모두 취소를 요청한 상태다 — 러너가 다음 체크포인트에서 마감할 때까지 기다린다. */
    CANCELLING
  }

  /**
   * 파이프라인에 아직 끝나지 않은(PENDING/RUNNING) 실행이 있는지와 그 상태(WD-437). 취소를 요청했지만 러너가 아직 체크포인트에 닿지 않은 RUNNING
   * 실행도 진행 중으로 센다 — 체크포인트 전까지는 출력을 바꿀 수 있다. 다만 안내 문구가 달라야 하므로(「취소 진행 중」) 따로 구분한다.
   */
  public ActiveExecutionState findActiveExecutionState(long pipelineId) {
    var counts =
        dsl.select(
                count(),
                count().filterWhere(PE_CANCEL_REQUESTED_AT.isNull()))
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

  /** 취소 요청 결과 — 마감 후 상태({@code CANCELLED}=대기 중이라 즉시 마감, {@code RUNNING}=요청만 기록)와 첫 요청 시각. */
  public record CancelRequestResult(String status, LocalDateTime cancelRequestedAt) {}

  /**
   * 실행 취소를 요청한다(WD-153). 두 조건부 UPDATE 로 경쟁을 정리한다 — 러너의 상태 쓰기도 조건부라 어느 순서로 와도 한쪽만 이긴다.
   *
   * <ol>
   *   <li>PENDING(스레드 시작 전) 이면 그 자리에서 CANCELLED 로 마감하고 대기 스텝을 SKIPPED(취소 사유)로 바꾼다. 러너가 나중에 시작해도 시작
   *       쓰기가 조건부라 상태를 되살리지 못하고, 취소 요청을 보고 아무것도 실행하지 않는다.
   *   <li>RUNNING 이면 요청 시각·요청자만 남긴다(COALESCE — 재요청은 첫 요청을 덮지 않는다). 러너가 다음 체크포인트에서 CANCELLED 로 마감한다.
   *   <li>둘 다 아니면(이미 끝남) 빈 값 — 호출부가 409 로 바꾼다.
   * </ol>
   *
   * <p>호출부 트랜잭션(서비스의 {@code @Transactional})에 합류해 1번의 실행·스텝 갱신이 함께 커밋된다.
   */
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

  /**
   * 아직 시작하지 않은(PENDING) 스텝을 취소 사유와 함께 SKIPPED 로 마감한다(WD-153). 대기 중 취소(취소 API)와 실행 중 취소(러너 — 남은 스텝)가
   * 같이 쓴다. 이미 끝났거나 진행 중인 스텝은 건드리지 않는다.
   */
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

  /**
   * 이 테넌트에 지금 RUNNING 인 파이프라인 실행이 있는가(최종 리뷰 I1 — 고아 staging 스윕 게이트).
   *
   * <p>Tibero 파이프라인 스텝은 import 와 같은 {@code STG_} 이름의 스테이징을 쓰고, 실행은 JobRunr 가 아니라 {@code @Async} 라
   * 스윕의 JobRunr 게이트가 보지 못한다. 그래서 스윕이 테넌트마다 이 메서드로 "실행 중인 파이프라인이 없을 때만" 지우게 한다.
   *
   * <p>{@code pipeline_execution} 은 RLS 대상이라 GUC 가 없으면 0행(=거짓 "없음")이 된다 — 클래스의
   * {@code @Transactional} 이 트랜잭션 시작 시 {@code TenantContext} 의 테넌트를 GUC 로 넣어 주므로, 호출자는 그 테넌트 컨텍스트
   * 안에서 불러야 한다. {@code tenant_id} 조건도 명시해 RLS 와 이중으로 좁힌다.
   *
   * @param tenantId 확인할 테넌트(현재 {@code TenantContext} 와 같아야 한다)
   * @return RUNNING 실행이 하나라도 있으면 true
   */
  public boolean hasRunningExecution(long tenantId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PIPELINE_EXECUTION)
            .where(PE_TENANT_ID.eq(tenantId))
            .and(PE_STATUS.eq("RUNNING")));
  }

  /** 실행이 속한 파이프라인 id(소유권 검증용, #188) — 스텝 기록까지 읽는 {@link #findExecutionById} 보다 가볍다. */
  public Optional<Long> findPipelineIdOfExecution(Long executionId) {
    return dsl.select(PE_PIPELINE_ID)
        .from(PIPELINE_EXECUTION)
        .where(PE_ID.eq(executionId))
        .fetchOptional(PE_PIPELINE_ID);
  }

  /**
   * 스텝 실행 행을 PENDING 으로 만든다. 스텝 이름을 함께 떠 둔다(WD-108) — 이후 스텝이 정의에서 지워져 {@code step_id} 가 NULL 이
   * 되어도(V139 ON DELETE SET NULL) 실행 상세가 "어느 스텝의 기록인지"를 보여 줄 수 있어야 하고, 이름이 바뀌어도 그 실행 당시 이름이 남는다.
   */
  public Long createStepExecution(Long executionId, Long stepId, String stepName) {
    return dsl.insertInto(PIPELINE_STEP_EXECUTION)
        .set(PSE_EXECUTION_ID, executionId)
        .set(PSE_STEP_ID, stepId)
        .set(PSE_STEP_NAME, stepName)
        .set(PSE_STATUS, "PENDING")
        .returning(PSE_ID)
        .fetchOne(r -> r.get(PSE_ID));
  }

  /**
   * 같은 스텝의 다른 실행이 이 스텝 실행과 겹쳤는가(코드리뷰 CR1 — 러너 TEMP 쓰기 후 허용 목록 확정의 "마지막으로 쓴 실행이 나인가" 판정).
   *
   * <p>러너 TEMP 는 스텝마다 하나라 그 TEMP 에 쓰는 실행은 이 스텝의 실행들이다. 다른 실행이 지금 RUNNING 이거나, 이 실행이 시작된 뒤에
   * 끝났으면(PENDING 이 아닌데 completed_at ≥ 이 실행의 started_at) 그 실행의 쓰기가 이 실행의 쓰기보다 뒤였을 수 있다 — 그러면 TEMP 의
   * 데이터가 이 실행 것이라고 단정할 수 없다. 러너는 RUNNING 표시를 쓰기 전 좁히기보다 먼저 커밋하고 completed_at 은 확정 뒤에 쓰므로, 창이 겹친 실행은
   * 이 조건에서 빠지지 않는다. 이 실행 행이 없거나 started_at 이 비어 판정할 수 없으면 겹친 것으로 본다(넓히지 않는 쪽이 안전 — 가용성 비용만).
   *
   * @param stepExecId 확정하려는 스텝 실행 id
   * @return 겹친 실행이 있거나 판정할 수 없으면 true
   */
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

  /**
   * 실행 중인 스텝의 진척만 갱신한다 — 상태는 건드리지 않는다(#691).
   *
   * <p>{@link #updateStepExecution} 은 상태를 반드시 쓰게 되어 있어, 진척을 남기려다 호출부가 관리하는 RUNNING/FAILED 전이를 덮어쓸 수
   * 있다. 오래 도는 스텝(AI_CLASSIFY 는 배치 174개 × 45초)이 배치마다 부르는 경로이므로 output_rows 와 log 만 좁게 갱신한다.
   */
  public void updateStepProgress(Long stepExecId, Integer outputRows, String log) {
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_OUTPUT_ROWS, outputRows)
        .set(PSE_LOG, log)
        .where(PSE_ID.eq(stepExecId))
        .execute();
  }

  /**
   * 증분 스텝이 이번 실행에 주입한 책갈피 값을 기록한다(전체 읽기였으면 null).
   *
   * <p>{@link #updateStepExecution} 에 인자로 얹지 않는다 — 그쪽은 상태를 반드시 쓰므로, "실행 시작 직전에 주입값만 남긴다"는 이 호출이
   * RUNNING/FAILED 전이와 얽히면 안 된다.
   */
  public void setInjectedLastRunAt(Long stepExecId, java.time.OffsetDateTime value) {
    dsl.update(PIPELINE_STEP_EXECUTION)
        .set(PSE_INJECTED_LAST_RUN_AT, value)
        .where(PSE_ID.eq(stepExecId))
        .execute();
  }

  /**
   * 스텝 실행 상태를 쓴다. 부모 실행이 PENDING/RUNNING 일 때만 반영한다(WD-230, WD-153 과 같은 취지) — 중단 정리·취소로 이미 끝난 실행의
   * 단계를 늦게 도착한 러너 쓰기가 덮어쓰지 않게 한다. 호출부는 실행을 마감하기 <b>전에</b> 단계를 써야 한다(순서가 반대면 조용히 무반영).
   */
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
      PipelineExecutionRepository.log.debug("스텝 실행 {} 쓰기 무반영 — 부모 실행이 이미 끝났거나 행이 없다", stepExecId);
    }
    return rows == 1;
  }

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

  /**
   * 실행의 실행 주체 사용자 id(수동=요청자, 트리거=트리거 생성자). 상세 응답 DTO 는 사용자 이름만 싣기 때문에, 원문 오류 노출 판정(WD-27)이 id 를 따로
   * 읽는다.
   */
  public Optional<Long> findExecutedById(Long executionId) {
    return dsl.select(PE_EXECUTED_BY)
        .from(PIPELINE_EXECUTION)
        .where(PE_ID.eq(executionId))
        .fetchOptional(PE_EXECUTED_BY);
  }

  /**
   * 실행의 스텝 기록 전체를 실행 당시 기준으로 돌려준다.
   *
   * <p>LEFT JOIN 인 이유(WD-108): 정의에서 지워진 스텝의 기록은 {@code step_id} 가 NULL 이라 INNER JOIN 이면 응답에서 통째로
   * 빠진다 — 사용자가 본 "파이프라인을 고쳐 저장하면 과거 실행 기록이 사라진다" 결함의 조회 쪽 절반이다. 이름은 실행 당시 스냅샷({@code step_name})을
   * 먼저 쓰고, 스냅샷이 없는 행(V139 백필 이전에 만들어져 아직 비어 있는 경우 등)만 현재 스텝 이름으로 채운다. {@code stepId} 가 null 이면 "지금
   * 정의에 없는(삭제된) 스텝의 기록"이라는 뜻이다 — 웹은 이 값으로 삭제된 스텝을 구분한다.
   */
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
}
