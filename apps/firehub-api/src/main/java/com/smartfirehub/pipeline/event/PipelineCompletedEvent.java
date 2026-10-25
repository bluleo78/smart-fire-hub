package com.smartfirehub.pipeline.event;

/**
 * 파이프라인 실행 완료 이벤트.
 *
 * <p>{@code interrupted} 는 api 재시작 등으로 중단되어 정리기(WD-230)가 마감한 실행임을 뜻한다. 이 경우 체인 트리거는 발화하지 않고 알림만
 * 「중단」 문구로 나간다. 러너가 정상 경로에서 내는 이벤트는 항상 false 다.
 */
public record PipelineCompletedEvent(
    Long pipelineId, Long executionId, String status, Long createdBy, boolean interrupted) {

  /** 기존 호출부 호환 — 중단 아님(interrupted=false). */
  public PipelineCompletedEvent(Long pipelineId, Long executionId, String status, Long createdBy) {
    this(pipelineId, executionId, status, createdBy, false);
  }
}
