package com.smartfirehub.dbexecutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨텍스트 기동 스모크 + 인증 우회 회귀: 실제 Tomcat 에서 {@code /execute/**} 더미 컨트롤러가 토큰 없이는, 변형 경로(퍼센트 인코딩·세미콜론·이중
 * 슬래시·대소문자· {@code ..})로도 도달되지 않아야 한다. MockHttpServletRequest 로는 Spring MVC 의 경로 정규화를 재현할 수 없어 실제
 * 서버로 검증한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.internal-token=smoke-token"})
class DbExecutorApplicationTest {

  static final AtomicInteger REACHED = new AtomicInteger();

  /** 보호 대상 경로를 흉내 내는 더미 핸들러(도달하면 카운트). */
  @TestConfiguration
  static class Dummy {
    @RestController
    static class DummyController {
      @RequestMapping("/execute/x")
      String x() {
        REACHED.incrementAndGet();
        return "reached";
      }
    }
  }

  @LocalServerPort int port;

  @BeforeEach
  void reset() {
    REACHED.set(0);
  }

  private HttpResponse<String> send(String method, String rawPath, String auth) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (auth != null) {
      b.header("Authorization", auth);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void execute_withToken_reachesHandler() throws Exception {
    HttpResponse<String> res = send("POST", "/execute/x", "Internal smoke-token");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(REACHED.get()).isEqualTo(1);
  }

  @Test
  void execute_withoutToken_401() throws Exception {
    assertThat(send("POST", "/execute/x", null).statusCode()).isEqualTo(401);
    assertThat(REACHED.get()).isZero();
  }

  @Test
  void bypassAttempts_neverReachHandler() throws Exception {
    String[] paths = {
      "/%65xecute/x", "/execute;x/x", "//execute/x", "/EXECUTE/x", "/execute/../execute/x"
    };
    for (String path : paths) {
      HttpResponse<String> res = send("POST", path, null);
      assertThat(res.statusCode()).as(path).isIn(400, 401, 404);
      assertThat(REACHED.get()).as(path).isZero();
    }
  }

  @Test
  void unknownPath_withoutToken_401() throws Exception {
    assertThat(send("GET", "/nope", null).statusCode()).isEqualTo(401);
    assertThat(send("GET", "/actuator/info", null).statusCode()).isEqualTo(401);
  }

  @Test
  void health_reachableWithoutToken_andReportsDriver() throws Exception {
    HttpResponse<String> res = send("GET", "/actuator/health", null);
    // 드라이버 jar 가 없으면 503(DOWN), 있으면 200(UP) — 어느 쪽이든 인증 없이 응답해야 한다.
    assertThat(res.statusCode()).isIn(200, 503);
    assertThat(res.body()).contains("tiberoDriver");
  }
}
