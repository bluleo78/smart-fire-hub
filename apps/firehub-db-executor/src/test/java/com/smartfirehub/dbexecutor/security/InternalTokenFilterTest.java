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
              "script-key-0123456789abcdef-0123456",
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
            "script-key-0123456789abcdef-0123456",
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

  /**
   * 최종 리뷰 I2: {@code .env.example} 같은 자리표시자({@code change-me…})를 그대로 옮긴 값도 알려진 기본값처럼 거부한다 — 누구나 아는
   * 값이라 사용자 코드가 닿는 db-executor 에서 다른 테넌트 권한으로 이어진다. 허용 플래그(로컬·테스트)는 기존 기본값과 같은 규칙으로 통과시킨다.
   */
  @Test
  void changeMePlaceholders_refusedUnlessAllowed() {
    var pool = new DbExecutorProperties.Pool(8, 4, 2);
    for (String[] pair :
        new String[][] {
          {"change-me-db-executor-internal-token", "x-load"},
          {"x-internal", "change-me-db-executor-load-token"},
          {"change-me", null}
        }) {
      var ex =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class,
              () -> new DbExecutorProperties(pair[0], pair[1], null, "http://x", 60, pool, false));
      assertThat(ex.getMessage()).contains("개발 기본값");
      new DbExecutorProperties(pair[0], pair[1], null, "http://x", 60, pool, true);
    }
  }

  /**
   * 최종 리뷰 M2: 서명 키가 있는데 32바이트(UTF-8)보다 짧으면 기동을 막는다 — 그대로 두면 api 는 발급 때 실패하고 db-executor 는 조용히 401 만
   * 내서 원인을 알기 어렵다. 비운 키는 허용한다(스크립트 자격을 열지 않고 언제나 401, fail-closed).
   */
  @Test
  void scriptTokenKey_setButShorterThan32Bytes_failsFast_blankAllowed() {
    var pool = new DbExecutorProperties.Pool(8, 4, 2);
    String key31 = "k".repeat(ScriptToken.MIN_KEY_BYTES - 1);
    var ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new DbExecutorProperties("secret", "load", key31, "http://x", 60, pool, false));
    assertThat(ex.getMessage()).contains("32").doesNotContain(key31);
    // 허용 플래그와 무관하다 — 짧은 키는 로컬에서도 검증이 늘 실패하는 설정이다.
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> new DbExecutorProperties("secret", "load", key31, "http://x", 60, pool, true));
    // 정확히 32바이트, 바이트로 세는 다바이트 문자(한글 11자 = 33바이트), 비움은 통과한다.
    new DbExecutorProperties("secret", "load", "k".repeat(32), "http://x", 60, pool, false);
    new DbExecutorProperties("secret", "load", "가".repeat(11), "http://x", 60, pool, false);
    for (String blank : new String[] {null, "", "   "}) {
      new DbExecutorProperties("secret", "load", blank, "http://x", 60, pool, false);
    }
  }

  /** 최종 리뷰 I2·M2: {@code change-me} 자리표시자 서명 키(예시 길이 38바이트라 길이 검사로는 못 거른다)도 기본값처럼 거부한다. */
  @Test
  void scriptTokenKey_changeMePlaceholder_refusedUnlessAllowed() {
    var pool = new DbExecutorProperties.Pool(8, 4, 2);
    String placeholder = "change-me-db-executor-script-token-key";
    var ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                new DbExecutorProperties(
                    "secret", "load", placeholder, "http://x", 60, pool, false));
    assertThat(ex.getMessage()).contains("개발 기본값");
    new DbExecutorProperties("secret", "load", placeholder, "http://x", 60, pool, true);
  }

  @Test
  void internalToken_passesEveryEndpoint() throws Exception {
    for (String uri :
        new String[] {"/execute/sql", "/execute/query", "/execute/probe", "/execute/load"}) {
      assertThat(call(uri, "Internal secret").getStatus()).as(uri).isEqualTo(200);
    }
  }

  // ---- 스크립트 토큰(WD-78, Ruling 16) ----

  /** 공유 벡터의 첫 토큰: 키 test-script-key-0123456789abcdef-32b, 테넌트 7, 슬롯 3, 세대 2, 만료 1700000000. */
  private static final String SCRIPT_TOKEN =
      "v1.7.3.2.1700000000.RH248H7oXbuOkEw2d2Hswv3q3nU2k5UFS4g_cu5I69k";

  /** 만료 전 시각으로 고정한 시계를 쓰는 필터(스크립트 키는 벡터의 키). */
  private static InternalTokenFilter scriptFilter(String scriptKey, long nowSeconds) {
    return new InternalTokenFilter(
        new DbExecutorProperties(
            "secret",
            "load-secret",
            scriptKey,
            "http://x",
            60,
            new DbExecutorProperties.Pool(8, 4, 2),
            false),
        () -> nowSeconds);
  }

  private static MockHttpServletRequest scriptRequest(String method, String uri, String token) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
    req.setRequestURI(uri);
    req.addHeader("Authorization", "Script " + token);
    return req;
  }

  @Test
  void scriptToken_onExactPostQuery_passesWithVerifiedClaims() throws Exception {
    MockHttpServletRequest req = scriptRequest("POST", "/execute/query", SCRIPT_TOKEN);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    scriptFilter("test-script-key-0123456789abcdef-32b", 1_699_999_000L).doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).as("다음 필터로 넘어갔다").isNotNull();
    ScriptToken.Claims claims = (ScriptToken.Claims) req.getAttribute(ScriptToken.ATTRIBUTE);
    assertThat(claims.tenantId()).isEqualTo(7);
    assertThat(claims.slot()).isEqualTo(3);
    assertThat(claims.epoch()).isEqualTo(2);
  }

  /** 스크립트 자격은 POST /execute/query 한 곳뿐이다(Review Focus 1) — 다른 창구·메서드·변형 경로는 모두 401. */
  @Test
  void scriptToken_otherEndpointsOrMethods_401() throws Exception {
    InternalTokenFilter f = scriptFilter("test-script-key-0123456789abcdef-32b", 1_699_999_000L);
    String[][] cases = {
      {"POST", "/execute/sql"},
      {"POST", "/execute/load"},
      {"POST", "/execute/probe"},
      {"POST", "/actuator/env"},
      {"POST", "/execute/query/"},
      {"POST", "/execute/query;x"},
      {"POST", "/%65xecute/query"},
      {"GET", "/execute/query"},
      {"PUT", "/execute/query"},
    };
    for (String[] c : cases) {
      MockHttpServletRequest req = scriptRequest(c[0], c[1], SCRIPT_TOKEN);
      MockHttpServletResponse res = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();
      f.doFilter(req, res, chain);
      assertThat(res.getStatus()).as(c[0] + " " + c[1]).isEqualTo(401);
      assertThat(chain.getRequest()).as(c[0] + " " + c[1]).isNull();
      assertThat(req.getAttribute(ScriptToken.ATTRIBUTE)).isNull();
    }
  }

  @Test
  void scriptToken_expiredTamperedOrEmptyKey_401() throws Exception {
    String[] tokens = {
      SCRIPT_TOKEN.replace("v1.7.", "v1.8."), // 다른 테넌트
      SCRIPT_TOKEN.replace("v1.7.3.", "v1.7.4."), // 다른 슬롯
      "garbage",
      "",
    };
    for (String t : tokens) {
      MockHttpServletResponse res = new MockHttpServletResponse();
      scriptFilter("test-script-key-0123456789abcdef-32b", 1_699_999_000L)
          .doFilter(scriptRequest("POST", "/execute/query", t), res, new MockFilterChain());
      assertThat(res.getStatus()).as(t).isEqualTo(401);
    }
    // 만료(현재 시각 = exp)
    MockHttpServletResponse expired = new MockHttpServletResponse();
    scriptFilter("test-script-key-0123456789abcdef-32b", 1_700_000_000L)
        .doFilter(
            scriptRequest("POST", "/execute/query", SCRIPT_TOKEN), expired, new MockFilterChain());
    assertThat(expired.getStatus()).isEqualTo(401);
    // 키가 비면 스크립트 스킴 자체를 끈다.
    for (String key : new String[] {"", "  ", null}) {
      MockHttpServletResponse res = new MockHttpServletResponse();
      scriptFilter(key, 1_699_999_000L)
          .doFilter(
              scriptRequest("POST", "/execute/query", SCRIPT_TOKEN), res, new MockFilterChain());
      assertThat(res.getStatus()).as("key=" + key).isEqualTo(401);
    }
  }

  /** 내부·적재 토큰 값을 Script 스킴으로 보내도 스크립트 자격이 되지 않는다. */
  @Test
  void scriptScheme_withInternalOrLoadTokenValue_401() throws Exception {
    InternalTokenFilter f = scriptFilter("test-script-key-0123456789abcdef-32b", 1_699_999_000L);
    for (String t :
        new String[] {"secret", "load-secret", "test-script-key-0123456789abcdef-32b"}) {
      MockHttpServletResponse res = new MockHttpServletResponse();
      f.doFilter(scriptRequest("POST", "/execute/query", t), res, new MockFilterChain());
      assertThat(res.getStatus()).as(t).isEqualTo(401);
    }
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
