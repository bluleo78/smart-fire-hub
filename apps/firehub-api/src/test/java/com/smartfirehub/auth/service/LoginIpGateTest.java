package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.util.ClientIpExtractor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 로그인 IP 키 게이트 단위 테스트(WD-630).
 *
 * <p>게이트는 같은 IP 키의 로그인을 로그인 트랜잭션 <b>바깥</b>(커넥션을 빌리기 전)에서 줄 세운다. 여기서는 DB 없이 줄 세우기 자체만 본다: 같은 키 직렬화,
 * 다른 키 병렬, 기다림 상한·줄 길이 상한 초과 → 429 {@code LOGIN_BUSY}(로그인 본문을 아예 부르지 않으므로 IP·계정 카운터가 오르지 않음), 예외 뒤
 * 해제, 인터럽트, 키 없는 요청 통과, 잠금 맵 비워짐. 커넥션 풀과의 관계는 {@code LoginIpLimitTest} 가 실제 DB 로 본다.
 */
class LoginIpGateTest {

  /** 상한 초과 테스트가 오래 걸리지 않도록 짧게 둔다. */
  private static final Duration MAX_WAIT = Duration.ofMillis(300);

  /** 기본 게이트의 키당 줄 길이 상한 — 줄 세우기 테스트(동시 8건)가 걸리지 않게 넉넉히. 상한 자체는 따로 작은 게이트로 본다. */
  private static final int MAX_WAITERS = 16;

  private LoginIpThrottleService throttle;
  private ClientIpExtractor clientIpExtractor;
  private LoginIpGate gate;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    // keyFor 는 저장소를 쓰지 않는다 — 목만 넘긴다. 제외 대역은 루프백.
    throttle = new LoginIpThrottleService(mock(LoginIpFailureRepository.class), "127.0.0.0/8");
    clientIpExtractor = mock(ClientIpExtractor.class);
    gate = new LoginIpGate(throttle, clientIpExtractor, MAX_WAIT, MAX_WAITERS);
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("같은 IP 키의 로그인은 한 번에 하나씩 — 앞 로그인이 끝나야 다음 로그인 본문이 시작된다")
  void sameKey_isSerialized() throws Exception {
    LoginIpGate gate = patientGate();
    Held first = hold(gate, "198.51.100.7");
    AtomicBoolean secondStarted = new AtomicBoolean();

    Future<String> second =
        executor.submit(
            () ->
                gate.run(
                    "198.51.100.7",
                    () -> {
                      secondStarted.set(true);
                      return "second";
                    }));
    awaitQueued(gate, "198.51.100.7", 1);
    assertThat(secondStarted).as("앞 로그인이 쥐고 있는 동안 같은 키는 들어오지 못한다").isFalse();

    first.release();
    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
    assertThat(gate.activeKeys()).isZero();
  }

  @Test
  @DisplayName("IPv6 는 같은 /64 면 주소가 달라도 같은 줄에 선다")
  void ipv6SameSlash64_isSerialized() throws Exception {
    // 줄 길이 1 — 같은 줄이면 기다리지 않고 바로 거절되어, 같은 키인지가 즉시 드러난다.
    LoginIpGate single = new LoginIpGate(throttle, clientIpExtractor, MAX_WAIT, 1);
    Held first = hold(single, "2001:db8:1:2::1");

    assertBusy(() -> single.run("2001:db8:1:2::ffff", () -> "second"));
    // 다른 /64 는 다른 줄
    assertThat(single.run("2001:db8:1:3::1", () -> "other")).isEqualTo("other");

    first.release();
  }

  @Test
  @DisplayName("다른 IP 키끼리는 서로 기다리지 않는다")
  void differentKeys_runInParallel() throws Exception {
    Held first = hold(gate, "198.51.100.8");

    // 첫 키가 잡혀 있는 동안 다른 키는 바로 끝난다
    assertThat(gate.run("198.51.100.9", () -> "other")).isEqualTo("other");

    first.release();
  }

  @Test
  @DisplayName("기다림 상한을 넘으면 429 LOGIN_BUSY — 로그인 본문을 부르지 않아 카운터가 오르지 않는다")
  void waitTimeout_rejectsWithoutRunningLogin() throws Exception {
    Held first = hold(gate, "198.51.100.10");

    AtomicInteger loginBodyCalls = new AtomicInteger();
    long started = System.nanoTime();
    assertBusy(
        () ->
            gate.run(
                "198.51.100.10",
                () -> {
                  // 실제 로그인이라면 여기서 IP·계정 실패 카운터가 오른다
                  loginBodyCalls.incrementAndGet();
                  return "second";
                }));
    long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(loginBodyCalls).as("거절된 요청은 로그인 본문(카운터)을 건드리지 않는다").hasValue(0);
    assertThat(waitedMillis).as("상한만큼 기다린 뒤 거절").isGreaterThanOrEqualTo(250);

    first.release();
    assertThat(gate.activeKeys()).as("거절된 대기자도 맵 참조를 돌려준다").isZero();
  }

  @Test
  @DisplayName("로그인 본문이 예외를 던져도 잠금이 풀린다 — 다음 같은 키 로그인이 바로 들어온다")
  void exceptionInLogin_releasesGate() {
    assertThatThrownBy(
            () ->
                gate.run(
                    "198.51.100.11",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThat(gate.activeKeys()).isZero();

    // 잠금이 남아 있었다면 300ms 상한에 걸려 429 였을 것
    assertThat(gate.run("198.51.100.11", () -> "next")).isEqualTo("next");
  }

  @Test
  @DisplayName("기다리는 중 인터럽트되면 인터럽트 상태를 되살리고 같은 429 로 거절한다")
  void interruptedWhileWaiting_restoresFlagAndRejects() throws Exception {
    Held first = hold(gate, "198.51.100.12");

    AtomicBoolean bodyCalled = new AtomicBoolean();
    Thread.currentThread().interrupt();
    try {
      assertBusy(
          () ->
              gate.run(
                  "198.51.100.12",
                  () -> {
                    bodyCalled.set(true);
                    return "second";
                  }));
      assertThat(Thread.currentThread().isInterrupted()).as("인터럽트 상태 복원").isTrue();
    } finally {
      // 다음 테스트로 인터럽트 상태가 새지 않게 지운다
      Thread.interrupted();
    }
    assertThat(bodyCalled).isFalse();

    first.release();
    assertThat(gate.activeKeys()).isZero();
  }

  @Test
  @DisplayName("키가 없는 요청(IP 모름·제외 대역)은 게이트를 타지 않는다 — 동시에 들어가고 맵에 남지 않는다")
  void noKey_bypassesGate() throws Exception {
    for (String ip : new String[] {null, "127.0.0.1"}) {
      Held first = hold(gate, ip);
      assertThat(gate.activeKeys()).isZero();

      // 줄을 세웠다면 상한에 걸려 429 였을 것
      assertThat(gate.run(ip, () -> "second")).isEqualTo("second");

      first.release();
    }
  }

  @Test
  @DisplayName("요청 IP 없이 부르면 현재 요청의 IP(ClientIpExtractor)로 키를 고른다")
  void runWithoutIp_usesCurrentRequestIp() throws Exception {
    when(clientIpExtractor.currentRequestIp()).thenReturn("198.51.100.13");
    LoginIpGate single = new LoginIpGate(throttle, clientIpExtractor, MAX_WAIT, 1);

    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<String> first =
        executor.submit(
            () ->
                single.run(
                    () -> {
                      inside.countDown();
                      await(release);
                      return "first";
                    }));
    assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(single.activeKeys()).isEqualTo(1);

    // 같은 키의 줄(길이 1)이 차 있으므로 명시한 같은 IP 는 바로 거절된다
    assertBusy(() -> single.run("198.51.100.13", () -> "second"));

    release.countDown();
    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
  }

  @Test
  @DisplayName("키별 잠금은 쓰고 나면 사라진다 — 서로 다른 키를 많이 써도 맵이 쌓이지 않는다")
  void lockMap_doesNotAccumulateKeys() throws Exception {
    for (int i = 0; i < 1000; i++) {
      String ip = "203.0.113." + (i % 250);
      gate.run(i % 2 == 0 ? ip : "2001:db8:" + Integer.toHexString(i) + "::1", () -> "ok");
    }
    assertThat(gate.activeKeys()).isZero();

    // 동시 대기자가 여럿이어도 모두 끝나면 비워진다
    LoginIpGate patient = patientGate();
    String ip = "198.51.100.14";
    Held holder = hold(patient, ip);
    Future<?>[] waiters = new Future<?>[7];
    for (int i = 0; i < waiters.length; i++) {
      waiters[i] = executor.submit(() -> patient.run(ip, () -> "ok"));
    }
    awaitQueued(patient, ip, waiters.length);
    holder.release();
    for (Future<?> waiter : waiters) {
      waiter.get(5, TimeUnit.SECONDS);
    }
    assertThat(patient.activeKeys()).isZero();
  }

  /**
   * 키당 줄 길이 상한(WD-630 리뷰) — 잡은 요청 포함 상한만큼 이미 있으면 새 요청은 기다리지 않고 바로 429. 거절된 요청은 참조를 늘리지 않으므로, 줄이 빠지면
   * 다시 줄을 설 수 있고 모두 끝나면 맵이 빈다.
   */
  @Test
  @DisplayName("키당 줄이 상한이면 새 요청은 기다리지 않고 바로 429 — 줄이 빠지면 다시 설 수 있고 맵은 빈다")
  void tooManyWaiters_rejectsImmediately() throws Exception {
    // 기다림 상한은 길게(10초) — 바로 거절되는지(기다리지 않는지)를 시간으로 가른다.
    LoginIpGate capped = new LoginIpGate(throttle, clientIpExtractor, Duration.ofSeconds(10), 2);
    String ip = "198.51.100.15";

    Held holder = hold(capped, ip);
    // 두 번째는 줄에 선다(잡은 1 + 기다리는 1 = 상한 2)
    Future<String> waiter = executor.submit(() -> capped.run(ip, () -> "waiter"));
    awaitQueued(capped, ip, 1);

    AtomicBoolean bodyCalled = new AtomicBoolean();
    long started = System.nanoTime();
    assertBusy(
        () ->
            capped.run(
                ip,
                () -> {
                  bodyCalled.set(true);
                  return "third";
                }));
    long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertThat(waitedMillis).as("기다리지 않고 바로 거절").isLessThan(1000);
    assertThat(bodyCalled).isFalse();
    // 다른 키는 상한과 무관
    assertThat(capped.run("198.51.100.16", () -> "other")).isEqualTo("other");

    holder.release();
    assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("waiter");
    assertThat(capped.activeKeys()).as("거절된 요청이 참조를 남기지 않아 맵이 빈다").isZero();

    // 줄이 빠졌으니 다시 들어갈 수 있다 — 상한(2)만큼 동시에 서도 거절되지 않는다
    Held again = hold(capped, ip);
    Future<String> againWaiter = executor.submit(() -> capped.run(ip, () -> "again-waiter"));
    awaitQueued(capped, ip, 1);
    again.release();
    assertThat(againWaiter.get(5, TimeUnit.SECONDS)).isEqualTo("again-waiter");
    assertThat(capped.activeKeys()).isZero();
  }

  @Test
  @DisplayName("줄 길이 상한이 1 미만이면 만들 수 없다 — 모든 로그인을 막는 설정을 조용히 받지 않는다")
  void maxWaitersBelowOne_isRejected() {
    assertThatThrownBy(() -> new LoginIpGate(throttle, clientIpExtractor, MAX_WAIT, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 기다림 상한이 긴 게이트 — 줄에 세워 두고 관찰하는 테스트가 부하에서 상한(300ms)에 걸려 흔들리지 않게. */
  private LoginIpGate patientGate() {
    return new LoginIpGate(throttle, clientIpExtractor, Duration.ofSeconds(10), MAX_WAITERS);
  }

  /** 게이트 안에 들어가 풀어 줄 때까지 머무는 로그인 — 같은 키를 쥐고 있는 앞 요청 역할. */
  private record Held(Future<String> future, CountDownLatch releaseLatch) {

    /** 쥔 로그인을 끝내고 정상 종료를 확인한다. */
    void release() throws Exception {
      releaseLatch.countDown();
      assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("held");
    }
  }

  /** 다른 스레드에서 그 IP 로 게이트에 들어가 머문다. 본문에 들어간 것을 확인한 뒤 돌려준다. */
  private Held hold(LoginIpGate target, String ip) throws InterruptedException {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Supplier<String> body =
        () -> {
          inside.countDown();
          await(release);
          return "held";
        };
    Future<String> future = executor.submit(() -> target.run(ip, body));
    assertThat(inside.await(5, TimeUnit.SECONDS)).as("게이트 안에 들어감").isTrue();
    return new Held(future, release);
  }

  /** 그 IP 의 잠금을 기다리는 요청이 {@code expected} 개가 될 때까지(최대 5초) 폴링한다 — 고정 sleep 대신. */
  private static void awaitQueued(LoginIpGate target, String ip, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (target.queueLength(ip) < expected && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(target.queueLength(ip)).as("줄에 선 요청 수").isEqualTo(expected);
  }

  /** 게이트 거절(429 LOGIN_BUSY, 공용 메시지)인지 단언한다. */
  private static void assertBusy(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(e.code()).isEqualTo(LoginIpGate.BUSY_CODE);
            })
        .hasMessage(LoginIpGate.BUSY_MESSAGE);
  }

  /** 래치를 최대 5초 기다린다(테스트가 멈추지 않게). */
  private static boolean await(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
