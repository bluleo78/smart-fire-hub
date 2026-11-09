package com.smartfirehub.global.platformdb.tibero;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * {@link TiberoOutboxWakeupChannel} 실서버 테스트(WD-493) — DBMS_ALERT 로 신호가 구독자를 깨우고, 종료 뒤 등록이 남지 않는지 공유
 * 개발 Tibero 에서 본다.
 *
 * <p>공유 서버 안전장치: 이번 실행만의 계정 하나(풀 3 = 구독·발행·여유)를 쓰고 끝나면 지운다. 구독 스레드는 {@code finally} 에서 반드시 멈춘다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = SECONDS)
class TiberoOutboxWakeupChannelLiveTest {

  private TiberoTestAccount account;
  private TiberoOutboxWakeupChannel channel;

  @BeforeAll
  void setUp() {
    account = TiberoTestAccount.create("ALERT", Privileges.TABLES, 3);
    channel =
        new TiberoOutboxWakeupChannel(
            account.dataSource(), () -> account.dataSource().getConnection());
  }

  /** 이번 계정을 지우고, 남은 FHT_ALERT_* 계정 수를 읽기 전용으로 세어 증거로 남긴다. */
  @AfterAll
  void tearDown() {
    if (account != null) account.close();
    ev("남은 FHT_ALERT_* 계정 " + TiberoTestAccount.remainingAccounts("ALERT"));
  }

  private static void ev(String line) {
    TiberoTestEvidence.write("ALERT " + line);
  }

  /** 알림 이름은 이 계정 스키마로 나뉜다 — 실서버 풀에서 읽은 이름이 계정 이름의 해시와 같고 30자 안이다. */
  @Test
  void alertNameIsNamespacedByAccountSchema() throws Exception {
    String name = channel.alertName();
    assertThat(name)
        .isEqualTo(TiberoOutboxWakeupChannel.alertNameFor(account.name().toUpperCase()));
    assertThat(name.length()).isLessThanOrEqualTo(TiberoOutboxWakeupChannel.MAX_ALERT_NAME_LENGTH);
    ev("알림 이름: 스키마 해시 접미사 일치, 길이 " + name.length());
  }

  /**
   * 권한 있는 계정이면 기동 점검(REMOVE)이 통과해 채널이 켜진 채로 남는다(WD-691). 권한 없는 계정 실서버 테스트는 공유 서버의 PUBLIC 권한을 건드려야
   * 해서 하지 않는다.
   */
  @Test
  void 기동_점검이_통과하면_채널이_켜져_있다() {
    assertThat(channel.probe()).isEqualTo(TiberoOutboxWakeupChannel.ProbeResult.OK);
    channel.afterPropertiesSet();
    assertThat(channel.enabled()).isTrue();
    ev("기동 점검 통과: probe=OK, enabled=true");
  }

  @Test
  void signalWakesListener() throws Exception {
    var running = new AtomicBoolean(true);
    var latch = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> listener =
          pool.submit(
              () -> {
                channel.listen(running::get, Duration.ofSeconds(1), latch::countDown);
                return null;
              });
      long t0 = System.nanoTime();
      boolean woke = false;
      // 구독 등록이 끝나기 전 신호는 놓칠 수 있으므로 200ms 간격으로 반복 발행한다.
      while (!woke && System.nanoTime() - t0 < Duration.ofSeconds(10).toNanos()) {
        channel.publish();
        woke = latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      long wokeMs = (System.nanoTime() - t0) / 1_000_000;
      assertThat(woke).isTrue();
      running.set(false);
      long t1 = System.nanoTime();
      listener.get(3, SECONDS);
      ev(
          "signalWakesListener: 신호 뒤 "
              + wokeMs
              + "ms 에 깨어남, 중지 뒤 "
              + (System.nanoTime() - t1) / 1_000_000
              + "ms 에 반환");
    } finally {
      running.set(false);
      pool.shutdownNow();
    }
  }

  @Test
  void listenerKeepsWaitingAcrossTimeouts() throws Exception {
    var running = new AtomicBoolean(true);
    var signals = new AtomicInteger();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> listener =
          pool.submit(
              () -> {
                channel.listen(running::get, Duration.ofSeconds(1), signals::incrementAndGet);
                return null;
              });
      Thread.sleep(2500);
      assertThat(listener.isDone()).as("신호 없이도 구독이 계속 대기").isFalse();
      assertThat(signals.get()).isZero();
      running.set(false);
      assertThatCode(() -> listener.get(3, SECONDS)).doesNotThrowAnyException();
      ev("listenerKeepsWaitingAcrossTimeouts: 2.5초 동안 onSignal " + signals.get() + "회, 예외 없음");
    } finally {
      running.set(false);
      pool.shutdownNow();
    }
  }

  @Test
  void listenerExitsWithoutLeavingRegistration() throws Exception {
    // 첫 구독을 즉시 종료시켜(REGISTER 직후 REMOVE) 등록이 남는지 본다.
    channel.listen(() -> false, Duration.ofSeconds(1), () -> {});
    assertThatCode(channel::publish).doesNotThrowAnyException();

    var signals = new AtomicInteger();
    var calls = new AtomicInteger();
    // 다시 구독해 1초 대기 — 남은 등록이 있었다면 그동안 쌓인 신호가 바로 잡힌다.
    channel.listen(
        () -> calls.getAndIncrement() < 1, Duration.ofSeconds(1), signals::incrementAndGet);
    assertThat(signals.get()).isZero();
    ev(
        "listenerExitsWithoutLeavingRegistration: 종료 뒤 publish 예외 없음, 재구독 1초 동안 onSignal "
            + signals.get()
            + "회");
  }
}
