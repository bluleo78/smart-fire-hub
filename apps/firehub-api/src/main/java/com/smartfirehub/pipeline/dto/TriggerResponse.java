package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * {@code nextFireTime}: SCHEDULE 트리거의 다음 실행 예정 시각 (UTC ISO-8601, 예:
 * "2026-09-13T00:00:00Z"). {@code pipeline_trigger.trigger_state}(jsonb)에 저장된 값을 리포지토리가
 * 꺼내 이 필드로 매핑한다 — 프론트엔드가 {@code trigger.nextFireTime}으로 바로 접근하기 위함(#676).
 * SCHEDULE이 아니거나 아직 등록 전이면 null.
 */
public record TriggerResponse(
    Long id,
    Long pipelineId,
    String triggerType,
    String name,
    String description,
    boolean isEnabled,
    Map<String, Object> config,
    Map<String, Object> triggerState,
    String nextFireTime,
    Long createdBy,
    LocalDateTime createdAt) {}
