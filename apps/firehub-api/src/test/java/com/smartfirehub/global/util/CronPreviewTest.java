package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.dto.CronPreviewResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link CronPreview} 단위 테스트 (WD-391) — 미리보기가 스케줄러와 같은 서머타임 규칙으로 계산되는지 고정 시각으로 확인한다. */
class CronPreviewTest {

  @Test
  @DisplayName("뉴욕 서머타임 시작일 — 매일 2:30 은 그날 3:30 EDT(07:30Z)로 미리보기된다")
  void preview_springForward_shiftsToSameDay() {
    Instant from = OffsetDateTime.parse("2027-03-13T12:00-05:00").toInstant();

    CronPreviewResponse res = CronPreview.preview("30 2 * * *", "America/New_York", 3, from);

    assertThat(res.cron()).isEqualTo("0 30 2 * * *");
    assertThat(res.timezone()).isEqualTo("America/New_York");
    assertThat(res.nextFireTimes())
        .containsExactly("2027-03-14T07:30:00Z", "2027-03-15T06:30:00Z", "2027-03-16T06:30:00Z");
  }

  @Test
  @DisplayName("뉴욕 서머타임 해제일 — 매일 1:30 은 한 번만(05:30Z, 다음 날 06:30Z)")
  void preview_fallBack_firesOnce() {
    Instant from = OffsetDateTime.parse("2026-10-31T12:00-04:00").toInstant();

    assertThat(CronPreview.preview("30 1 * * *", "America/New_York", 3, from).nextFireTimes())
        .containsExactly("2026-11-01T05:30:00Z", "2026-11-02T06:30:00Z", "2026-11-03T06:30:00Z");
  }

  @Test
  @DisplayName("매크로(@daily)도 저장 검증처럼 받아들인다")
  void preview_acceptsMacro() {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    assertThat(CronPreview.preview("@daily", "UTC", 2, from).nextFireTimes())
        .containsExactly("2026-11-02T00:00:00Z", "2026-11-03T00:00:00Z");
  }
}
