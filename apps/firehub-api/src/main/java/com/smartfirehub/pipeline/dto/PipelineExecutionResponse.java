package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record PipelineExecutionResponse(
    Long id,
    Long pipelineId,
    String status,
    String executedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt,
    String triggeredBy,
    String triggerName,
    /** 첫 취소 요청 시각(WD-153) — 목록에서 실행 중인 실행의 「취소 요청됨」 표시에 쓴다. 요청이 없으면 null. */
    LocalDateTime cancelRequestedAt) {
  /** 취소 요청 필드 없는 생성자 — 막 만든 실행 응답 등 취소 요청이 있을 수 없는 경우. */
  public PipelineExecutionResponse(
      Long id,
      Long pipelineId,
      String status,
      String executedBy,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime createdAt,
      String triggeredBy,
      String triggerName) {
    this(
        id,
        pipelineId,
        status,
        executedBy,
        startedAt,
        completedAt,
        createdAt,
        triggeredBy,
        triggerName,
        null);
  }

  /** Backward-compatible constructor without trigger fields. */
  public PipelineExecutionResponse(
      Long id,
      Long pipelineId,
      String status,
      String executedBy,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime createdAt) {
    this(id, pipelineId, status, executedBy, startedAt, completedAt, createdAt, "MANUAL", null);
  }
}
