package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * Proactive Job 수정 요청 DTO.
 *
 * <p>{@code triggerType}은 nullable이며 null이면 기존 값을 유지한다. 이 필드가 없어 트리거 유형 변경이 DB에 반영되지 않던 회귀를
 * 수정했다 (#655).
 */
public record UpdateProactiveJobRequest(
    String name,
    String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    @Pattern(regexp = "SCHEDULE|ANOMALY|BOTH", message = "triggerType은 SCHEDULE, ANOMALY, BOTH 중 하나여야 합니다")
        String triggerType,
    Map<String, Object> config) {}
