package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 */
public record CreateProactiveJobRequest(
    @NotBlank @Size(max = 200, message = "작업 이름은 200자 이내여야 합니다") String name,
    @NotBlank String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    @NotNull Map<String, Object> config) {}
