package com.smartfirehub.dbexecutor.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PipelineStopClient} 단위 테스트(WD-482) — api 중지 확인 엔드포인트의 경로·내부 토큰 헤더와, 비정상 응답을 예외로 올리는
 * 규칙(호출부가 fail-open 으로 처리한다)을 고정한다.
 */
class PipelineStopClientTest {

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{\"stopRequested\":true}");
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicInteger hits = new AtomicInteger();
  private PipelineStopClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/executor/tenants/",
        ex -> {
          hits.incrementAndGet();
          lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          lastPath.set(ex.getRequestURI().getPath());
          byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(status.get(), payload.length);
          ex.getResponseBody().write(payload);
          ex.close();
        });
    server.start();
    client =
        new PipelineStopClient(
            new DbExecutorProperties(
                "tok",
                "load-tok",
                null,
                // 끝 슬래시는 떼고 붙인다.
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                60,
                new DbExecutorProperties.Pool(8, 4, 2),
                false),
            new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void asksApiWithInternalToken_andReadsBoolean() {
    BooleanSupplier check = client.check(7L, 42L);

    assertThat(check.getAsBoolean()).isTrue();
    assertThat(lastPath.get())
        .isEqualTo("/internal/executor/tenants/7/pipeline-executions/42/stop-requested");
    assertThat(lastAuth.get()).isEqualTo("Internal tok");

    body.set("{\"stopRequested\":false}");
    // 부를 때마다 새로 묻는다 — 캐시하지 않는다.
    assertThat(check.getAsBoolean()).isFalse();
    assertThat(hits).hasValue(2);
  }

  @Test
  void non200_throws() {
    status.set(401);
    body.set("");

    assertThatThrownBy(() -> client.check(7L, 42L).getAsBoolean())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("401");
  }

  @Test
  void malformedOrNonBoolean_throws() {
    body.set("{\"stopRequested\":\"yes\"}");
    assertThatThrownBy(() -> client.check(7L, 42L).getAsBoolean())
        .isInstanceOf(IllegalStateException.class);

    body.set("not json");
    assertThatThrownBy(() -> client.check(7L, 42L).getAsBoolean())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void connectionRefused_throws() {
    server.stop(0);

    assertThatThrownBy(() -> client.check(7L, 42L).getAsBoolean())
        .isInstanceOf(IllegalStateException.class);
  }
}
