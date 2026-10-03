package com.smartfirehub.audit.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 감사 시각 변환 규칙(WD-11). 저장 TZ 를 인자로 받아 운영(UTC)·dev(KST) 두 조건을 같은 JVM 에서 검증한다. */
class AuditTimesTest {

  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Test
  void offsetBoundary_isConvertedToStorageWallClock() {
    // KST 10-01 00:00 = UTC 09-30 15:00 — 운영(UTC 저장)에서는 15:00, dev(KST 저장)에서는 00:00 과 비교해야 한다
    assertThat(AuditTimes.parseBoundary("2026-10-01T00:00:00+09:00", UTC))
        .isEqualTo(LocalDateTime.of(2026, 9, 30, 15, 0));
    assertThat(AuditTimes.parseBoundary("2026-09-30T15:00:00.000Z", KST))
        .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  @Test
  void offsetlessBoundary_isKeptAsStorageWallClock() {
    // ai-agent 호환: 오프셋 없는 값은 저장 TZ 벽시계로 그대로(현행 동작)
    assertThat(AuditTimes.parseBoundary("2026-09-01T00:00:00", UTC))
        .isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
  }

  @Test
  void blankIsNull_malformedIsIllegalArgument() {
    assertThat(AuditTimes.parseBoundary(null, UTC)).isNull();
    assertThat(AuditTimes.parseBoundary("  ", UTC)).isNull();
    assertThatThrownBy(() -> AuditTimes.parseBoundary("2026-13-01T00:00:00Z", UTC))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fromStorage_attachesStorageOffset() {
    assertThat(AuditTimes.fromStorage(LocalDateTime.of(2026, 9, 30, 15, 0), UTC))
        .isEqualTo(OffsetDateTime.of(2026, 9, 30, 15, 0, 0, 0, ZoneOffset.UTC));
    assertThat(AuditTimes.fromStorage(LocalDateTime.of(2026, 10, 1, 0, 0), KST).toInstant())
        .isEqualTo(OffsetDateTime.of(2026, 9, 30, 15, 0, 0, 0, ZoneOffset.UTC).toInstant());
  }
}
