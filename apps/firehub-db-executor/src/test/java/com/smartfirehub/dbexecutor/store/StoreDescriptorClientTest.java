package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.LogCapture;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 기술자 조회·캐시: 성공만 캐시, 단일 조회, fail-closed. */
class StoreDescriptorClientTest {

  private static final String PASSWORD = "S3cr3t-Pw!";
  private static final String TIBERO_JSON =
      "{\"tenantId\":7,\"backend\":\"TIBERO\",\"host\":\"h\",\"port\":8629,\"database\":\"tb\","
          + "\"schema\":\"GENIA_T7\",\"owner\":{\"username\":\"GENIA_T7\",\"password\":\""
          + PASSWORD
          + "\"},\"extra\":1}";

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger epochHits = new AtomicInteger();
  private final AtomicReference<String> epochBody =
      new AtomicReference<>("{\"scriptTokenEpoch\":4}");
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>(TIBERO_JSON);
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastUri = new AtomicReference<>();
  private volatile long delayMs = 0;

  /** 세대 창구 지연 — {@link #slowEpochTenant} 경로에만 건다(다른 테넌트가 막히지 않는지 보려고). */
  private volatile long epochDelayMs = 0;

  private volatile long slowEpochTenant = -1;

  /**
   * 기술자 요청 문(WD-263) — 도착한 요청은 큐에서 문 하나를 꺼내 열릴 때까지 응답을 미룬다(큐가 비면 바로 응답). 잠자기 대신 문으로 순서를 고정해 테스트를
   * 결정적으로 만든다.
   */
  private final LinkedBlockingQueue<CountDownLatch> gates = new LinkedBlockingQueue<>();

  /** 만든 문 전부 — 테스트가 끝나면 모두 연다. */
  private final List<CountDownLatch> allGates = new CopyOnWriteArrayList<>();

  /** 기술자 요청이 도착할 때마다 하나씩 풀린다 — 「원격 조회가 진행 중」이 된 것을 기다리는 데 쓴다. */
  private final Semaphore entered = new Semaphore(0);

  private final AtomicLong clock = new AtomicLong(0);
  private StoreDescriptorClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(16));
    server.createContext(
        "/internal/executor/tenants/",
        ex -> {
          if (ex.getRequestURI().getPath().endsWith("/script-token-epoch")) {
            epochHits.incrementAndGet();
            if (epochDelayMs > 0
                && ex.getRequestURI().getPath().contains("/tenants/" + slowEpochTenant + "/")) {
              try {
                Thread.sleep(epochDelayMs);
              } catch (InterruptedException ignored) {
              }
            }
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastUri.set(ex.getRequestURI().toString());
            byte[] payload = epochBody.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
            return;
          }
          hits.incrementAndGet();
          lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          lastUri.set(ex.getRequestURI().toString());
          // 응답 본문·상태는 요청이 <b>도착한 시점</b> 값으로 정한다 — 「무효화 전에 시작된 조회는 옛 값을 받는다」를 재현하려고.
          byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
          int st = status.get();
          entered.release();
          CountDownLatch gate = gates.poll();
          try {
            if (gate != null) {
              gate.await();
            }
            if (delayMs > 0) {
              Thread.sleep(delayMs);
            }
          } catch (InterruptedException ignored) {
          }
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(st, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    client =
        new StoreDescriptorClient(
            new DbExecutorProperties(
                "tok",
                "load-tok",
                null,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                60,
                new DbExecutorProperties.Pool(8, 4, 2),
                false),
            new ObjectMapper(),
            clock::get);
  }

  @AfterEach
  void stop() {
    // 실패한 테스트가 닫힌 문을 남겨도 서버 스레드가 영원히 막히지 않게 모두 연다.
    allGates.forEach(CountDownLatch::countDown);
    server.stop(0);
  }

  /** 세대만 5초마다 확인해도 접속 기술자 캐시(60초)는 다시 읽지 않는다. */
  @Test
  void scriptTokenEpoch_refreshesIndependentlyOfDescriptor() {
    client.get(7);
    assertThat(client.getScriptTokenEpoch(7, Duration.ofSeconds(10))).isEqualTo(4L);
    assertThat(lastUri.get()).isEqualTo("/internal/executor/tenants/7/script-token-epoch");
    assertThat(lastAuth.get()).isEqualTo("Internal tok");
    epochBody.set("{\"scriptTokenEpoch\":5}");
    clock.addAndGet(Duration.ofSeconds(4).toNanos());
    assertThat(client.getScriptTokenEpoch(7, Duration.ofSeconds(10))).isEqualTo(4L);
    clock.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(client.getScriptTokenEpoch(7, Duration.ofSeconds(10))).isEqualTo(5L);
    assertThat(epochHits.get()).isEqualTo(2);
    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void scriptTokenEpoch_nullFailsClosed() {
    epochBody.set("{\"scriptTokenEpoch\":null}");
    assertThatThrownBy(() -> client.getScriptTokenEpoch(7, Duration.ofSeconds(10)))
        .isInstanceOf(StoreUnavailableException.class);
  }

  /**
   * 리뷰 I-4: 세대를 한 번 캐시한 뒤 5초가 지나 다음 조회가 실패하면(503·음수·정수 아님·소수) 옛 값을 내주지 않고 거부한다. 실패는 캐시하지 않아 매번 다시
   * 묻고(epochHits 증가), 정상 응답이 오면 새 값이 나온다.
   */
  @Test
  void scriptTokenEpoch_expiredCacheThenFailure_throwsAndDoesNotServeStale() {
    Duration budget = Duration.ofSeconds(10);
    assertThat(client.getScriptTokenEpoch(7, budget)).isEqualTo(4L);
    clock.addAndGet(Duration.ofSeconds(6).toNanos());
    int before = epochHits.get();

    status.set(503);
    assertThatThrownBy(() -> client.getScriptTokenEpoch(7, budget))
        .isInstanceOf(StoreUnavailableException.class);
    status.set(200);
    for (String bad :
        List.of(
            "{\"scriptTokenEpoch\":-1}",
            "{\"scriptTokenEpoch\":\"4\"}",
            "{\"scriptTokenEpoch\":1.5}")) {
      epochBody.set(bad);
      assertThatThrownBy(() -> client.getScriptTokenEpoch(7, budget))
          .as(bad)
          .isInstanceOf(StoreUnavailableException.class);
    }
    assertThat(epochHits.get()).as("실패는 캐시하지 않는다").isEqualTo(before + 4);

    epochBody.set("{\"scriptTokenEpoch\":5}");
    assertThat(client.getScriptTokenEpoch(7, budget)).isEqualTo(5L);
  }

  /** 리뷰 I-4: 만료된 캐시 뒤 연결 실패도 옛 값 없이 거부한다. */
  @Test
  void scriptTokenEpoch_expiredCacheThenConnectionFailure_throws() {
    assertThat(client.getScriptTokenEpoch(7, Duration.ofSeconds(10))).isEqualTo(4L);
    clock.addAndGet(Duration.ofSeconds(6).toNanos());
    server.stop(0);
    assertThatThrownBy(() -> client.getScriptTokenEpoch(7, Duration.ofSeconds(10)))
        .isInstanceOf(StoreUnavailableException.class);
  }

  /** 리뷰 I-3: 같은 테넌트의 진행 중 세대 조회를 기다리는 쪽도 자기 남은 시간까지만 기다린다(잠금 안에서 원격 호출을 기다리지 않는다). */
  @Test
  void scriptTokenEpoch_waiterOnInflight_respectsItsOwnBudget() throws Exception {
    slowEpochTenant = 7;
    epochDelayMs = 1_500;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Long> leader = pool.submit(() -> client.getScriptTokenEpoch(7, Duration.ofSeconds(10)));
    Thread.sleep(200); // 선행 조회가 진행 중이 되게
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.getScriptTokenEpoch(7, Duration.ofMillis(200)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_000));
    assertThat(leader.get()).isEqualTo(4L);
    assertThat(epochHits.get()).as("같은 테넌트는 원격 조회 하나를 같이 쓴다").isEqualTo(1);
    pool.shutdown();
  }

  /**
   * 리뷰 I-3: 한 테넌트의 느린 세대 조회가 다른 테넌트를 막지 않는다. 1 과 17 은 기본 크기(16) 맵에서 같은 칸이라 {@code compute} 잠금이면
   * 막힌다.
   */
  @Test
  void scriptTokenEpoch_slowTenantDoesNotBlockOtherTenant() throws Exception {
    slowEpochTenant = 1;
    epochDelayMs = 1_500;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Long> slow = pool.submit(() -> client.getScriptTokenEpoch(1, Duration.ofSeconds(10)));
    Thread.sleep(200);
    long start = System.nanoTime();
    assertThat(client.getScriptTokenEpoch(17, Duration.ofSeconds(10))).isEqualTo(4L);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(800));
    assertThat(slow.get()).isEqualTo(4L);
    pool.shutdown();
  }

  /** 리뷰 M-9: 세대 조회 실패는 401 로만 보이므로 db-executor 로그에 사유(비밀 없음)를 WARN 으로 남긴다. */
  @Test
  void scriptTokenEpoch_failure_logsReasonWithoutSecrets() {
    status.set(503);
    try (LogCapture logs = LogCapture.of(StoreDescriptorClient.class)) {
      assertThatThrownBy(() -> client.getScriptTokenEpoch(7, Duration.ofSeconds(10)))
          .isInstanceOf(StoreUnavailableException.class);
      assertThat(logs.events())
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage())
                    .contains("tenant=7")
                    .contains("503")
                    .doesNotContain("Internal ");
              });
    }
  }

  @Test
  void tibero200_succeeds_andCachesWithinTtl() {
    StoreDescriptor d = client.get(7);
    assertThat(d.host()).isEqualTo("h");
    assertThat(d.port()).isEqualTo(8629);
    assertThat(d.owner().username()).isEqualTo("GENIA_T7");
    assertThat(lastAuth.get()).isEqualTo("Internal tok");

    clock.addAndGet(59_000_000_000L);
    client.get(7);
    assertThat(hits.get()).isEqualTo(1);

    clock.addAndGet(2_000_000_000L); // TTL(60s) 경과
    client.get(7);
    assertThat(hits.get()).isEqualTo(2);
  }

  /** RO 계정을 받으려고 {@code ?include=ro} 로 요청하고, 응답의 ro 를 파싱하되 toString 에는 비밀번호가 없다. */
  @Test
  void requestsRoAccount_andParsesIt_withoutLeakingPassword() {
    String roPassword = "Ro-Pw-Secret";
    body.set(
        TIBERO_JSON.replace(
            "\"extra\":1",
            "\"ro\":{\"username\":\"GENIA_T7_RO\",\"password\":\"" + roPassword + "\"}"));

    StoreDescriptor d = client.get(7);

    assertThat(lastUri.get()).isEqualTo("/internal/executor/tenants/7/data-store?include=ro,slots");
    assertThat(d.ro().username()).isEqualTo("GENIA_T7_RO");
    assertThat(d.ro().password()).isEqualTo(roPassword);
    assertThat(d.toString()).doesNotContain(roPassword).doesNotContain(PASSWORD);
  }

  /** 슬롯 계정·스크립트 토큰 세대(WD-78)를 파싱하고, 슬롯 비밀번호는 toString 에 나오지 않는다. */
  @Test
  void parsesSlotsAndScriptTokenEpoch_withoutLeakingSlotPassword() {
    String slotPassword = "Slot-Pw-Secret";
    body.set(
        TIBERO_JSON.replace(
            "\"extra\":1",
            "\"slots\":[{\"slot\":2,\"username\":\"GENIA_T7_S2\",\"password\":\""
                + slotPassword
                + "\"}],\"scriptTokenEpoch\":4"));

    StoreDescriptor d = client.get(7);

    assertThat(d.slot(2).username()).isEqualTo("GENIA_T7_S2");
    assertThat(d.slot(2).password()).isEqualTo(slotPassword);
    assertThat(d.slot(1)).isNull();
    assertThat(d.scriptTokenEpoch()).isEqualTo(4L);
    assertThat(d.toString()).doesNotContain(slotPassword);
  }

  /** ro 가 없는 응답(아직 RO 계정 미생성)도 완전한 기술자다 — 파이프라인 경로는 RO 없이 동작한다. */
  @Test
  void roAbsent_isStillComplete() {
    StoreDescriptor d = client.get(7);
    assertThat(d.ro()).isNull();
  }

  @Test
  void concurrentCalls_singleRemoteFetch() throws Exception {
    delayMs = 300;
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<StoreDescriptor>> fs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      fs.add(
          pool.submit(
              () -> {
                go.await();
                return client.get(7);
              }));
    }
    go.countDown();
    for (Future<StoreDescriptor> f : fs) {
      assertThat(f.get().tenantId()).isEqualTo(7);
    }
    pool.shutdown();
    assertThat(hits.get()).isEqualTo(1);
  }

  /** Review Focus 4: 비정상 응답은 한국어 예외, 비밀번호 비노출, 풀 미생성, 캐시 안 함. */
  @Test
  void descriptor_nonOk_failsWithKoreanMessage_noPool() {
    AtomicInteger poolCreated = new AtomicInteger();
    TenantPools pools =
        new TenantPools(
            d -> {
              poolCreated.incrementAndGet();
              return null;
            },
            StoreDescriptor::owner,
            8);
    int[] codes = {409, 503, 401};
    for (int code : codes) {
      status.set(code);
      body.set("{\"error\":\"" + PASSWORD + "\"}");
      assertThatThrownBy(() -> pools.borrow(client.get(7)))
          .isInstanceOf(StoreUnavailableException.class)
          .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(" + code + ").")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain(PASSWORD));
    }
    status.set(200);
    body.set("{\"tenantId\":7,\"backend\":\"POSTGRESQL\"}");
    assertThatThrownBy(() -> pools.borrow(client.get(7)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessageStartingWith("테넌트 저장소 정보를 가져오지 못했습니다(");
    assertThat(poolCreated.get()).isZero();
    assertThat(hits.get()).isEqualTo(4); // 실패는 캐시하지 않는다.
  }

  @Test
  void failureNotCached_thenRecovers() {
    status.set(503);
    assertThatThrownBy(() -> client.get(7)).isInstanceOf(StoreUnavailableException.class);
    status.set(200);
    assertThat(client.get(7).tenantId()).isEqualTo(7);
  }

  @Test
  void connectionFailure_failsClosed() {
    server.stop(0);
    assertThatThrownBy(() -> client.get(7))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(연결 실패).");
  }

  /** 최종 리뷰 FF1: 전송 실패는 원인 클래스·메시지를 WARN 으로 남긴다(예외 객체는 넘기지 않는다 — 응답에는 여전히 상태 표지만). */
  @Test
  void connectionFailure_logsCauseWithoutThrowable() {
    server.stop(0);
    try (LogCapture logs = LogCapture.of(StoreDescriptorClient.class)) {
      assertThatThrownBy(() -> client.get(7)).isInstanceOf(StoreUnavailableException.class);

      assertThat(logs.events()).hasSize(1);
      ILoggingEvent e = logs.events().get(0);
      assertThat(e.getLevel()).isEqualTo(Level.WARN);
      assertThat(e.getThrowableProxy()).isNull();
      assertThat(e.getFormattedMessage())
          .contains("tenant=7")
          .contains("연결 실패")
          .contains("ConnectException")
          .doesNotContain("Internal ");
    }
  }

  /** 최종 리뷰 FF1: api 가 200 이 아닌 상태(예: 기본 토큰이라 503)를 주면 그 상태를 WARN 으로 남긴다 — 첫 배포 때 가장 흔한 실패다. */
  @Test
  void nonOkStatus_logsStatus() {
    status.set(503);
    try (LogCapture logs = LogCapture.of(StoreDescriptorClient.class)) {
      assertThatThrownBy(() -> client.get(7)).isInstanceOf(StoreUnavailableException.class);
      assertThat(logs.events())
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("tenant=7").contains("503");
              });
    }
  }

  @Test
  void invalidDescriptors_failClosed() {
    String base =
        "\"backend\":\"TIBERO\",\"host\":\"h\",\"port\":1,\"database\":\"d\",\"schema\":\"S\",";
    String owner = "\"owner\":{\"username\":\"u\",\"password\":\"p\"}";
    String[] bad = {
      "null",
      "{\"tenantId\":8," + base + owner + "}", // 다른 테넌트
      "{\"tenantId\":7," + base.replace("\"schema\":\"S\",", "") + owner + "}",
      "{\"tenantId\":7," + base.replace("\"host\":\"h\"", "\"host\":\" \"") + owner + "}",
      "{\"tenantId\":7," + base.replace("\"port\":1,", "") + owner + "}",
      "{\"tenantId\":7," + base.replace("\"database\":\"d\"", "\"database\":\"\"") + owner + "}",
      "{\"tenantId\":7," + base + "\"owner\":{\"username\":\"\",\"password\":\"p\"}}",
      "{\"tenantId\":7," + base + "\"owner\":{\"username\":\"u\",\"password\":\" \"}}",
      "{\"tenantId\":7," + base + "\"owner\":{\"username\":\"u\"}}",
      "{\"tenantId\":7," + base + "\"owner\":null}"
    };
    for (String b : bad) {
      body.set(b);
      assertThatThrownBy(() -> client.get(7))
          .as(b)
          .isInstanceOf(StoreUnavailableException.class)
          .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(응답 형식 오류).");
    }
  }

  /** Error 가 나도 같은 테넌트 대기자가 영원히 막히지 않는다. */
  @Test
  void errorInFetch_wakesWaiters() throws Exception {
    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public <T> T readValue(String content, Class<T> valueType) {
            throw new StackOverflowError("boom");
          }
        };
    StoreDescriptorClient c =
        new StoreDescriptorClient(
            new DbExecutorProperties(
                "tok",
                "load-tok",
                null,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                60,
                new DbExecutorProperties.Pool(8, 4, 2),
                false),
            boom,
            clock::get);
    delayMs = 300;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Object>> fs = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      fs.add(
          pool.submit(
              () -> {
                try {
                  c.get(7);
                  return "ok";
                } catch (Throwable t) {
                  return t;
                }
              }));
    }
    for (Future<Object> f : fs) {
      assertThat(f.get(5, TimeUnit.SECONDS)).isInstanceOf(Error.class);
    }
    pool.shutdown();
  }

  /** F1: 원격 조회는 호출자의 남은 시간까지만 기다린다 — 실패는 캐시하지 않는다. */
  @Test
  void slowFetch_failsWithinBudget_notCached() {
    delayMs = 2_000;
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.get(7, Duration.ofMillis(300)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    delayMs = 0;
    assertThat(client.get(7).tenantId()).isEqualTo(7);
  }

  /** F1: 같은 테넌트의 진행 중 조회를 기다리는 쪽도 자기 남은 시간까지만 기다린다. */
  @Test
  void waiterOnInflightFetch_respectsItsOwnBudget() throws Exception {
    delayMs = 1_500;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<StoreDescriptor> leader = pool.submit(() -> client.get(7));
    Thread.sleep(200); // 선행 조회가 진행 중이 되게
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.get(7, Duration.ofMillis(200)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_000));
    assertThat(leader.get().tenantId()).isEqualTo(7);
    pool.shutdown();
  }

  @Test
  void noBudgetLeft_failsWithoutRemoteCall() {
    assertThatThrownBy(() -> client.get(7, Duration.ZERO))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(hits.get()).isZero();
  }

  /** F7: 무효화하면 TTL 안이어도 다음 조회가 api 에서 새로 받는다(비밀번호 교체 반영). */
  @Test
  void invalidate_forcesRefetchWithinTtl() {
    client.get(7);
    client.get(7);
    assertThat(hits.get()).isEqualTo(1);
    client.invalidate(7);
    client.get(7);
    assertThat(hits.get()).isEqualTo(2);
  }

  @Test
  void toString_masksPassword() {
    StoreDescriptor d = client.get(7);
    assertThat(d.toString()).doesNotContain(PASSWORD).contains("password=****");
  }

  // ---- WD-263: 강제 갱신(refresh)·세대 ----

  /** 슬롯 계정 S2 가 생긴 뒤의 기술자 응답(그 전 응답은 {@link #TIBERO_JSON} — 슬롯 없음). */
  private static final String WITH_SLOT_JSON =
      TIBERO_JSON.replace(
          "\"extra\":1",
          "\"slots\":[{\"slot\":2,\"username\":\"GENIA_T7_S2\",\"password\":\"s\"}]");

  private static final Duration REFRESH_WAIT = Duration.ofSeconds(3);

  /** 다음 기술자 요청 하나를 붙잡아 둘 문을 건다. */
  private CountDownLatch holdNextRequest() {
    CountDownLatch gate = new CountDownLatch(1);
    allGates.add(gate);
    gates.add(gate);
    return gate;
  }

  /** 기술자 요청 하나가 서버에 도착할 때까지 기다린다(원격 조회가 진행 중이 됨). */
  private void awaitRequestEntered() throws InterruptedException {
    assertThat(entered.tryAcquire(5, TimeUnit.SECONDS)).as("요청 도착").isTrue();
  }

  /** 별도 스레드에서 시작한 작업 — 결과(task)와, 대기 상태를 볼 스레드(thread). */
  private record Started<T>(Thread thread, FutureTask<T> task) {}

  /** 별도 스레드에서 실행한다 — 상태(대기 중인지)를 볼 수 있게 스레드를 직접 만든다. */
  private static <T> Started<T> startThread(Callable<T> work) {
    FutureTask<T> task = new FutureTask<>(work);
    Thread t = new Thread(task);
    t.start();
    return new Started<>(t, task);
  }

  /** 스레드들이 모두 진행 중 조회의 future 를 기다리며 멈출 때까지(TIMED_WAITING) 기다린다 — 문을 열기 전에 합류를 확정한다. */
  private static void awaitParked(List<Thread> threads) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    for (Thread t : threads) {
      while (t.getState() != Thread.State.TIMED_WAITING) {
        assertThat(System.nanoTime()).as("합류 대기: " + t.getState()).isLessThan(until);
        Thread.sleep(2);
      }
    }
  }

  /**
   * 무효화 전에 시작된 진행 중 조회에는 refresh 가 합류하지 않는다 — 합류하면 옛 값(슬롯 없음)을 받는다. 옛 조회는 문에 막혀 있으므로 합류했다면 refresh
   * 는 시간 초과로 끝난다. 옛 조회가 늦게 끝나도 refresh 가 넣은 새 값을 덮지 않는다.
   */
  @Test
  void refresh_doesNotJoinInflightStartedBeforeInvalidate() throws Exception {
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> old = startThread(() -> client.get(7)).task();
    awaitRequestEntered();

    client.invalidate(7);
    body.set(WITH_SLOT_JSON);
    StoreDescriptor refreshed = client.refresh(7, REFRESH_WAIT);

    assertThat(refreshed.slot(2)).as("무효화 이후 시작한 조회의 결과").isNotNull();
    assertThat(hits.get()).isEqualTo(2);

    gate.countDown();
    assertThat(old.get(5, TimeUnit.SECONDS).slot(2)).as("옛 조회의 호출자는 옛 값").isNull();
    assertThat(client.get(7).slot(2)).as("옛 조회가 캐시를 덮지 않았다").isNotNull();
    assertThat(hits.get()).isEqualTo(2);
  }

  /**
   * 무효화 전에 시작된 <b>강제 갱신</b>에도 합류하지 않는다(세대가 달라졌으므로) — 합류하면 문에 막힌 옛 강제 갱신을 기다리다 시간 초과로 끝난다. 옛 강제 갱신이
   * 늦게 끝나도 캐시를 덮지 않는다.
   */
  @Test
  void refresh_doesNotJoinRefreshStartedBeforeInvalidate() throws Exception {
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> old = startThread(() -> client.refresh(7, REFRESH_WAIT)).task();
    awaitRequestEntered();

    client.invalidate(7);
    body.set(WITH_SLOT_JSON);
    StoreDescriptor refreshed = client.refresh(7, REFRESH_WAIT);

    assertThat(refreshed.slot(2)).as("무효화 이후 시작한 조회의 결과").isNotNull();
    assertThat(hits.get()).isEqualTo(2);

    gate.countDown();
    assertThat(old.get(5, TimeUnit.SECONDS).slot(2)).as("옛 강제 갱신의 호출자는 옛 값").isNull();
    assertThat(client.get(7).slot(2)).as("옛 강제 갱신이 캐시를 덮지 않았다").isNotNull();
    assertThat(hits.get()).isEqualTo(2);
  }

  /** 무효화 전에 시작돼 무효화 뒤에 끝난 조회는 캐시에 넣지 않는다 — 다음 조회가 새로 받는다(LeasedExecution 의 무효화도 같다). */
  @Test
  void staleInflight_doesNotOverwriteCacheAfterInvalidate() throws Exception {
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> old = startThread(() -> client.get(7)).task();
    awaitRequestEntered();

    client.invalidate(7);
    body.set(WITH_SLOT_JSON);
    gate.countDown();

    assertThat(old.get(5, TimeUnit.SECONDS).slot(2)).isNull();
    assertThat(client.get(7).slot(2)).as("옛 결과가 캐시에 남지 않아 새로 받았다").isNotNull();
    assertThat(hits.get()).isEqualTo(2);
  }

  /**
   * 같은 테넌트의 동시 refresh 는 원격 조회 하나로 묶는다 — 첫 refresh 의 조회가 진행 중일 때 온 refresh 들은 그 조회에 합류한다(각자 무효화해서
   * 서로의 조회를 버리지 않는다). 결과는 캐시에도 들어간다.
   */
  @Test
  void concurrentRefresh_singleRemoteFetch() throws Exception {
    assertThat(client.get(7).slot(2)).isNull(); // 슬롯 없는 옛 기술자가 캐시돼 있다.
    body.set(WITH_SLOT_JSON);
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> leader = startThread(() -> client.refresh(7, REFRESH_WAIT)).task();
    awaitRequestEntered();

    List<Thread> joiners = new ArrayList<>();
    List<FutureTask<StoreDescriptor>> joined = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Started<StoreDescriptor> joiner = startThread(() -> client.refresh(7, REFRESH_WAIT));
      joiners.add(joiner.thread());
      joined.add(joiner.task());
    }
    awaitParked(joiners);
    gate.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).slot(2)).isNotNull();
    for (FutureTask<StoreDescriptor> f : joined) {
      assertThat(f.get(5, TimeUnit.SECONDS).slot(2)).isNotNull();
    }
    assertThat(hits.get()).as("처음 get 1 + refresh 묶음 1").isEqualTo(2);
    assertThat(client.get(7).slot(2)).isNotNull();
    assertThat(hits.get()).as("refresh 결과가 캐시됐다").isEqualTo(2);
  }

  /** refresh 는 신선한 캐시가 있어도 새로 받는다(옛 기술자가 캐시돼 있을 때 쓰는 길이다). */
  @Test
  void refresh_bypassesFreshCache_andCachesResult() {
    assertThat(client.get(7).slot(2)).isNull();
    body.set(WITH_SLOT_JSON);

    assertThat(client.refresh(7, REFRESH_WAIT).slot(2)).isNotNull();
    assertThat(client.get(7).slot(2)).isNotNull();
    assertThat(hits.get()).isEqualTo(2);
  }

  /** 진행 중인 refresh 에 get 도 합류한다 — refresh 가 옛 조회 표시를 갈아 끼워 다음 get 들이 새 조회를 같이 기다린다. */
  @Test
  void get_joinsInflightRefresh() throws Exception {
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> leader = startThread(() -> client.refresh(7, REFRESH_WAIT)).task();
    awaitRequestEntered();
    Started<StoreDescriptor> getter = startThread(() -> client.get(7, REFRESH_WAIT));
    awaitParked(List.of(getter.thread()));
    gate.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).tenantId()).isEqualTo(7);
    assertThat(getter.task().get(5, TimeUnit.SECONDS).tenantId()).isEqualTo(7);
    assertThat(hits.get()).isEqualTo(1);
  }

  /**
   * refresh 의 원격 조회가 실패하면 맡은 쪽과 합류한 쪽 모두 예외로 깨어나고(영원히 막히지 않음), 진행 표시가 남지 않아 다음 refresh 는 새로 조회한다.
   * 실패는 캐시하지 않는다.
   */
  @Test
  void refreshFailure_releasesWaiters_noStuckInflight() throws Exception {
    status.set(503);
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> leader = startThread(() -> client.refresh(7, REFRESH_WAIT)).task();
    awaitRequestEntered();
    Started<StoreDescriptor> joiner = startThread(() -> client.refresh(7, REFRESH_WAIT));
    awaitParked(List.of(joiner.thread()));
    gate.countDown();

    for (FutureTask<StoreDescriptor> f : List.of(leader, joiner.task())) {
      assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(StoreUnavailableException.class)
          .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(503).");
    }
    assertThat(hits.get()).isEqualTo(1);

    status.set(200);
    assertThat(client.refresh(7, REFRESH_WAIT).tenantId()).isEqualTo(7);
    assertThat(hits.get()).as("멈춘 진행 표시 없이 새로 조회").isEqualTo(2);
  }

  /** refresh 의 조회가 진행 중일 때 무효화가 오면(로그인 실패 등) 그 결과는 호출자에게만 주고 캐시에 넣지 않는다. */
  @Test
  void invalidateDuringRefresh_resultNotCached() throws Exception {
    CountDownLatch gate = holdNextRequest();
    FutureTask<StoreDescriptor> leader = startThread(() -> client.refresh(7, REFRESH_WAIT)).task();
    awaitRequestEntered();
    client.invalidate(7);
    gate.countDown();

    assertThat(leader.get(5, TimeUnit.SECONDS).tenantId()).isEqualTo(7);
    client.get(7);
    assertThat(hits.get()).as("무효화 전에 보낸 조회 결과는 캐시되지 않았다").isEqualTo(2);
  }

  /** 남은 시간이 없으면 원격 조회 없이 시간 초과로 거부하고, 캐시도 건드리지 않는다. */
  @Test
  void refresh_noBudgetLeft_failsWithoutRemoteCallOrInvalidation() {
    client.get(7);
    assertThatThrownBy(() -> client.refresh(7, Duration.ZERO))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    client.get(7);
    assertThat(hits.get()).isEqualTo(1);
  }
}
