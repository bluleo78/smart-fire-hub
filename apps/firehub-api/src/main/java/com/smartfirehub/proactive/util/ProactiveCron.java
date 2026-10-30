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
   * <p>Spring {@code CronExpression} 은 6필드(초 분 시 일 월 요일)만 수용하는데 DB에는 5필드(Unix 표준)와 6필드가 섞여
   * 있다(#347). 5필드면 앞에 초 자리 {@code "0 "} 을 붙인다. TriggerService.validateScheduleConfig 와 같은 규칙이다.
   */
  public static String normalize(String cronExpression) {
    if (cronExpression == null) return null;
    String trimmed = cronExpression.trim();
    return trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
  }

  /** 1분보다 잦은 실행 주기 거부 문구(WD-366) — 웹 cron-frequency.ts 의 CRON_TOO_FREQUENT_MESSAGE 와 같다. */
  public static final String TOO_FREQUENT_MESSAGE =
      "실행 주기는 1분보다 짧게 할 수 없습니다 — 「초」 칸에는 숫자 하나만 쓸 수 있습니다 (예: 0 * * * * * → 매분)";

  /**
   * 1분에 한 번보다 자주 실행되는 cron 이면 거부 문구를, 아니면 {@code null} 을 돌려준다(WD-366).
   *
   * <p>파이프라인 트리거·스마트 작업은 데이터 가공·리포트라 초 단위로 돌 이유가 없는데, 「* * * * * *」(매초)처럼 저장되면 실행이 끝나자마자
   * 다음이 이어져 실행 이력과 스케줄러 자원을 끝없이 쓴다. 분 이상 칸은 아무리 잦아도 1분 간격이 최소이므로 초 칸 하나만 보면 된다 — 초 칸이 숫자
   * 하나(5필드는 정규화로 "0")면 1분에 최대 한 번이다. 형식이 틀린 식은 여기서 판정하지 않는다(형식 검사가 먼저 거른다).
   */
  public static String describeTooFrequent(String cronExpression) {
    if (cronExpression == null || cronExpression.isBlank()) return null;
    String[] fields = normalize(cronExpression).split("\\s+");
    if (fields.length != 6) return null;
    return fields[0].matches("\\d+") ? null : TOO_FREQUENT_MESSAGE;
  }

  /** 5필드(화면 안내 기준) 칸 이름과 허용 범위. 6필드면 앞에 초 칸이 하나 더 붙는다. */
  private static final String[] FIELD_NAMES = {"분", "시", "일", "월", "요일"};

  private static final String[] FIELD_RANGES = {
    "0~59", "0~23", "1~31", "1~12 또는 JAN~DEC", "0~7 또는 MON~SUN"
  };

  /**
   * 잘못된 cron 식이 왜 틀렸는지 화면 안내(「분 시 일 월 요일」 5칸)와 같은 기준의 한국어로 설명한다(WD-138).
   *
   * <p>Spring {@code CronExpression} 의 예외 문구는 영어이고, 5필드 입력도 초 칸을 붙인 6필드 기준으로 말해 사용자가 넣지 않은 값("0 61
   * * * * *")과 "6칸이 필요하다"는 안내와 다른 칸 수가 보였다. 그래서 칸 수를 먼저 보고, 칸 수가 맞으면 칸을 하나씩 따로 파싱해 처음 틀린 칸의 이름·값·허용
   * 범위를 알려 준다(다른 칸은 {@code *} 로 채워 그 칸만 검사).
   *
   * @return 설명 문구. 식이 유효하면 {@code null}
   */
  public static String describeInvalid(String cronExpression) {
    String trimmed = cronExpression == null ? "" : cronExpression.trim();
    String[] fields = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    if (fields.length != 5 && fields.length != 6) {
      return "「분 시 일 월 요일」 5칸을 공백으로 나눠 입력해야 하는데 "
          + fields.length
          + "칸을 입력했습니다 (예: 0 9 * * * → 매일 09:00)";
    }
    try {
      CronExpression.parse(normalize(trimmed));
      return null;
    } catch (IllegalArgumentException wholeInvalid) {
      // 아래에서 칸별로 원인을 찾는다
    }
    int offset = fields.length - 5; // 6필드면 맨 앞이 초 칸
    for (int i = 0; i < fields.length; i++) {
      String[] probe = {"*", "*", "*", "*", "*", "*"};
      probe[i + (6 - fields.length)] = fields[i];
      try {
        CronExpression.parse(String.join(" ", probe));
      } catch (IllegalArgumentException fieldInvalid) {
        boolean seconds = i < offset;
        String name = seconds ? "초" : FIELD_NAMES[i - offset];
        String range = seconds ? "0~59" : FIELD_RANGES[i - offset];
        return "「" + name + "」 칸의 값 \"" + fields[i] + "\" 이(가) 올바르지 않습니다 (허용 범위 " + range + ")";
      }
    }
    // 칸마다는 맞지만 조합이 성립하지 않는 경우(드묾)
    return "칸의 조합이 올바른 cron 형식이 아닙니다 (예: 0 9 * * * → 매일 09:00)";
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
      log.warn("다음 실행 시각 계산 실패 — cron='{}' timezone='{}': {}", cronExpression, tz, e.getMessage());
      return null;
    }
  }
}
