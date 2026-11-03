package com.smartfirehub.global.platformdb.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PostgresOutboxWakeupChannel 의 왕복 계약(WD-486) — publish 한 신호가 listen 의 onSignal 로 도착하고, running 이
 * false 가 되면 listen 이 정상 반환한다. 모든 대기에 상한을 두고 리스너 스레드는 finally 에서 반드시 멈춘다.
 */
class PostgresOutboxWakeupChannelTest extends IntegrationTestBase {

  @Autowired private OutboxWakeupChannel channel;

  @Test
  void publish_신호가_listen_콜백으로_도착하고_running_false면_반환한다() throws Exception {
    CountDownLatch signaled = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    // 테스트가 직접 쥔 플래그 — listen 에게 "계속 돌아도 되는가"를 알려준다.
    AtomicBoolean running = new AtomicBoolean(true);
    Thread listener =
        new Thread(
            () -> {
              try {
                channel.listen(running::get, Duration.ofMillis(200), signaled::countDown);
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "test-outbox-listener");
    listener.setDaemon(true);
    try {
      listener.start();
      // LISTEN 시작 시점을 알 수 없으므로, 신호가 올 때까지 짧은 간격으로 반복 발행한다(5초 상한).
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!signaled.await(100, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
        channel.publish();
      }
      assertThat(signaled.getCount()).as("NOTIFY 가 5초 안에 도착해야 한다").isZero();

      running.set(false);
      listener.join(5_000);
      assertThat(listener.isAlive()).as("running=false 뒤 listen 이 반환해야 한다").isFalse();
      assertThat(failure.get()).isNull();
    } finally {
      running.set(false);
      listener.interrupt();
      listener.join(5_000);
    }
  }
}
