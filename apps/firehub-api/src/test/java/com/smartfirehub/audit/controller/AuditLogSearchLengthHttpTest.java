package com.smartfirehub.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * WD-1111: 실제 Tomcat 을 거치는 HTTP 시험. 한글 검색어 1,000자는 URL 인코딩하면 약 9KB 라 Tomcat 기본 요청 헤더 한도(8KB)에 걸려 앱
 * 예외 처리를 거치지 않은 HTML 400 이 왔다. 인증 없이 보내 401 JSON 이 나오면 Tomcat 을 통과한 것이다.
 */
class AuditLogSearchLengthHttpTest extends IntegrationTestBase {

  @LocalServerPort private int port;

  private HttpResponse<String> get(String search) throws Exception {
    String q = URLEncoder.encode(search, StandardCharsets.UTF_8);
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/admin/audit-logs?search=" + q))
            .GET()
            .build();
    return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
  }

  /** 상한 길이 한글 검색어가 Tomcat 한도에 걸리지 않고 앱(보안 필터 401 JSON)까지 도달한다. */
  @Test
  void koreanMaxLengthSearch_passesTomcat() throws Exception {
    HttpResponse<String> res = get("가".repeat(1000));
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
  }

  /** 한도를 올렸을 뿐 무제한이 아니다 — 40,000자 요청줄은 여전히 Tomcat 이 400 으로 거절한다. */
  @Test
  void oversized32kRequest_rejectedByTomcat() throws Exception {
    assertThat(get("a".repeat(40_000)).statusCode()).isEqualTo(400);
  }
}
