package com.smartfirehub.proactive.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

/**
 * proactive 잡의 cron 표현식 해석 유틸.
 *
 * <p>스케줄 등록에 쓰는 {@link org.springframework.scheduling.support.CronTrigger} 와 동일한 파서·타임존 규칙으로 "다음 실행
 * 시각"을 계산해, 화면에 표시되는 값이 스케줄러가 실제로 발화하는 시각과 어긋나지 않게 한다 (#348).
 */
@Slf4j
public final class ProactiveCron {

  /** 잡에 timezone 이 지정되지 않았을 때의 기본값. ProactiveJobSchedulerService 의 폴백과 같아야 한다. */
  public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

  private ProactiveCron() {}

  /**
   * cron 표현식을 Spring 이 받아들이는 6필드 형식으로 정규화한다.
   *
   * <p>Spring {@code CronExpression} 은 6필드(초 분 시 일 월 요일)만 수용하는데 DB에는 5필드(Unix 표준)와 6필드가 섞여 있다(#347).
   * 5필드면 앞에 초 자리 {@code "0 "} 을 붙인다. TriggerService.validateScheduleConfig 와 같은 규칙이다.
   */
  public static String normalize(String cronExpression) {
    if (cronExpression == null) return null;
    String trimmed = cronExpression.trim();
    return trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
  }

  /**
   * 다음 실행 시각을 UTC 벽시계로 계산한다.
   *
   * <p>DB의 timestamp 컬럼은 타임존을 담지 않고, 프론트엔드 계약이 "타임존 없는 문자열 = UTC"이므로 잡의 타임존으로 계산한 뒤 UTC로 환산해 반환한다
   * (#349와 동일한 저장 규칙).
   *
   * <p>cron/timezone 이 비었거나 파싱에 실패하면 예외를 던지지 않고 {@code null} 을 반환한다. 다음 실행 시각은 부가 정보이므로, 이걸 계산하다
   * 실패해서 잡 실행이나 스케줄 등록 자체가 깨지면 안 된다.
   *
   * @param cronExpression cron 식 (5필드/6필드 모두 허용)
   * @param timezone IANA zone ID. null/blank 면 {@link #DEFAULT_TIMEZONE}
   * @return 다음 실행 시각(UTC 벽시계) 또는 계산 불가 시 null
   */
  public static LocalDateTime nextExecuteAtUtc(String cronExpression, String timezone) {
    if (cronExpression == null || cronExpression.isBlank()) return null;

    String tz = timezone != null && !timezone.isBlank() ? timezone : DEFAULT_TIMEZONE;
    try {
      ZoneId zone = ZoneId.of(tz);
      CronExpression parsed = CronExpression.parse(normalize(cronExpression));
      ZonedDateTime next = parsed.next(ZonedDateTime.now(zone));
      if (next == null) return null; // 더 이상 발화하지 않는 식 (예: 지나간 특정 일자)
      return next.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    } catch (Exception e) {
      log.warn(
          "다음 실행 시각 계산 실패 — cron='{}' timezone='{}': {}",
          cronExpression,
          tz,
          e.getMessage());
      return null;
    }
  }
}
