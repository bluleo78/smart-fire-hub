package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExecutionDetailResponse(
    Long id,
    Long pipelineId,
    String pipelineName,
    String status,
    String executedBy,
    List<StepExecutionResponse> stepExecutions,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt,
    /**
     * 스텝 실행 레코드가 하나도 생성되기 전에 발생한 최상위 예외 메시지(#517). 스텝 레벨 오류가 아닌 파이프라인 실행 자체의 실패 원인(토폴로지 정렬 실패, DB
     * 오류 등)을 담으며, 정상 완료되었거나 스텝 레벨에서 실패한 경우 null이다.
     */
    String errorMessage,
    /**
     * 실행 단위 errorMessage 가 원문 대신 가림 문구로 바뀌었는가(WD-27) — 스텝 단위 {@link
     * StepExecutionResponse#errorMasked} 와 같은 용도.
     */
    boolean errorMasked,
    /**
     * 첫 취소 요청 시각(WD-153). 상태가 CANCELLED 가 아니어도 채워질 수 있다 — RUNNING 이면 "취소 요청됨(다음 체크포인트에서 멈춤)", 마지막 체크포인트
     * 뒤에 도착한 요청이면 COMPLETED/FAILED 인 채로 남는다(효과 없음).
     */
    LocalDateTime cancelRequestedAt,
    /** 취소 요청자 이름(WD-153). 요청자가 삭제됐으면 null. */
    String cancelRequestedBy) {}
