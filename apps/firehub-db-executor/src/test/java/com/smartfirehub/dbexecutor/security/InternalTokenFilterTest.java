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
              "secret", "http://x", 60, new DbExecutorProperties.Pool(8, 4, 2)));

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
        new DbExecutorProperties("secret", "http://x", 60, new DbExecutorProperties.Pool(8, 4, 2));
    assertThat(p.toString()).doesNotContain("secret").contains("internalToken=****");
  }

  @Test
  void blankProperty_failsFast() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            new DbExecutorProperties(" ", "http://x", 60, new DbExecutorProperties.Pool(8, 4, 2)));
  }
}
