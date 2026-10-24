package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** {@link LastRunAtPlaceholder} 의 감지·치환 계약을 고정한다. */
class LastRunAtPlaceholderTest {

  @Test
  void 공백을_허용해_감지한다() {
    assertThat(LastRunAtPlaceholder.isUsedIn("select 1 where x >= {{ last_run_at }}")).isTrue();
    assertThat(LastRunAtPlaceholder.isUsedIn("select 1 where x >= {{last_run_at}}")).isTrue();
    assertThat(LastRunAtPlaceholder.isUsedIn("select 1")).isFalse();
    assertThat(LastRunAtPlaceholder.isUsedIn(null)).isFalse();
  }

  /** #739 — 증분 스텝 판정은 "SQL 타입 + 플레이스홀더"다. 비SQL 스크립트의 문자열은 증분이 아니다. */
  @Test
  void 증분_스텝_판정은_SQL_타입과_플레이스홀더를_모두_요구한다() {
    assertThat(LastRunAtPlaceholder.isIncrementalStep("SQL", "select 1 where x >= {{last_run_at}}"))
        .isTrue();
    assertThat(LastRunAtPlaceholder.isIncrementalStep("PYTHON", "print(1)  # {{last_run_at}}"))
        .isFalse();
    assertThat(LastRunAtPlaceholder.isIncrementalStep("SQL", "select 1")).isFalse();
    assertThat(LastRunAtPlaceholder.isIncrementalStep(null, "{{last_run_at}}")).isFalse();
    assertThat(LastRunAtPlaceholder.isIncrementalStep("SQL", null)).isFalse();
  }

  @Test
  void null이면_negative_infinity로_치환한다() {
    assertThat(LastRunAtPlaceholder.substitute("a >= {{last_run_at}}", null))
        .isEqualTo("a >= '-infinity'::timestamptz");
  }

  @Test
  void 마이크로초를_보존해_치환한다() {
    // 마이크로초를 잃으면 경계 행(_updated_at = 책갈피)을 다음 실행이 놓친다 — 정밀도는 계약이다.
    OffsetDateTime v = OffsetDateTime.of(2026, 9, 19, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
    assertThat(LastRunAtPlaceholder.substitute("a >= {{last_run_at}} and b >= {{last_run_at}}", v))
        .isEqualTo(
            "a >= '2026-09-19T01:02:03.123456Z'::timestamptz"
                + " and b >= '2026-09-19T01:02:03.123456Z'::timestamptz");
  }

  @Test
  void 공백이_있는_형태도_치환한다() {
    OffsetDateTime v = OffsetDateTime.of(2026, 9, 19, 1, 2, 3, 0, ZoneOffset.UTC);
    assertThat(LastRunAtPlaceholder.substitute("a >= {{  last_run_at  }}", v))
        .isEqualTo("a >= '2026-09-19T01:02:03Z'::timestamptz");
  }

  // ── Tibero 방언(WD-77 Task 10) ─────────────────────────────────────────────────────

  /** 서버에서 UTC instant 를 서버 벽시계(시간대 없음)로 바꾸는 식 — Ruling 13. */
  private static String tiberoExpr(String utc) {
    return "CAST(TIMESTAMP '"
        + utc
        + " +00:00' AT TIME ZONE TO_CHAR(SYSTIMESTAMP, 'TZH:TZM') AS TIMESTAMP)";
  }

  /**
   * _UPDATED_AT 은 서버 벽시계(시간대 없는 TIMESTAMP)라 UTC 벽시계 리터럴은 서버 시간대가 UTC 가 아니면 어긋난다. 그래서 instant(UTC)를
   * 서버 오프셋으로 옮기는 식으로 치환한다.
   */
  @Test
  void tibero는_서버_벽시계로_변환하는_식으로_치환한다() {
    OffsetDateTime v = OffsetDateTime.of(2026, 9, 19, 1, 2, 3, 123_456_000, ZoneOffset.UTC);
    String expr = tiberoExpr("2026-09-19 01:02:03.123456");
    assertThat(
            LastRunAtPlaceholder.substitute(
                "a >= {{last_run_at}} and b >= {{ last_run_at }}", v, DataStoreBackend.TIBERO))
        .isEqualTo("a >= " + expr + " and b >= " + expr);
  }

  @Test
  void tibero는_다른_오프셋_값을_UTC_instant로_바꿔_치환한다() {
    // +09:00 10:00 = UTC 01:00 — 오프셋을 그대로 버리면 9시간 어긋난다.
    OffsetDateTime v = OffsetDateTime.of(2026, 9, 19, 10, 0, 0, 5_000, ZoneOffset.ofHours(9));
    assertThat(LastRunAtPlaceholder.substitute("x >= {{last_run_at}}", v, DataStoreBackend.TIBERO))
        .isEqualTo("x >= " + tiberoExpr("2026-09-19 01:00:00.000005"));
  }

  @Test
  void tibero는_null이면_최소_리터럴로_치환한다() {
    assertThat(
            LastRunAtPlaceholder.substitute("x >= {{last_run_at}}", null, DataStoreBackend.TIBERO))
        .isEqualTo("x >= TIMESTAMP '0001-01-01 00:00:00.000000'");
  }

  @Test
  void pg_백엔드와_두인자_메서드는_기존_출력_그대로다() {
    OffsetDateTime v = OffsetDateTime.of(2026, 9, 19, 1, 2, 3, 0, ZoneOffset.UTC);
    String expected = "a >= '2026-09-19T01:02:03Z'::timestamptz";
    assertThat(LastRunAtPlaceholder.substitute("a >= {{last_run_at}}", v)).isEqualTo(expected);
    assertThat(
            LastRunAtPlaceholder.substitute("a >= {{last_run_at}}", v, DataStoreBackend.POSTGRESQL))
        .isEqualTo(expected);
    assertThat(
            LastRunAtPlaceholder.substitute(
                "a >= {{last_run_at}}", null, DataStoreBackend.POSTGRESQL))
        .isEqualTo("a >= '-infinity'::timestamptz");
  }
}
