package com.smartfirehub.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * {@link RefreshTokenRepository#revokedAtNow()} 가 JVM 기본 시간대와 무관하게 UTC 벽시계로 읽히는지 고정한다(WD-1141 리뷰 후속, 반려 3차 5).
 *
 * <p>기본 시간대를 UTC+9 로 바꿔 놓고도 UTC 와 5초 안에 맞아야 한다 — {@code LocalDateTime.now()} 로 돌아가면 9시간이 어긋나 회전 유예 창 전체가
 * 그만큼 벌어진다. 시험 환경의 기본 시간대가 UTC 라면 원래 구현도 같은 값을 내어 이 시험이 회귀를 잡지 못하므로, 여기서 일부러 어긋나게 만든다.
 *
 * <p><b>단위 시험으로만 쓰는 까닭</b>: 전역 상태({@link TimeZone#setDefault})를 바꾼다. Spring 컨텍스트를 쓰는 통합 시험 클래스에 두면 같은 JVM 에
 * 도는 스케줄러·비동기 스레드가 그 순간의 시각을 오염받을 수 있고, 시험 순서에 따라 다른 시험에도 흘러든다 — 그래서 컨텍스트 없는 여기에 둔다.
 * 전역 변경이라 스레드까지 완전히 떼어놓지는 못한다(그건 Clock 주입의 몫) — 컨텍스트 없이 순차로 도는 단위 시험에서만 쓴다.
 */
class RefreshTokenRepositoryClockTest {

  @Test
  void revokedAtNow_ignoresJvmDefaultTimeZone() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    try {
      assertThat(
              Duration.between(
                      RefreshTokenRepository.revokedAtNow(), LocalDateTime.now(ZoneOffset.UTC))
                  .abs())
          .as("판정 시계는 JVM 기본 시간대가 아니라 UTC 벽시계여야 한다")
          .isLessThan(Duration.ofSeconds(5));
    } finally {
      TimeZone.setDefault(original);
    }
  }
}