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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>(TIBERO_JSON);
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastUri = new AtomicReference<>();
  private volatile long delayMs = 0;
  private final AtomicLong clock = new AtomicLong(0);
  private StoreDescriptorClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(16));
    server.createContext(
        "/internal/executor/tenants/",
        ex -> {
          hits.incrementAndGet();
          lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          lastUri.set(ex.getRequestURI().toString());
          try {
            if (delayMs > 0) {
              Thread.sleep(delayMs);
            }
          } catch (InterruptedException ignored) {
          }
          byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status.get(), b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    client =
        new StoreDescriptorClient(
            new DbExecutorProperties(
                "tok",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                60,
                new DbExecutorProperties.Pool(8, 4, 2)),
            new ObjectMapper(),
            clock::get);
  }

  @AfterEach
  void stop() {
    server.stop(0);
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

    assertThat(lastUri.get()).isEqualTo("/internal/executor/tenants/7/data-store?include=ro");
    assertThat(d.ro().username()).isEqualTo("GENIA_T7_RO");
    assertThat(d.ro().password()).isEqualTo(roPassword);
    assertThat(d.toString()).doesNotContain(roPassword).doesNotContain(PASSWORD);
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
    List<Future<StoreDescriptor>> fs = new java.util.ArrayList<>();
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
                "http://127.0.0.1:" + server.getAddress().getPort(),
                60,
                new DbExecutorProperties.Pool(8, 4, 2)),
            boom,
            clock::get);
    delayMs = 300;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Object>> fs = new java.util.ArrayList<>();
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
      assertThat(f.get(5, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(Error.class);
    }
    pool.shutdown();
  }

  /** F1: 원격 조회는 호출자의 남은 시간까지만 기다린다 — 실패는 캐시하지 않는다. */
  @Test
  void slowFetch_failsWithinBudget_notCached() {
    delayMs = 2_000;
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.get(7, java.time.Duration.ofMillis(300)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
        .isLessThan(java.time.Duration.ofMillis(1_500));
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
    assertThatThrownBy(() -> client.get(7, java.time.Duration.ofMillis(200)))
        .isInstanceOf(StoreUnavailableException.class)
        .hasMessage("테넌트 저장소 정보를 가져오지 못했습니다(시간 초과).");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
        .isLessThan(java.time.Duration.ofMillis(1_000));
    assertThat(leader.get().tenantId()).isEqualTo(7);
    pool.shutdown();
  }

  @Test
  void noBudgetLeft_failsWithoutRemoteCall() {
    assertThatThrownBy(() -> client.get(7, java.time.Duration.ZERO))
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
}
