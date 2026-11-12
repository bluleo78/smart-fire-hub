package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.LocalDateTime;

/** PipelineExecutionMapper 의 평평한 행·INSERT 홀더 모음(WD-708). */
public final class PipelineExecutionRows {

  private PipelineExecutionRows() {}

  /** 실행 한 줄의 상태·취소 요청 시각 — 러너의 중지 판정용. */
  public record StopStateRow(String status, LocalDateTime cancelRequestedAt) {}

  /** 파이프라인의 진행 중(PENDING/RUNNING) 실행 수와 그중 취소 요청이 없는 수. */
  public record ActiveCountsRow(long active, long notCancelling) {}

  /** 하트비트가 끊긴 실행 한 줄. */
  public record StaleRow(long executionId, long pipelineId) {}

  /** 겹침 판정용 — 이 스텝 실행의 스텝 id·시작 시각(둘 다 null 일 수 있다). */
  public record StepStartRow(Long stepId, LocalDateTime startedAt) {}

  /** 실행 이력 목록 한 줄 — 사용자 이름은 사용자가 없으면 null(구현이 실행자 id 문자열로 대신한다). */
  public record ExecutionListRow(
      Long id,
      Long pipelineId,
      String status,
      Long executedBy,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime createdAt,
      String userName,
      String triggeredBy,
      String triggerName,
      LocalDateTime cancelRequestedAt) {}

  /** 실행 상세 한 줄(스텝 기록은 따로 읽는다). */
  public record ExecutionDetailRow(
      Long id,
      Long pipelineId,
      String status,
      Long executedBy,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime createdAt,
      String errorMessage,
      String pipelineName,
      String userName,
      LocalDateTime cancelRequestedAt,
      String cancellerName) {}

  /** 스텝 실행 기록 한 줄 — 이름은 실행 당시 스냅샷, 없으면 현재 스텝 이름. log 는 PG text / Tibero CLOB(문자열로 읽는다). */
  public record StepExecutionRow(
      Long id,
      Long stepId,
      String stepName,
      String status,
      Integer outputRows,
      String log,
      String errorMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt) {}

  /**
   * 실행 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(id)} 대신 JDBC 생성 키가
   * {@code id} 에 써 넣는다.
   */
  public static class ExecutionInsert extends GeneratedId {
    private final Long pipelineId;
    private final Long executedBy;
    private final LocalDateTime heartbeatAt;
    private final String triggeredBy;
    private final Long triggerId;

    public ExecutionInsert(
        Long pipelineId,
        Long executedBy,
        LocalDateTime heartbeatAt,
        String triggeredBy,
        Long triggerId) {
      this.pipelineId = pipelineId;
      this.executedBy = executedBy;
      this.heartbeatAt = heartbeatAt;
      this.triggeredBy = triggeredBy;
      this.triggerId = triggerId;
    }

    public Long getPipelineId() {
      return pipelineId;
    }

    public Long getExecutedBy() {
      return executedBy;
    }

    public LocalDateTime getHeartbeatAt() {
      return heartbeatAt;
    }

    public String getTriggeredBy() {
      return triggeredBy;
    }

    public Long getTriggerId() {
      return triggerId;
    }
  }

  /** 스텝 실행 INSERT 파라미터 겸 생성 키 홀더. */
  public static class StepExecutionInsert extends GeneratedId {
    private final Long executionId;
    private final Long stepId;
    private final String stepName;

    public StepExecutionInsert(Long executionId, Long stepId, String stepName) {
      this.executionId = executionId;
      this.stepId = stepId;
      this.stepName = stepName;
    }

    public Long getExecutionId() {
      return executionId;
    }

    public Long getStepId() {
      return stepId;
    }

    public String getStepName() {
      return stepName;
    }
  }
}
