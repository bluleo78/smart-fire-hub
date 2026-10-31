package com.smartfirehub.proactive.dto;

import com.smartfirehub.global.validation.MaxJsonSize;
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
 * <p>{@code triggerType}은 nullable이며 미지정 시 서비스 레이어에서 기본값 {@code SCHEDULE}로 처리한다. 이전에는 이 필드 자체가 DTO에
 * 없어 프론트엔드가 값을 보내도 조용히 버려지고 DB 컬럼 기본값(SCHEDULE)만 저장되는 회귀가 있었다 (#655) — ANOMALY/BOTH로 생성해도 이상 탐지
 * 폴러({@code MetricPollerService})가 절대 조회하지 못해 작업이 영원히 발화하지 않는 문제로 이어졌다.
 *
 * <p>{@code prompt}·{@code config} 에는 크기 상한을 둔다(WD-425) — 상한이 없어 500만 자 프롬프트와 500만 자 config 도 저장됐고,
 * 작업 목록 응답이 둘을 통째로 실어 내 작업 목록이 10MB 가 됐다.
 */
public record CreateProactiveJobRequest(
    @NotBlank
        @Size(
            max = CreateProactiveJobRequest.NAME_MAX_LENGTH,
            message = CreateProactiveJobRequest.NAME_TOO_LONG_MESSAGE)
        String name,
    @NotBlank
        @Size(
            max = CreateProactiveJobRequest.PROMPT_MAX_LENGTH,
            message = CreateProactiveJobRequest.PROMPT_TOO_LONG_MESSAGE)
        String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    Boolean enabled,
    @Pattern(
            regexp = "SCHEDULE|ANOMALY|BOTH",
            message = "triggerType은 SCHEDULE, ANOMALY, BOTH 중 하나여야 합니다")
        String triggerType,
    @NotNull
        @MaxJsonSize(
            maxBytes = CreateProactiveJobRequest.CONFIG_MAX_BYTES,
            message = CreateProactiveJobRequest.CONFIG_TOO_LARGE_MESSAGE)
        Map<String, Object> config) {

  /** 작업 이름 길이 상한 — 수정 요청도 같은 값을 쓴다. */
  public static final int NAME_MAX_LENGTH = 200;

  /** 작업 이름 상한을 넘었을 때의 안내. */
  public static final String NAME_TOO_LONG_MESSAGE = "작업 이름은 200자 이내여야 합니다";

  /** 분석 프롬프트 길이 상한 — 10,000자(WD-425). 화면·AI 도구가 만드는 프롬프트는 몇 문장이다. */
  public static final int PROMPT_MAX_LENGTH = 10_000;

  /** 분석 프롬프트 상한을 넘었을 때의 안내 — 화면 입력 상한(maxLength)과 같은 값이다. */
  public static final String PROMPT_TOO_LONG_MESSAGE = "분석 프롬프트는 10,000자 이내여야 합니다";

  /** 작업 설정(config) 직렬화 크기 상한 — 64KB(UTF-8 바이트, WD-425). 전달 채널·수신자·이상 탐지 지표 설정이 들어가며 보통 1KB 안팎이다. */
  public static final long CONFIG_MAX_BYTES = 64 * 1024;

  /** 작업 설정 상한을 넘었을 때의 안내. */
  public static final String CONFIG_TOO_LARGE_MESSAGE = "작업 설정(config)이 너무 큽니다(최대 64KB).";
}
