package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link OutboxListenerLoop} 루프 종료 조건 — 인터럽트에 멈추는지 확인한다. */
class OutboxListenerLoopTest {

  /**
   * 꺼진 Tibero 채널처럼 인터럽트 플래그를 세운 채 곧바로 돌아오는 채널이어도 루프는 한 번 돌고 멈춘다(바쁜 루프 방지). 멈추지 않으면 호출 수가 끝없이 늘어
   * 상한(1000)에서 스스로 중단시킨다.
   */
  @Test
  void 채널이_인터럽트를_세우고_돌아오면_루프는_멈춘다() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    OutboxListenerLoop[] holder = new OutboxListenerLoop[1];
    OutboxWakeupChannel channel = mock(OutboxWakeupChannel.class);
    doAnswer(
            inv -> {
              if (calls.incrementAndGet() >= 1000) {
                holder[0].stop(); // 회귀 시에도 테스트가 끝나도록 하는 안전장치
              }
              Thread.currentThread().interrupt();
              return null;
            })
        .when(channel)
        .listen(any(), any(), any());
    holder[0] = new OutboxListenerLoop(channel, mock(NotificationDispatchWorker.class), true, true);

    holder[0].loop();

    assertThat(calls.get()).isEqualTo(1);
    // 테스트 스레드의 인터럽트 플래그를 비운다.
    assertThat(Thread.interrupted()).isTrue();
  }
}
