package com.smartfirehub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-105 — 분석 쿼리 결과 표의 NUMERIC·BIGINT 가 브라우저 double 로 깎여 보이던 결함 회귀 테스트.
 *
 * <p>A안: {@code rows} 는 차트·대시보드가 {@code typeof number} 로 쓰므로 JSON 숫자로 그대로 두고, 깎이는 칸만 원문을 {@code
 * exactValues}(행 인덱스 → {컬럼명: 원문}) 에 함께 싣는다. 단언은 Java 객체가 아니라 <b>실제 HTTP 응답 JSON</b> 으로 한다 — 필드
 * 생략(NON_EMPTY)과 숫자/문자열 구분은 직렬화 결과로만 확인된다.
 */
@AutoConfigureMockMvc
class AnalyticsExactValuesTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private AnalyticsQueryExecutionService executionService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();

  /**
   * 한 행에 깎이는 칸과 안 깎이는 칸을 섞은 SQL. 깎이는 칸 = 소수 2개·±2^53 이상 정수 3개·깎이는 원소를 가진 배열 1개. 경계 ±(2^53-1), 끝자리 0
   * 만 다른 소수, 짧은 정수, NULL, 안전한 배열, 문자열은 깎이지 않는다.
   */
  private static final String MIXED_SQL =
      "SELECT 123456789012.123456::numeric(18,6) AS dec_lossy,"
          + " -987654321098.000001::numeric(18,6) AS dec_neg,"
          + " 9007199254740993::bigint AS big,"
          + " 9007199254740992::bigint AS big_edge,"
          + " -9007199254740992::bigint AS big_neg_edge,"
          + " 9007199254740991::bigint AS safe_max,"
          + " -9007199254740991::bigint AS safe_min,"
          + " 1.500000::numeric(18,6) AS dec_ok,"
          + " 42::bigint AS small,"
          + " NULL::numeric AS nul,"
          + " ARRAY[1.5, 2]::numeric[] AS arr_ok,"
          + " ARRAY[123456789012.123456, NULL]::numeric[] AS arr_lossy,"
          + " 'x'::text AS txt";

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(
          () -> {
            dsl.execute("DELETE FROM analytics_query_run WHERE user_id = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          });
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  /** 기존 역할을 떼고 공개 등급 자격 + 지정 권한만 가진 사용자. */
  private long user(String... perms) {
    long uid = fx.createUser("aev_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("aev_r_" + System.nanoTime(), fx.levelId("공개"), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "aev", DEFAULT_TEST_TENANT_ID);
  }

  /** 애드혹 실행 엔드포인트(웹 쿼리 편집기 결과 표 경로) — 응답 JSON 트리. */
  private JsonNode executeAdhoc(long uid, String sql) throws Exception {
    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/execute")
                    .header("Authorization", token(uid))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("sql", sql, "maxRows", 100))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode root = om.readTree(json);
    assertThat(root.get("error").isNull()).as("error: %s", root.get("error")).isTrue();
    return root;
  }

  /** exactValues 한 행의 컬럼 이름 집합. */
  private static Set<String> keys(JsonNode node) {
    Set<String> names = new java.util.TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** 섞인 한 행 응답에 대해 rows 는 숫자 그대로, exactValues 는 깎이는 칸만 원문인지 확인한다. */
  private static void assertMixedRow(JsonNode root) {
    JsonNode row = root.get("rows").get(0);
    // rows 는 기존처럼 JSON 숫자 — 차트·대시보드의 typeof number 소비자가 깨지지 않는다(A안).
    for (String col :
        List.of(
            "dec_lossy",
            "dec_neg",
            "big",
            "big_edge",
            "big_neg_edge",
            "safe_max",
            "dec_ok",
            "small")) {
      assertThat(row.get(col).isNumber()).as("rows.%s 는 숫자여야 한다: %s", col, row.get(col)).isTrue();
    }
    assertThat(row.get("arr_lossy").get(0).isNumber()).isTrue();

    JsonNode exact = root.get("exactValues");
    assertThat(exact).as("exactValues 가 있어야 한다").isNotNull();
    assertThat(keys(exact)).containsExactly("0");
    JsonNode r0 = exact.get("0");
    // 정밀도를 잃는 칸만 — 경계(±(2^53-1))·1.500000·42·NULL·안전한 배열·문자열은 들어가지 않는다.
    assertThat(keys(r0))
        .containsExactlyInAnyOrder(
            "dec_lossy", "dec_neg", "big", "big_edge", "big_neg_edge", "arr_lossy");
    assertThat(r0.get("dec_lossy").asText()).isEqualTo("123456789012.123456");
    assertThat(r0.get("dec_neg").asText()).isEqualTo("-987654321098.000001");
    assertThat(r0.get("big").asText()).isEqualTo("9007199254740993");
    assertThat(r0.get("big_edge").asText()).isEqualTo("9007199254740992");
    assertThat(r0.get("big_neg_edge").asText()).isEqualTo("-9007199254740992");
    // 배열은 웹 표가 객체 칸을 그리는 JSON.stringify 모양 그대로, 숫자만 원문 자릿수.
    assertThat(r0.get("arr_lossy").asText()).isEqualTo("[123456789012.123456,null]");
    for (JsonNode v : r0) {
      assertThat(v.isTextual()).as("원문은 문자열: %s", v).isTrue();
    }
  }

  @Test
  void adhoc_rowsStayNumbers_andExactValuesHoldOnlyLossyCells() throws Exception {
    long u = user("analytics:read");
    assertMixedRow(executeAdhoc(u, MIXED_SQL));
  }

  @Test
  void adhoc_exactValuesKeyedByRowIndex() throws Exception {
    long u = user("analytics:read");
    // 가운데 행(인덱스 1)만 깎인다 — 행 인덱스가 rows 배열 순서와 맞아야 웹이 올바른 칸을 덮는다.
    JsonNode root =
        executeAdhoc(
            u,
            "SELECT CASE WHEN g = 1 THEN 123456789012.123456 ELSE 1.5 END::numeric(18,6) AS d"
                + " FROM generate_series(0, 2) g ORDER BY g");
    assertThat(root.get("rows")).hasSize(3);
    JsonNode exact = root.get("exactValues");
    assertThat(keys(exact)).containsExactly("1");
    assertThat(exact.get("1").get("d").asText()).isEqualTo("123456789012.123456");
  }

  @Test
  void adhoc_noLossyCells_omitsExactValues() throws Exception {
    long u = user("analytics:read");
    JsonNode root =
        executeAdhoc(
            u,
            "SELECT 1.5::numeric(18,6) AS a, 42::bigint AS b, NULL::numeric AS c,"
                + " ARRAY[1, 2]::bigint[] AS d, 9007199254740991::bigint AS e");
    // 깎이는 칸이 없으면 필드 자체가 빠진다 — 응답 크기가 늘지 않는다.
    assertThat(root.has("exactValues")).as("exactValues 는 생략돼야 한다: %s", root).isFalse();
    assertThat(root.get("rows").get(0).get("a").isNumber()).isTrue();
  }

  @Test
  void savedQueryExecute_carriesExactValues() throws Exception {
    long u = user("analytics:read", "analytics:write");
    String created =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries")
                    .header("Authorization", token(u))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of("name", "wd105_" + System.nanoTime(), "sqlText", MIXED_SQL))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = om.readTree(created).get("id").asLong();
    // 쿼리 목록의 "실행" 은 이 결과를 쿼리 편집기 결과 표로 넘긴다(location.state) — 같은 원문이 실려야 한다.
    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/" + id + "/execute")
                    .header("Authorization", token(u)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertMixedRow(om.readTree(json));
  }

  @Test
  void aiAgentCall_doesNotCarryExactValues() throws Exception {
    // ai-agent MCP 대행 호출(내부 토큰 + 대행 사용자·테넌트)은 같은 엔드포인트지만 결과 표가 아니다 — 응답을 통째로 LLM 에 넘기고
    // rows 만 잘라 내므로 원문을 싣지 않는다(실행 기록 attachRun 과 같은 기준).
    long u = user("analytics:read");
    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/execute")
                    .header("Authorization", "Internal test-internal-token")
                    .header(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(u))
                    .header(
                        InternalCallHeaders.ON_BEHALF_OF_TENANT,
                        String.valueOf(DEFAULT_TEST_TENANT_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("sql", MIXED_SQL, "maxRows", 100))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode root = om.readTree(json);
    assertThat(root.get("error").isNull()).as("error: %s", root.get("error")).isTrue();
    assertThat(root.get("rows").get(0).get("dec_lossy").isNumber()).isTrue();
    assertThat(root.has("exactValues")).as("AI 응답에는 원문이 없어야 한다: %s", root).isFalse();
  }

  @Test
  void sharedExecutionService_doesNotFillExactValues() throws Exception {
    // 차트 데이터·대시보드는 이 실행 서비스 결과를 그대로 쓴다(GuardedSqlExecutor.executeJudgedAnalytics) — 결과 표 전용 원문이
    // 섞여 들어가지 않도록 채우는 곳은 웹 결과 표 엔드포인트 두 곳뿐이어야 한다.
    var res = executionService.execute(MIXED_SQL, 10, true);
    assertThat(res.error()).isNull();
    JsonNode root = om.readTree(om.writeValueAsString(res));
    assertThat(root.has("exactValues")).isFalse();
    assertThat(root.get("rows").get(0).get("dec_lossy").isNumber()).isTrue();
  }
}
