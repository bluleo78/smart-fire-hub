package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

// 파이프라인 목록/상세 조회 응답 DTO.
// triggerCount: 활성화된(is_enabled=true) 트리거 개수. 목록 페이지의 "트리거" 컬럼 뱃지 표시에 사용된다.
public record PipelineResponse(
    Long id,
    String name,
    String description,
    boolean isActive,
    String createdBy,
    int stepCount,
    int triggerCount,
    LocalDateTime createdAt) {}
