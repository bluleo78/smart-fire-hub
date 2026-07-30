package com.smartfirehub.proactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProactiveTime 단위 테스트 (#349)
 *
 * <p>proactive 모듈이 DB에 쓰는 시각이 JVM 기본 타임존에 좌우되면, 같은 테이블에 UTC(Postgres now() 기본값)와 KST(애플리케이션 기록)가
 * 섞여 저장되어 프론트가 9시간 어긋나게 표시한다. nowUtc()가 기본 타임존과 무관하게 UTC 벽시계를 반환하는지 고정한다.
 */
class ProactiveTimeTest {

  @Test
  @DisplayName("nowUtc()는 JVM 기본 타임존과 무관하게 UTC 벽시계 값을 반환한다")
  void nowUtcIsIndependentOfDefaultZone() {
    LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC);
    LocalDateTime actual = ProactiveTime.nowUtc();

    // 두 호출 사이의 실행 시간만큼만 차이가 나야 한다 (타임존 오프셋만큼 벌어지면 실패)
    assertThat(Duration.between(expected, actual).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("nowUtc()는 로컬 시각(JVM 기본 존)과 UTC 오프셋만큼 차이난다 — 기본 존이 UTC가 아닐 때")
  void nowUtcDiffersFromSystemLocalWhenZoneIsNotUtc() {
    int offsetSeconds =
        java.time.ZoneId.systemDefault()
            .getRules()
            .getOffset(java.time.Instant.now())
            .getTotalSeconds();
    LocalDateTime local = LocalDateTime.now();
    LocalDateTime utc = ProactiveTime.nowUtc();

    // 기대 차이 = 시스템 존 오프셋. UTC 환경(운영 컨테이너)에서는 0, KST 로컬 개발에서는 9시간.
    assertThat(Duration.between(utc, local).toSeconds())
        .isCloseTo(offsetSeconds, org.assertj.core.data.Offset.offset(5L));
  }
}
