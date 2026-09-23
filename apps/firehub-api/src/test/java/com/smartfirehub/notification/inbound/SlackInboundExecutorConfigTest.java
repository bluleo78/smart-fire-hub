package com.smartfirehub.notification.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async("slackInboundExecutor")} 가 가리키는 빈 배선 테스트(이슈 #709).
 *
 * <p>이 빈이 없으면 {@code SlackInboundService.dispatch} 가 호출 시점에 실패해 경로 전체가 죽는다.
 * {@code SlackInboundServiceTest} 는 {@code @InjectMocks} 라 {@code @Async} 가 돌지 않아 그것을
 * 잡을 수 없으므로 실제 컨텍스트에서 확인한다.
 */
class SlackInboundExecutorConfigTest extends IntegrationTestBase {

  @Autowired
  @Qualifier("slackInboundExecutor")
  private ThreadPoolTaskExecutor slackInboundExecutor;

  @Test
  @DisplayName("slackInboundExecutor 빈이 있고, 넘치면 거절한다")
  void slackInboundExecutor_isRegisteredAndRejectsOverflow() {
    assertThat(slackInboundExecutor.getThreadNamePrefix()).isEqualTo("slack-inbound-");
    assertThat(slackInboundExecutor.getQueueCapacity()).isEqualTo(20);
    // CallerRuns 면 컨트롤러 스레드가 AI 응답까지 붙잡혀 ack 가 늦어진다
    assertThat(slackInboundExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
        .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
  }
}
