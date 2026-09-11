package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Proactive Job 생성 요청 DTO.
 *
 * <p>{@code enabled}는 nullable이며 미지정 시 서비스 레이어에서 기본값 {@code true}로 처리한다. {@code false}로 명시되면 생성 직후
 * 스케줄러에 등록하지 않아 비활성 상태로 저장된다 (#220).
 *
 * <p>{@code cronExpression} 과 {@code timezone} 값의 유효성은 서비스 레이어({@code
 * ProactiveJobService.validateCronAndTimezone})에서 Spring {@code CronExpression.parse} 와 {@code
 * ZoneId.of} 로 사전 검증한다. 잘못된 값은 400으로 반환되어 스케줄러 silent fail 을 막는다 (#221).
 *
 * <p>{@code triggerType}은 nullable이며 미지정 시 서비스 레이어에서 기본값 {@code SCHEDULE}로 처리한다. 이전에는 이 필드 자체가
 * DTO에 없어 프론트엔드가 값을 보내도 조용히 버려지고 DB 컬럼 기본값(SCHEDULE)만 저장되는 회귀가 있었다 (#655) — ANOMALY/BOTH로 생성해도
 * 이상 탐지 폴러({@code MetricPollerService})가 절대 조회하지 못해 작업이 영원히 발화하지 않는 문제로 이어졌다.
 */
public record CreateProactiveJobRequest(
    @NotBlank @Size(max = 200, message = "작업 이름은 200자 이내여야 합니다") String name,
    @NotBlank String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    @Pattern(regexp = "SCHEDULE|ANOMALY|BOTH", message = "triggerType은 SCHEDULE, ANOMALY, BOTH 중 하나여야 합니다")
        String triggerType,
    @NotNull Map<String, Object> config) {}
