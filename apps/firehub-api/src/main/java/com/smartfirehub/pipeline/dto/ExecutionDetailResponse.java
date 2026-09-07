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
     * 스텝 실행 레코드가 하나도 생성되기 전에 발생한 최상위 예외 메시지(#517). 스텝 레벨 오류가 아닌 파이프라인 실행 자체의 실패
     * 원인(토폴로지 정렬 실패, DB 오류 등)을 담으며, 정상 완료되었거나 스텝 레벨에서 실패한 경우 null이다.
     */
    String errorMessage) {}
