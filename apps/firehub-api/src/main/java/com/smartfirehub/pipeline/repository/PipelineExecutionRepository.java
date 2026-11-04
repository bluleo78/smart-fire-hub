package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 파이프라인 실행 이력(pipeline_execution)과 스텝 실행 상태 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqPipelineExecutionRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PipelineExecutionRepository {

  Long createExecution(Long pipelineId, Long executedBy);

  Long createExecution(Long pipelineId, Long executedBy, String triggeredBy, Long triggerId);

  boolean updateExecutionStatus(
      Long executionId, String status, LocalDateTime startedAt, LocalDateTime completedAt);

  /**
   * 파이프라인 실행 상태를 갱신한다. {@code errorMessage}가 주어지면 스텝 실행 레코드 생성 전에 발생한 최상위 예외의 메시지를 함께 저장한다(#517) —
   * 기존에는 FAILED 상태만 기록되고 원인이 완전히 유실됐다.
   *
   * @return 실제로 반영됐으면 true — 이미 끝난 실행(중단 정리·취소 등이 먼저 마감)이라 조건부 UPDATE 가 0행이면 false(WD-230)
   */
  boolean updateExecutionStatus(
      Long executionId,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      String errorMessage);

  /**
   * 스텝을 모두 돈 실행의 최종 상태(COMPLETED·FAILED)를 쓴다 — <b>취소 요청이 없을 때만</b>(WD-411). 마지막 스텝이 문장을 실행하는 동안 들어온
   * 취소 요청은 그 뒤 체크포인트가 없어 놓쳤고, 실행이 COMPLETED 로 끝나 연쇄 트리거가 하위를 돌렸다. 확인과 쓰기를 한 조건부 UPDATE 로 묶어, 러너의
   * 마지막 확인과 쓰기 사이에 취소가 들어와도 빠지지 않게 한다.
   *
   * @return 반영됐으면 true. 취소 요청이 있거나 이미 끝난 실행이면 false — 호출부가 취소 마감으로 넘긴다(조건부라 이미 끝난 실행엔 무동작)
   */
  boolean finishUnlessCancelRequested(Long executionId, String status, LocalDateTime completedAt);

  /**
   * 살아 있는 실행들의 하트비트를 갱신한다(WD-230). 끝난 실행은 건드리지 않는다 — 중단 정리로 이미 FAILED 가 된 실행의 하트비트가 되살아나 정리 판정을 흐리지
   * 않게 한다.
   *
   * @return 갱신된 행 수
   */
  int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now);

  /** 하트비트가 끊긴 실행 한 건 — 정리기가 닫고 파이프라인 단위로 알린다. */
  record StaleExecution(long executionId, long pipelineId) {}

  /**
   * 하트비트가 {@code threshold} 보다 오래된 미완료 실행을 찾는다(WD-230). 하트비트가 없는 옛 행은 started_at, 그것도 없으면
   * created_at 으로 판단한다 — created_at 은 DB {@code NOW()} 기본값이라 하트비트가 생성 시 채워지는 지금은 거의 쓰이지 않는 마지막
   * 폴백이다. 테넌트 조건을 RLS 에 더해 명시한다(호출자는 그 테넌트 컨텍스트 안에서 불러야 한다).
   */
  List<StaleExecution> findStaleExecutions(long tenantId, LocalDateTime threshold);

  /**
   * 고아 실행을 중단(FAILED)으로 닫고 그 단계들을 함께 닫는다(WD-230) — 한 트랜잭션이다. 실행만 FAILED 가 되고 프로세스가 죽으면 정리기는 종료된 실행을
   * 다시 보지 않으므로 단계가 영영 RUNNING/PENDING 으로 남는다. 그래서 둘을 묶어 모두 커밋되거나 모두 되돌려지게 한다.
   *
   * <p>실행 쓰기는 조건부(미완료만) UPDATE 라 그 사이 정상 마감·취소된 실행은 건드리지 않고, 두 번째 호출은 false 다. 진 호출은 단계도 건드리지 않는다.
   * 이벤트 발행은 호출자가 커밋 뒤(이 메서드가 true 로 돌아온 다음)에 한다.
   *
   * @return 이 호출이 닫았으면 true
   */
  boolean failInterruptedAndCloseSteps(long executionId, LocalDateTime now, String message);

  /**
   * 러너가 취소를 마감한다(WD-230) — 실행을 CANCELLED 로 쓰고, <b>그 쓰기가 이긴 경우에만</b> 남은 PENDING 단계를 취소 사유로 SKIPPED
   * 처리한다. 한 트랜잭션이라 실행만 CANCELLED 가 되고 단계가 PENDING 으로 남는 상태가 생기지 않는다(종료된 실행은 정리기가 다시 보지 않는다). 진
   * 경우(정리기가 이미 FAILED 로 닫음 등)는 아무것도 바꾸지 않는다.
   *
   * @return 이 호출이 CANCELLED 로 닫았으면 true
   */
  boolean cancelAndSkipPendingSteps(Long executionId, LocalDateTime completedAt);

  /**
   * 러너가 멈춰야 하는가(WD-230) — 취소 요청이 있거나, 실행이 더는 PENDING/RUNNING 이 아니면(중단 정리로 FAILED 등) true.
   *
   * <p>행이 없어도 true 다(WD-437 — fail-closed): 실행 행이 사라진 러너(파이프라인 삭제 CASCADE 등)가 기록 없이 출력 데이터셋을 바꾸지 않게
   * 멈춘다. 러너는 실행 행 커밋 뒤에만 시작하고(afterCommit) 같은 테넌트 컨텍스트로 돌므로 정상 실행에서는 늘 행이 보인다. 사용자 취소와 구분되도록 경고 로그를
   * 남긴다(러너는 첫 true 에서 멈추므로 실행당 한 번). 행이 사라진 실행의 마감 쓰기는 모두 조건부 UPDATE 라 무동작으로 끝난다.
   */
  boolean isStopRequested(long executionId);

  /** 파이프라인의 진행 중 실행 상태(WD-437 — 삭제 거부 판정·삭제 확인 창 안내). */
  enum ActiveExecutionState {
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
  ActiveExecutionState findActiveExecutionState(long pipelineId);

  /** 취소 요청 결과 — 마감 후 상태({@code CANCELLED}=대기 중이라 즉시 마감, {@code RUNNING}=요청만 기록)와 첫 요청 시각. */
  record CancelRequestResult(String status, LocalDateTime cancelRequestedAt) {}

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
  Optional<CancelRequestResult> requestCancel(Long executionId, Long userId);

  /**
   * 아직 시작하지 않은(PENDING) 스텝을 취소 사유와 함께 SKIPPED 로 마감한다(WD-153). 대기 중 취소(취소 API)와 실행 중 취소(러너 — 남은 스텝)가
   * 같이 쓴다. 이미 끝났거나 진행 중인 스텝은 건드리지 않는다.
   */
  void skipPendingStepsForCancel(Long executionId);

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
  boolean hasRunningExecution(long tenantId);

  /** 실행이 속한 파이프라인 id(소유권 검증용, #188) — 스텝 기록까지 읽는 {@link #findExecutionById} 보다 가볍다. */
  Optional<Long> findPipelineIdOfExecution(Long executionId);

  /**
   * 스텝 실행 행을 PENDING 으로 만든다. 스텝 이름을 함께 떠 둔다(WD-108) — 이후 스텝이 정의에서 지워져 {@code step_id} 가 NULL 이
   * 되어도(V139 ON DELETE SET NULL) 실행 상세가 "어느 스텝의 기록인지"를 보여 줄 수 있어야 하고, 이름이 바뀌어도 그 실행 당시 이름이 남는다.
   */
  Long createStepExecution(Long executionId, Long stepId, String stepName);

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
  boolean hasOverlappingStepExecution(long stepExecId);

  /**
   * 실행 중인 스텝의 진척만 갱신한다 — 상태는 건드리지 않는다(#691).
   *
   * <p>{@link #updateStepExecution} 은 상태를 반드시 쓰게 되어 있어, 진척을 남기려다 호출부가 관리하는 RUNNING/FAILED 전이를 덮어쓸 수
   * 있다. 오래 도는 스텝(AI_CLASSIFY 는 배치 174개 × 45초)이 배치마다 부르는 경로이므로 output_rows 와 log 만 좁게 갱신한다.
   */
  void updateStepProgress(Long stepExecId, Integer outputRows, String log);

  /**
   * 증분 스텝이 이번 실행에 주입한 책갈피 값을 기록한다(전체 읽기였으면 null).
   *
   * <p>{@link #updateStepExecution} 에 인자로 얹지 않는다 — 그쪽은 상태를 반드시 쓰므로, "실행 시작 직전에 주입값만 남긴다"는 이 호출이
   * RUNNING/FAILED 전이와 얽히면 안 된다.
   */
  void setInjectedLastRunAt(Long stepExecId, java.time.OffsetDateTime value);

  /**
   * 스텝 실행 상태를 쓴다. 부모 실행이 PENDING/RUNNING 일 때만 반영한다(WD-230, WD-153 과 같은 취지) — 중단 정리·취소로 이미 끝난 실행의
   * 단계를 늦게 도착한 러너 쓰기가 덮어쓰지 않게 한다. 호출부는 실행을 마감하기 <b>전에</b> 단계를 써야 한다(순서가 반대면 조용히 무반영).
   */
  boolean updateStepExecution(
      Long stepExecId,
      String status,
      Integer outputRows,
      String log,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt);

  List<PipelineExecutionResponse> findExecutionsByPipelineId(Long pipelineId);

  Optional<ExecutionDetailResponse> findExecutionById(Long executionId);

  /**
   * 실행의 실행 주체 사용자 id(수동=요청자, 트리거=트리거 생성자). 상세 응답 DTO 는 사용자 이름만 싣기 때문에, 원문 오류 노출 판정(WD-27)이 id 를 따로
   * 읽는다.
   */
  Optional<Long> findExecutedById(Long executionId);

  /**
   * 실행의 스텝 기록 전체를 실행 당시 기준으로 돌려준다.
   *
   * <p>LEFT JOIN 인 이유(WD-108): 정의에서 지워진 스텝의 기록은 {@code step_id} 가 NULL 이라 INNER JOIN 이면 응답에서 통째로
   * 빠진다 — 사용자가 본 "파이프라인을 고쳐 저장하면 과거 실행 기록이 사라진다" 결함의 조회 쪽 절반이다. 이름은 실행 당시 스냅샷({@code step_name})을
   * 먼저 쓰고, 스냅샷이 없는 행(V139 백필 이전에 만들어져 아직 비어 있는 경우 등)만 현재 스텝 이름으로 채운다. {@code stepId} 가 null 이면 "지금
   * 정의에 없는(삭제된) 스텝의 기록"이라는 뜻이다 — 웹은 이 값으로 삭제된 스텝을 구분한다.
   */
  List<StepExecutionResponse> findStepExecutionsByExecutionId(Long executionId);

  /**
   * 보존 기간이 지난 COMPLETED 실행 행을 지운다(TTL 정리 잡용).
   *
   * <p>정책: created_at &lt; cutoff AND status = 'COMPLETED'. FAILED 는 디버깅·재시도 단서라 남긴다. 자식
   * trigger_event 는 FK ON DELETE CASCADE 로 함께 정리된다. 호출자(PipelineExecutionTtlJob)가
   * TransactionTemplate 으로 연 트랜잭션에 합류한다.
   *
   * @param cutoff 이 시각보다 먼저 만들어진 행이 대상
   * @return 삭제한 행 수
   */
  int deleteCompletedCreatedBefore(LocalDateTime cutoff);
}
