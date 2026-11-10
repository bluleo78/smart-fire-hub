package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 커밋 직전 좁히기의 데이터 저장소 풀 포화 판정(WD-701 4차 리뷰 ②) — 빌려 간 연결이 최대 크기에 닿았고 그래도 기다리는 스레드가 있을 때만 포화다. 기다리는
 * 스레드 하나(풀이 새 연결을 만드는 중)나 평범한 바쁨(최대 크기지만 줄 없음)으로 대체 경로 — 차이 슬롯 세션을 테넌트 전체에서 끊기 — 에 가지 않게 한다. 빌리기는
 * 접속 상한·빌리기 스레드 수가 이미 묶는다.
 */
class PythonReadPoolSaturationTest {

  @Test
  void saturatedOnlyWhenAllConnectionsBorrowedAndThreadsWaiting() {
    assertThat(PythonReadGrantSync.poolSaturated(10, 10, 1)).as("다 빌려 갔고 줄이 있다").isTrue();
    assertThat(PythonReadGrantSync.poolSaturated(10, 10, 3)).isTrue();
  }

  @Test
  void singleWaiterWhilePoolStillGrowing_isNotSaturated() {
    assertThat(PythonReadGrantSync.poolSaturated(4, 10, 1)).as("최대 크기 전 — 풀이 새 연결을 만든다").isFalse();
  }

  @Test
  void fullButNoQueue_isNotSaturated() {
    assertThat(PythonReadGrantSync.poolSaturated(10, 10, 0))
        .as("최대 크기지만 기다리는 스레드 없음 — 곧 반납될 연결을 접속 상한 안에서 기다린다")
        .isFalse();
  }
}
