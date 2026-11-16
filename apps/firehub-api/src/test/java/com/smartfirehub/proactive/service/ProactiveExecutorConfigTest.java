package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 프로액티브 실행 전용 실행기 배선(WD-766). 빈이 없으면 러너의 {@code @Async("proactiveExecutor")} 가 호출 시점에 실패하므로 빈 존재를,
 * 넘칠 때 제출 쪽이 슬롯을 놓도록 거절 정책을 확인한다.
 */
class ProactiveExecutorConfigTest extends IntegrationTestBase {

  @Autowired
  @Qualifier("proactiveExecutor")
  private ThreadPoolTaskExecutor proactiveExecutor;

  @Test
  @DisplayName("proactiveExecutor 빈이 있고, 넘치면 거절한다")
  void proactiveExecutor_isRegistered() {
    // CallerRuns 면 요청·스케줄러 스레드가 리포트 하나(최대 15분)를 직접 돈다 — 거절해 컨트롤러가 슬롯을 놓게 한다(#149).
    assertThat(proactiveExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
  }
}
