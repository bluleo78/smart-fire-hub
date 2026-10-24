package com.smartfirehub.dbexecutor.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 내부 토큰 필터: /execute/** 만 보호하고 상수 시간 비교 규칙을 따른다. */
class InternalTokenFilterTest {

  private final InternalTokenFilter filter =
      new InternalTokenFilter(
          new DbExecutorProperties(
              "secret",
              "load-secret",
              "script-key",
              "http://x",
              60,
              new DbExecutorProperties.Pool(8, 4, 2),
              false));

  private MockHttpServletResponse call(String uri, String authorization) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
    req.setRequestURI(uri);
    if (authorization != null) {
      req.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    return res;
  }

  @Test
  void missingHeader_401() throws Exception {
    MockHttpServletResponse res = call("/execute/sql", null);
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("{\"success\":false,\"error\":\"인증되지 않은 요청입니다.\"}");
  }

  @Test
  void wrongToken_401() throws Exception {
    assertThat(call("/execute/sql", "Internal nope").getStatus()).isEqualTo(401);
    assertThat(call("/execute/probe", "Bearer secret").getStatus()).isEqualTo(401);
    assertThat(call("/execute/sql", "Internal ").getStatus()).isEqualTo(401);
  }

  @Test
  void correctToken_passes() throws Exception {
    assertThat(call("/execute/sql", "Internal secret").getStatus()).isEqualTo(200);
  }

  @Test
  void health_passesWithoutToken() throws Exception {
    assertThat(call("/actuator/health", null).getStatus()).isEqualTo(200);
  }

  @Test
  void unknownPaths_requireToken() throws Exception {
    assertThat(call("/anything", null).getStatus()).isEqualTo(401);
    assertThat(call("/actuator/health/", null).getStatus()).isEqualTo(401);
    assertThat(call("/actuator/env", null).getStatus()).isEqualTo(401);
    assertThat(call("/anything", "Internal secret").getStatus()).isEqualTo(200);
  }

  @Test
  void properties_toStringMasksToken() {
    var p =
        new DbExecutorProperties(
            "secret",
            "load-secret",
            "script-key",
            "http://x",
            60,
            new DbExecutorProperties.Pool(8, 4, 2),
            false);
    assertThat(p.toString())
        .doesNotContain("secret")
        .doesNotContain("script-key")
        .contains("internalToken=****")
        .contains("loadToken=****");
  }

  @Test
  void blankProperty_failsFast() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            new DbExecutorProperties(
                " ", null, null, "http://x", 60, new DbExecutorProperties.Pool(8, 4, 2), false));
  }

  @Test
  void loadTokenEqualToInternal_failsFast() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            new DbExecutorProperties(
                "same",
                "same",
                null,
                "http://x",
                60,
                new DbExecutorProperties.Pool(8, 4, 2),
                false));
  }

  @Test
  void loadToken_onlyLoadEndpoint() throws Exception {
    assertThat(call("/execute/load", "Internal load-secret").getStatus()).isEqualTo(200);
    for (String uri : new String[] {"/execute/sql", "/execute/query", "/execute/probe"}) {
      assertThat(call(uri, "Internal load-secret").getStatus()).as(uri).isEqualTo(401);
    }
    // 변형 표기는 정확한 일치가 아니므로 거부한다.
    assertThat(call("/execute/load/", "Internal load-secret").getStatus()).isEqualTo(401);
    assertThat(call("/execute/load;x", "Internal load-secret").getStatus()).isEqualTo(401);
    assertThat(call("/actuator/env", "Internal load-secret").getStatus()).isEqualTo(401);
  }

  @Test
  void loadToken_requiresPostAndExactPath() throws Exception {
    for (String method : new String[] {"GET", "PUT", "DELETE"}) {
      MockHttpServletRequest req = new MockHttpServletRequest(method, "/execute/load");
      req.setRequestURI("/execute/load");
      req.addHeader("Authorization", "Internal load-secret");
      MockHttpServletResponse res = new MockHttpServletResponse();
      filter.doFilter(req, res, new MockFilterChain());
      assertThat(res.getStatus()).as(method).isEqualTo(401);
    }
    assertThat(call("/%65xecute/load", "Internal load-secret").getStatus()).isEqualTo(401);
    assertThat(call("/execute/load/../sql", "Internal load-secret").getStatus()).isEqualTo(401);
  }

  @Test
  void defaultDevTokens_refusedUnlessAllowed() {
    var pool = new DbExecutorProperties.Pool(8, 4, 2);
    for (String[] pair :
        new String[][] {
          {"dev-db-executor-token", "x-load"}, {"x-internal", "dev-db-executor-load-token"}
        }) {
      var ex =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class,
              () -> new DbExecutorProperties(pair[0], pair[1], null, "http://x", 60, pool, false));
      assertThat(ex.getMessage()).contains("개발 기본값");
      // 로컬·테스트 허용 플래그가 켜져 있으면 통과한다.
      new DbExecutorProperties(pair[0], pair[1], null, "http://x", 60, pool, true);
    }
  }

  @Test
  void internalToken_passesEveryEndpoint() throws Exception {
    for (String uri :
        new String[] {"/execute/sql", "/execute/query", "/execute/probe", "/execute/load"}) {
      assertThat(call(uri, "Internal secret").getStatus()).as(uri).isEqualTo(200);
    }
  }

  @Test
  void scriptScheme_reserved_401() throws Exception {
    // 스크립트 토큰은 Task 13 에서 구현 — 그 전에는 어떤 값이어도 거부한다(스크립트 키·내부 토큰 값 포함).
    assertThat(call("/execute/query", "Script anything").getStatus()).isEqualTo(401);
    assertThat(call("/execute/query", "Script secret").getStatus()).isEqualTo(401);
    assertThat(call("/execute/load", "Script load-secret").getStatus()).isEqualTo(401);
  }

  @Test
  void blankLoadToken_disablesLoadCredential() throws Exception {
    InternalTokenFilter noLoad =
        new InternalTokenFilter(
            new DbExecutorProperties(
                "secret", "", null, "http://x", 60, new DbExecutorProperties.Pool(8, 4, 2), false));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/execute/load");
    req.setRequestURI("/execute/load");
    req.addHeader("Authorization", "Internal ");
    MockHttpServletResponse res = new MockHttpServletResponse();
    noLoad.doFilter(req, res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(401);
  }
}
