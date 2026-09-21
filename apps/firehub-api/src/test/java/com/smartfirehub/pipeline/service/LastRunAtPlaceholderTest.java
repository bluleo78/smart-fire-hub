package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
