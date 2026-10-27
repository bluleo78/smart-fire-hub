package com.smartfirehub.dbexecutor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 풀 설정 검증(리뷰 M-7) — 슬롯 풀 크기가 1 미만이면 첫 슬롯 조회 때 Hikari 가 예외를 내므로, 실행 중이 아니라 설정 바인딩(기동) 시점에 막는다. */
class DbExecutorPoolTest {

  @Test
  void slotMaxSizeBelowOne_rejectedAtConstruction() {
    assertThatThrownBy(() -> new DbExecutorProperties.Pool(8, 4, 2, 8, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("slot-max-size");
  }

  @Test
  void defaults_keepSlotMaxSizeThree() {
    assertThat(new DbExecutorProperties.Pool(8, 4, 2).slotMaxSize()).isEqualTo(3);
    assertThat(new DbExecutorProperties.Pool(8, 4, 2, 8, 1).slotMaxSize()).isEqualTo(1);
  }
}
