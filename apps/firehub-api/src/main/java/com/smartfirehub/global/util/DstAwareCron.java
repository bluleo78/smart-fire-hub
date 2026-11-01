package com.smartfirehub.global.util;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.scheduling.support.CronExpression;

/**
 * 서머타임(DST) 전환일을 Linux cron(Vixie) 규칙으로 다루는 cron 계산기 (WD-391).
 *
 * <p>Spring {@code CronTrigger} 는 cron 을 시간대가 붙은 시각({@code ZonedDateTime})으로 바로 계산해, 서머타임 해제일엔 「매일
 * 1:30」 같은 작업이 겹친 1시대에 두 번 돌고, 시작일엔 사라진 2:30 을 그날 건너뛴다. 편집 창 미리보기(웹 cron-parser)는 또 다른 규칙이라 「다음
 * 5회」와 실제 실행이 어긋났다. 그래서 규칙을 이 클래스 한 곳에 모으고 파이프라인 트리거·스마트 작업 스케줄러({@link DstAwareCronTrigger})와 미리보기
 * API 가 모두 이것만 쓰게 한다.
 *
 * <p><b>규칙</b> (Vixie cron 과 같다)
 *
 * <ul>
 *   <li><b>「시」 칸이 {@code *} 로 시작</b>(매시·n분마다, {@code *}/{@code *}/2 등 — 시간을 고정하지 않은 식): 실제 흐른 시간
 *       기준으로 돈다. 해제일엔 되풀이되는 1시대에도 돌고, 시작일엔 존재하지 않는 2시대는 없다.
 *   <li><b>「시」 칸이 고정</b>(매일 1:30 등): 벽시계 기준으로 계산한다. 해제일에 두 번 나타나는 벽시계 시각은 <b>처음 한 번</b>만 돌고, 시작일에
 *       존재하지 않는 시각은 같은 날 <b>한 시간 뒤</b>(간격 길이만큼)로 밀린다(2:30 → 3:30). 밀린 시각이 이미 돈 시각이나 다른 예정 시각과 같으면 한
 *       번으로 센다.
 * </ul>
 *
 * <p>Vixie 는 「분」 칸이 {@code *} 로 시작해도 경과 시간 기준으로 보지만, 이 프로젝트는 승인된 규칙대로 「시」 칸만 본다.
 */
public final class DstAwareCron {

  /**
   * 고정 시각 경로에서 후보를 넘겨 보는 최대 횟수 — 이미 지난 후보(겹친 시각의 두 번째, 간격 되돌아보기 구간)를 건너뛰는 용도라 몇 번이면 충분하다. 시 칸이 고정이고
   * 분·초가 촘촘한 식(예: 「* 9 * * *」)이 되돌아보기 구간에서 여러 번 걸릴 수 있어 넉넉히 잡는다.
   */
  private static final int MAX_SKIPS = 10_000;

  /** Spring {@code CronExpression} 이 받는 매크로를 6필드로 펼친 값 — 「시」 칸 판별용. */
  private static final Map<String, String> MACROS =
      Map.of(
          "@yearly", "0 0 0 1 1 *",
          "@annually", "0 0 0 1 1 *",
          "@monthly", "0 0 0 1 * *",
          "@weekly", "0 0 0 * * 0",
          "@daily", "0 0 0 * * *",
          "@midnight", "0 0 0 * * *",
          "@hourly", "0 0 * * * *");

  private final CronExpression expression;
  private final ZoneId zone;
  private final boolean wallClockHour;
  private final String normalized;

  private DstAwareCron(CronExpression expression, ZoneId zone, boolean wallClockHour, String n) {
    this.expression = expression;
    this.zone = zone;
    this.wallClockHour = wallClockHour;
    this.normalized = n;
  }

  /**
   * cron 식을 Spring 이 받아들이는 6필드 형식으로 정규화한다.
   *
   * <p>Spring {@code CronExpression} 은 6필드(초 분 시 일 월 요일)만 수용하는데 DB에는 5필드(Unix 표준)와 6필드가 섞여
   * 있다(#347). 5필드면 앞에 초 자리 {@code "0 "} 을 붙인다. 매크로(@daily 등)는 그대로 둔다.
   */
  public static String normalize(String cronExpression) {
    if (cronExpression == null) return null;
    String trimmed = cronExpression.trim();
    return trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
  }

  /**
   * cron 식과 시간대로 계산기를 만든다.
   *
   * @param cronExpression 5필드/6필드/매크로 cron 식
   * @param zone 시간대
   * @throws IllegalArgumentException 식이 비었거나 파싱할 수 없을 때(Spring 원문 메시지)
   */
  public static DstAwareCron of(String cronExpression, ZoneId zone) {
    if (cronExpression == null || cronExpression.isBlank()) {
      throw new IllegalArgumentException("cron 표현식이 비어 있습니다");
    }
    if (zone == null) {
      throw new IllegalArgumentException("시간대가 비어 있습니다");
    }
    String normalized = normalize(cronExpression);
    CronExpression parsed = CronExpression.parse(normalized);
    return new DstAwareCron(parsed, zone, !isHourWildcard(normalized), normalized);
  }

  /**
   * 문자열 시간대로 계산기를 만든다.
   *
   * @throws IllegalArgumentException cron 이 틀렸거나 시간대가 IANA ID 가 아닐 때
   */
  public static DstAwareCron of(String cronExpression, String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null ? "" : timezone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("유효하지 않은 시간대입니다: " + timezone, e);
    }
    return of(cronExpression, zone);
  }

  /**
   * 「시」 칸이 {@code *} 로 시작하면(매시·n분마다) 경과 시간 기준 식이다. Vixie cron 의 HR_STAR 판별과 같이 {@code *}/2 처럼 별표로
   * 시작하는 것도 포함한다. 매크로는 6필드로 펼친 뒤 본다.
   */
  static boolean isHourWildcard(String normalizedCron) {
    String expanded = MACROS.getOrDefault(normalizedCron.toLowerCase(Locale.ROOT), normalizedCron);
    String[] fields = expanded.trim().split("\\s+");
    return fields.length == 6 && fields[2].startsWith("*");
  }

  /** 계산에 쓰는 시간대. */
  public ZoneId zone() {
    return zone;
  }

  /** 정규화된(6필드) cron 식. */
  public String expression() {
    return normalized;
  }

  /** 「시」 칸이 고정이라 벽시계 기준(겹침 1회·간격 +1시간)으로 계산하는지 여부. */
  public boolean isWallClockHour() {
    return wallClockHour;
  }

  /**
   * {@code base} 보다 <b>엄격히 뒤</b>의 다음 발화 시각을 돌려준다. 더 이상 발화하지 않는 식이면 {@code null}.
   *
   * <p>"엄격히 뒤" 가 해제일 1회 실행과 시작일 중복 제거(밀린 2:00 → 3:00 이 원래 3:00 과 같으면 한 번)의 핵심이다 — 이미 돈 시각과 같거나 앞서는
   * 후보는 건너뛴다.
   */
  public Instant next(Instant base) {
    if (!wallClockHour) {
      // 경과 시간 기준 — Spring 의 시간대 계산을 그대로 쓴다(해제일 되풀이 시간대도 돌고, 시작일 사라진 시간대는 없다).
      ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(base, zone));
      return next == null ? null : next.toInstant();
    }
    LocalDateTime cursor = wallClockSearchStart(base);
    for (int i = 0; i < MAX_SKIPS; i++) {
      LocalDateTime candidate = expression.next(cursor);
      if (candidate == null) return null;
      // offset=null → 겹침이면 앞(서머타임) 오프셋, 간격이면 간격 길이만큼 뒤로 민 시각
      Instant at = ZonedDateTime.ofLocal(candidate, zone, null).toInstant();
      if (at.isAfter(base)) return at;
      cursor = candidate;
    }
    return null;
  }

  /**
   * 벽시계 탐색 시작점. 보통은 {@code base} 의 벽시계 시각이지만, {@code base} 가 서머타임 시작 직후(간격 길이 이내)면 간격 시작 직전으로 되돌린다.
   *
   * <p>예: 「15,45 2시」 식은 시작일에 2:15→3:15, 2:45→3:45 로 밀린다. 3:15(EDT)에 돈 뒤 벽시계 3:15 부터 찾으면 2:45 를 이미
   * 지난 것으로 보고 다음 날로 넘어가 3:45 실행을 잃는다. 간격 안의 벽시계 시각을 다시 후보로 보게 하고, 이미 지난 것은 {@link #next} 의 "엄격히 뒤"
   * 조건이 거른다.
   */
  private LocalDateTime wallClockSearchStart(Instant base) {
    LocalDateTime local = LocalDateTime.ofInstant(base, zone);
    ZoneOffsetTransition prev = zone.getRules().previousTransition(base.plusSeconds(1));
    if (prev != null
        && prev.isGap()
        && !base.isAfter(prev.getInstant().plus(prev.getDuration()))
        && !base.isBefore(prev.getInstant())) {
      // CronExpression.next 는 "다음"(엄격히 뒤)을 찾으므로 간격 시작 시각 자체가 후보가 되도록 1나노 앞에서 시작한다.
      return prev.getDateTimeBefore().minusNanos(1);
    }
    return local;
  }

  /**
   * {@code from} 이후 발화 시각을 최대 {@code count} 개 돌려준다(미리보기용). 발화가 끝나면 그보다 적을 수 있다.
   *
   * <p>스케줄러와 같은 {@link #next} 를 이어 불러, 앞 발화 시각을 다음 계산의 기준으로 삼는다 — 실제 실행 순서와 같다.
   */
  public List<Instant> nextTimes(Instant from, int count) {
    List<Instant> result = new ArrayList<>(count);
    Instant cursor = from;
    for (int i = 0; i < count; i++) {
      Instant next = next(cursor);
      if (next == null) break;
      result.add(next);
      cursor = next;
    }
    return result;
  }

  /** 이 계산기를 쓰는 Spring 스케줄러용 {@link org.springframework.scheduling.Trigger}. */
  public DstAwareCronTrigger toTrigger() {
    return new DstAwareCronTrigger(this);
  }

  @Override
  public String toString() {
    return normalized + " [" + zone + "]";
  }
}
