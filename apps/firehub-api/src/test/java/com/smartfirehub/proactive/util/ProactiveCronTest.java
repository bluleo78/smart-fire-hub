package com.smartfirehub.proactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProactiveCron 단위 테스트 (#348)
 *
 * <p>목록의 "다음 실행" 컬럼이 전 작업 상시 '-' 이던 원인은 next_execute_at 을 계산해 넣는 코드가 없었기 때문이다. 계산 유틸이 5필드/6필드 cron
 * 혼재(#347)와 잡별 타임존을 모두 견디고, 실패 시 예외 대신 null 로 폴백하는지 고정한다.
 */
class ProactiveCronTest {

  @Test
  @DisplayName("5필드 cron 은 초 자리를 붙여 6필드로 정규화한다")
  void normalizeFiveFieldCron() {
    assertThat(ProactiveCron.normalize("0 9 * * *")).isEqualTo("0 0 9 * * *");
  }

  @Test
  @DisplayName("이미 6필드인 cron 은 그대로 둔다")
  void normalizeKeepsSixFieldCron() {
    assertThat(ProactiveCron.normalize("0 0 9 * * *")).isEqualTo("0 0 9 * * *");
  }

  @Test
  @DisplayName("5필드 cron 도 다음 실행 시각을 계산한다 — 잡 타임존 기준 09:00")
  void nextExecuteAtHandlesFiveFieldCron() {
    LocalDateTime next = ProactiveCron.nextExecuteAtUtc("0 9 * * *", "Asia/Seoul");

    assertThat(next).isNotNull();
    // 반환값은 UTC 벽시계이므로, KST로 되돌리면 09:00 이어야 한다
    ZonedDateTime inSeoul =
        next.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul"));
    assertThat(inSeoul.getHour()).isEqualTo(9);
    assertThat(inSeoul.getMinute()).isZero();
    // 항상 미래여야 한다 — 이미 지나간 시각을 "다음 실행"으로 보여주면 안 된다
    assertThat(next).isAfter(LocalDateTime.now(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("타임존이 다르면 같은 cron 도 다른 UTC 시각을 낸다")
  void nextExecuteAtRespectsTimezone() {
    LocalDateTime seoul = ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", "Asia/Seoul");
    LocalDateTime utc = ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", "UTC");

    assertThat(seoul).isNotNull();
    assertThat(utc).isNotNull();
    // 서울 09:00 = UTC 00:00, UTC 09:00 = UTC 09:00 → 시(hour)가 달라야 한다
    assertThat(seoul.getHour()).isNotEqualTo(utc.getHour());
  }

  @Test
  @DisplayName("timezone 이 비었으면 기본값(Asia/Seoul)을 쓴다")
  void nextExecuteAtFallsBackToDefaultTimezone() {
    assertThat(ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", null))
        .isEqualTo(ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", ProactiveCron.DEFAULT_TIMEZONE));
    assertThat(ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", "  "))
        .isEqualTo(ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", ProactiveCron.DEFAULT_TIMEZONE));
  }

  @Test
  @DisplayName("잘못된 cron/timezone/빈 값은 예외 대신 null 로 폴백한다")
  void nextExecuteAtReturnsNullOnInvalidInput() {
    // 계산 실패로 잡 실행이나 스케줄 등록이 깨지면 안 되므로 조용히 null
    assertThat(ProactiveCron.nextExecuteAtUtc("이건 cron 이 아니다", "Asia/Seoul")).isNull();
    assertThat(ProactiveCron.nextExecuteAtUtc("0 0 9 * * *", "Not/AZone")).isNull();
    assertThat(ProactiveCron.nextExecuteAtUtc(null, "Asia/Seoul")).isNull();
    assertThat(ProactiveCron.nextExecuteAtUtc("  ", "Asia/Seoul")).isNull();
  }

  // ---- WD-138: 잘못된 cron 의 한국어 설명 — 화면 안내(「분 시 일 월 요일」 5칸) 기준 ----

  @Test
  @DisplayName("유효한 5필드·6필드 cron 은 설명이 없다(null)")
  void describeInvalidReturnsNullForValid() {
    assertThat(ProactiveCron.describeInvalid("0 9 * * *")).isNull();
    assertThat(ProactiveCron.describeInvalid("0 0 9 * * MON-FRI")).isNull();
  }

  @Test
  @DisplayName("칸 수가 틀리면 5칸 기준으로 몇 칸을 넣었는지 알려 준다 — 영어·6칸 문구가 아니다")
  void describeInvalidReportsFieldCountInFiveFieldTerms() {
    String reason = ProactiveCron.describeInvalid("abc");

    assertThat(reason)
        .isEqualTo("「분 시 일 월 요일」 5칸을 공백으로 나눠 입력해야 하는데 1칸을 입력했습니다 (예: 0 9 * * * → 매일 09:00)");
    assertThat(ProactiveCron.describeInvalid("0 9 * *")).contains("4칸을 입력했습니다");
  }

  @Test
  @DisplayName("범위를 벗어난 칸은 사용자가 넣은 값 그대로 칸 이름·허용 범위와 함께 알려 준다 — 초 칸을 붙인 값이 나오지 않는다")
  void describeInvalidNamesTheBadField() {
    assertThat(ProactiveCron.describeInvalid("61 * * * *"))
        .isEqualTo("「분」 칸의 값 \"61\" 이(가) 올바르지 않습니다 (허용 범위 0~59)");
    assertThat(ProactiveCron.describeInvalid("0 25 * * *")).startsWith("「시」 칸의 값 \"25\"");
    assertThat(ProactiveCron.describeInvalid("0 9 * 13 *")).startsWith("「월」 칸의 값 \"13\"");
    assertThat(ProactiveCron.describeInvalid("0 9 * * FOO")).startsWith("「요일」 칸의 값 \"FOO\"");
    // 6필드로 넣으면 맨 앞은 초 칸이다
    assertThat(ProactiveCron.describeInvalid("70 0 9 * * *")).startsWith("「초」 칸의 값 \"70\"");
  }
}
