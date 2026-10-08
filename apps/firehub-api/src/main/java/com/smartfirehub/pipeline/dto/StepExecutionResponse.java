package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record StepExecutionResponse(
    Long id,
    Long stepId,
    String stepName,
    String status,
    Integer outputRows,
    String log,
    String errorMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    /**
     * errorMessage 가 원문 대신 가림 문구로 바뀌었는가(WD-27). 웹이 문구 문자열 비교 없이 "아래 오류를 참고" 안내를 숨기는 데 쓴다 — 저장본 조회
     * 시에는 항상 false 이고 조회자 판정(PipelineService)만 true 로 바꾼다.
     */
    boolean errorMasked) {}
