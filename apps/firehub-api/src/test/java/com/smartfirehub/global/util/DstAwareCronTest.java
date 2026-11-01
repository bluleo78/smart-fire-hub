package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * {@link DstAwareCron} 단위 테스트 (WD-391).
 *
 * <p>서머타임 전환일(뉴욕: 2026-11-01 해제·2027-03-14 시작, 베를린: 2026-10-25 해제·2027-03-28 시작)에 Linux cron(Vixie)
 * 규칙대로 발화 시각이 나오는지 표로 고정한다. 기준 시각은 모두 고정 값이라 테스트 날짜와 무관하다.
 */
class DstAwareCronTest {

  private static Instant at(String offsetDateTime) {
    return OffsetDateTime.parse(offsetDateTime).toInstant();
  }

  private static List<Instant> times(String... offsetDateTimes) {
    return Arrays.stream(offsetDateTimes).map(DstAwareCronTest::at).toList();
  }

  /** 표: 설명, cron, 시간대, 기준 시각(이 시각보다 뒤부터), 기대 발화 시각들(현지 오프셋 표기). */
  static Stream<Arguments> dstCases() {
    return Stream.of(
        Arguments.of(
            "뉴욕 해제일 — 고정 시각 1:30 은 겹친 1시대에 한 번(첫 번째)만",
            "0 30 1 * * *",
            "America/New_York",
            "2026-11-01T00:00-04:00",
            new String[] {"2026-11-01T01:30-04:00", "2026-11-02T01:30-05:00"}),
        Arguments.of(
            "뉴욕 시작일 — 없는 2:30 은 같은 날 3:30 으로 밀린다",
            "0 30 2 * * *",
            "America/New_York",
            "2027-03-14T00:00-05:00",
            new String[] {"2027-03-14T03:30-04:00", "2027-03-15T02:30-04:00"}),
        Arguments.of(
            "뉴욕 시작일 — 1-3시 매 30분: 2:00·2:30 은 3:00·3:30 으로 밀려 원래 3:00·3:30 과 한 번씩만",
            "0 0,30 1-3 * * *",
            "America/New_York",
            "2027-03-14T00:00-05:00",
            new String[] {
              "2027-03-14T01:00-05:00",
              "2027-03-14T01:30-05:00",
              "2027-03-14T03:00-04:00",
              "2027-03-14T03:30-04:00",
              "2027-03-15T01:00-04:00"
            }),
        Arguments.of(
            "뉴욕 시작일 — 2:15·2:45 둘 다 3:15·3:45 로 밀린다(3:15 실행 뒤 3:45 를 잃지 않는다)",
            "0 15,45 2 * * *",
            "America/New_York",
            "2027-03-14T00:00-05:00",
            new String[] {
              "2027-03-14T03:15-04:00", "2027-03-14T03:45-04:00", "2027-03-15T02:15-04:00"
            }),
        Arguments.of(
            "뉴욕 해제일 — 30분마다(시 칸 *)는 경과 시간 기준이라 두 번의 1시대 모두 돈다",
            "0 */30 * * * *",
            "America/New_York",
            "2026-11-01T00:45-04:00",
            new String[] {
              "2026-11-01T01:00-04:00",
              "2026-11-01T01:30-04:00",
              "2026-11-01T01:00-05:00",
              "2026-11-01T01:30-05:00",
              "2026-11-01T02:00-05:00"
            }),
        Arguments.of(
            "뉴욕 시작일 — 30분마다는 없는 2시대 없이 1:30 다음이 3:00",
            "0 */30 * * * *",
            "America/New_York",
            "2027-03-14T01:15-05:00",
            new String[] {
              "2027-03-14T01:30-05:00", "2027-03-14T03:00-04:00", "2027-03-14T03:30-04:00"
            }),
        Arguments.of(
            "뉴욕 해제일 — @hourly 도 시 칸이 * 라 1시대 두 번 돈다",
            "@hourly",
            "America/New_York",
            "2026-11-01T00:30-04:00",
            new String[] {
              "2026-11-01T01:00-04:00", "2026-11-01T01:00-05:00", "2026-11-01T02:00-05:00"
            }),
        Arguments.of(
            "베를린 해제일 — 고정 시각 2:30 은 한 번만",
            "0 30 2 * * *",
            "Europe/Berlin",
            "2026-10-25T00:00+02:00",
            new String[] {"2026-10-25T02:30+02:00", "2026-10-26T02:30+01:00"}),
        Arguments.of(
            "베를린 시작일 — 없는 2:30 은 3:30 으로",
            "30 2 * * *",
            "Europe/Berlin",
            "2027-03-28T00:00+01:00",
            new String[] {"2027-03-28T03:30+02:00", "2027-03-29T02:30+02:00"}),
        Arguments.of(
            "서울(서머타임 없음) — 그대로",
            "0 30 2 * * *",
            "Asia/Seoul",
            "2026-11-01T00:00+09:00",
            new String[] {"2026-11-01T02:30+09:00", "2026-11-02T02:30+09:00"}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dstCases")
  void nextTimes_followVixieRules(
      String description, String cron, String tz, String from, String[] expected) {
    DstAwareCron dst = DstAwareCron.of(cron, ZoneId.of(tz));

    assertThat(dst.nextTimes(at(from), expected.length)).containsExactlyElementsOf(times(expected));
  }

  @Test
  @DisplayName("해제일 — 두 번째 1시대(EST)에 기준이 있으면(재등록·긴 실행) 이미 지난 1:30(EDT)을 다시 돌지 않는다")
  void fallBack_fixedHour_baseInRepeatedHour_doesNotFireAgain() {
    DstAwareCron dst = DstAwareCron.of("0 30 1 * * *", ZoneId.of("America/New_York"));

    // 1:30 EDT 에 시작한 실행이 1:10 EST(=첫 실행 40분 뒤)에 끝났다 — 두 번째 1:30 EST 에 다시 돌면 안 된다
    assertThat(dst.next(at("2026-11-01T01:10-05:00"))).isEqualTo(at("2026-11-02T01:30-05:00"));
    assertThat(dst.next(at("2026-11-01T01:45-04:00"))).isEqualTo(at("2026-11-02T01:30-05:00"));
  }

  @Test
  @DisplayName("Spring 스케줄러 경로 — Trigger 가 실행 완료 시각을 기준으로 이어 가며 같은 규칙을 지킨다")
  void trigger_chainsWithCompletionTimes() {
    DstAwareCronTrigger trigger =
        DstAwareCron.of("0 30 1 * * *", ZoneId.of("America/New_York")).toTrigger();
    Clock clock = Clock.fixed(at("2026-11-01T00:00-04:00"), ZoneOffset.UTC);
    SimpleTriggerContext ctx = new SimpleTriggerContext(clock);

    // 첫 발화: 지금 시각 기준
    Instant first = trigger.nextExecution(ctx);
    assertThat(first).isEqualTo(at("2026-11-01T01:30-04:00"));

    // 50분 걸린 실행이 두 번째 1시대(1:20 EST)에 끝나도 1:30 EST 에 또 돌지 않는다
    ctx.update(first, first, at("2026-11-01T01:20-05:00"));
    assertThat(trigger.nextExecution(ctx)).isEqualTo(at("2026-11-02T01:30-05:00"));
  }

  @Test
  @DisplayName("Trigger — 완료 시각이 예정 시각보다 이르면 예정 시각을 기준으로 한다(CronTrigger 와 같음)")
  void trigger_completionBeforeScheduled_usesScheduled() {
    DstAwareCronTrigger trigger =
        DstAwareCron.of("0 30 2 * * *", ZoneId.of("America/New_York")).toTrigger();
    SimpleTriggerContext ctx = new SimpleTriggerContext();
    Instant scheduled = at("2027-03-14T03:30-04:00");
    ctx.update(scheduled, scheduled, scheduled.minusMillis(5));

    assertThat(trigger.nextExecution(ctx)).isEqualTo(at("2027-03-15T02:30-04:00"));
  }

  @Test
  @DisplayName("서머타임 없는 시간대는 Spring CronExpression 과 결과가 같다")
  void noDstZone_matchesSpring() {
    ZoneId seoul = ZoneId.of("Asia/Seoul");
    Instant from = at("2026-11-01T00:00+09:00");
    for (String cron :
        List.of("0 30 2 * * *", "0 */30 * * * *", "0 0 9 * * 1", "0 0 9 1 * *", "0 15 */3 * * *")) {
      DstAwareCron dst = DstAwareCron.of(cron, seoul);
      CronExpression spring = CronExpression.parse(cron);
      ZonedDateTime cursor = from.atZone(seoul);
      for (Instant actual : dst.nextTimes(from, 20)) {
        cursor = spring.next(cursor);
        assertThat(actual).as(cron).isEqualTo(cursor.toInstant());
      }
    }
  }

  @Test
  @DisplayName("다음 발화가 없는 식(2월 30일)은 null·빈 목록")
  void noNextExecution_returnsNull() {
    DstAwareCron dst = DstAwareCron.of("0 0 0 30 2 *", ZoneId.of("Asia/Seoul"));
    assertThat(dst.next(Instant.parse("2026-11-01T00:00:00Z"))).isNull();
    assertThat(dst.nextTimes(Instant.parse("2026-11-01T00:00:00Z"), 5)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0 */30 * * * *", "0 0 */2 * * *", "@hourly", "0 0 * * * *"})
  @DisplayName("시 칸이 * 로 시작하면 경과 시간 기준")
  void hourWildcard_isElapsedTime(String cron) {
    assertThat(DstAwareCron.of(cron, ZoneId.of("UTC")).isWallClockHour()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0 30 1 * * *", "0 9 * * *", "0 0 1-3 * * *", "@daily", "@weekly"})
  @DisplayName("시 칸이 고정이면 벽시계 기준")
  void fixedHour_isWallClock(String cron) {
    assertThat(DstAwareCron.of(cron, ZoneId.of("UTC")).isWallClockHour()).isTrue();
  }

  @Test
  @DisplayName("잘못된 cron·시간대는 IllegalArgumentException")
  void invalidInput_throws() {
    assertThatThrownBy(() -> DstAwareCron.of("not a cron", "Asia/Seoul"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DstAwareCron.of("0 9 * * *", "Not/AZone"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효하지 않은 시간대입니다");
    assertThatThrownBy(() -> DstAwareCron.of("  ", "Asia/Seoul"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
