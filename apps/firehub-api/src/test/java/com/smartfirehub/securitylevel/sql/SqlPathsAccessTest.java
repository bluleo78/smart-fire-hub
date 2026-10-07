package com.smartfirehub.securitylevel.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 스펙 §4.2 3행: 애드혹·데이터셋 /query·저장 쿼리 실행은 실행자 기준 판정, 스키마 조회는 visible 데이터셋만.
 *
 * <p>모든 거부 단언 옆에 "자격 있는 사용자는 같은 경로에서 행을 받는다"는 양성 대조를 둔다 — 관문이 빠지면 거부 단언이 깨지고, 경로 자체가 고장 나면 양성 대조가
 * 깨져 공허한 통과를 막는다.
 */
@AutoConfigureMockMvc
class SqlPathsAccessTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String schema;
  private long owner;
  private long pubId;
  private long secId;
  private String pubTable;
  private String secTable;
  private Long savedQueryId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("sp_owner");
    users.add(owner);
    schema = DataSchema.current();
    String m = "sp" + System.nanoTime();
    pubTable = m + "_pub";
    secTable = m + "_sec";
    // 실행 결과까지 확인하므로 물리 테이블도 만든다. 민감 테이블의 값 4242 가 응답에 나오면 누출이다.
    dsl.execute("CREATE TABLE " + schema + "." + pubTable + " (v int)");
    dsl.execute("INSERT INTO " + schema + "." + pubTable + " VALUES (1)");
    dsl.execute("CREATE TABLE " + schema + "." + secTable + " (v int)");
    dsl.execute("INSERT INTO " + schema + "." + secTable + " VALUES (4242)");
    pubId = fx.createDatasetRow(pubTable, fx.levelId("공개"), owner);
    datasets.add(pubId);
    secId = fx.createDatasetRow(secTable, fx.levelId("민감"), owner);
    datasets.add(secId);
    savedQueryId =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchOne(
                        "insert into saved_query (name, sql_text, is_shared, created_by)"
                            + " values (?, ?, true, ?) returning id",
                        m,
                        "SELECT v FROM " + secTable,
                        owner)
                    .get(0, Long.class));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("delete from saved_query where id = ?", savedQueryId));
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + pubTable);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + secTable);
  }

  /** 지정 등급 자격(역할 하나)만 가진 사용자의 Bearer 토큰. */
  private String tokenAt(String level) {
    long uid = fx.createUser("sp_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "sp_r_" + System.nanoTime(),
            fx.levelId(level),
            "dataset:read",
            "data:read",
            "data:import",
            "analytics:read",
            "analytics:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return "Bearer " + jwt.generateAccessToken(uid, "sp" + uid, DEFAULT_TEST_TENANT_ID);
  }

  private MvcResult postJson(String url, String token, String body) throws Exception {
    return mockMvc
        .perform(
            post(url)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private static String sqlBody(String sql) {
    return "{\"sql\":\"" + sql + "\",\"maxRows\":10}";
  }

  private JsonNode json(MvcResult r) throws Exception {
    return om.readTree(r.getResponse().getContentAsString());
  }

  private void assertDenied(MvcResult r, String code) throws Exception {
    assertThat(r.getResponse().getStatus()).isEqualTo(403);
    assertThat(json(r).get("code").asText()).isEqualTo(code);
    // 거부 응답 본문 어디에도 민감 값이 실리면 안 된다.
    assertThat(r.getResponse().getContentAsString()).doesNotContain("4242");
  }

  private void assertRows(MvcResult r, int expectedValue) throws Exception {
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode rows = json(r).get("rows");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("v").asInt()).isEqualTo(expectedValue);
  }

  @Test
  void datasetQuery_judgesReferencedTables_notThePathDataset() throws Exception {
    String path = "/api/v1/datasets/" + pubId + "/query";
    // 공개 자격: 경로 데이터셋(공개)은 보이지만 SQL 이 참조하는 민감 테이블은 거부.
    String low = tokenAt("공개");
    assertDenied(
        postJson(path, low, sqlBody("SELECT v FROM " + secTable)), "DATASET_SQL_ACCESS_DENIED");
    // 양성 대조 1: 같은 사용자가 공개 테이블은 읽는다.
    assertRows(postJson(path, low, sqlBody("SELECT v FROM " + pubTable)), 1);
    // 양성 대조 2: 민감 자격은 같은 SQL(민감 테이블)로 값을 받는다 — 거부가 경로 고장이 아님을 증명.
    assertRows(postJson(path, tokenAt("민감"), sqlBody("SELECT v FROM " + secTable)), 4242);
  }

  @Test
  void datasetQuery_writeDowngrade_isRejected() throws Exception {
    String path = "/api/v1/datasets/" + pubId + "/query";
    // 민감 자격이 민감 테이블을 읽어 공개 테이블에 쓰기 = 등급 하향 → 거부.
    String high = tokenAt("민감");
    assertDenied(
        postJson(path, high, sqlBody("INSERT INTO " + pubTable + " (v) SELECT v FROM " + secTable)),
        "SQL_WRITE_DOWNGRADE");
    // 거부 후 공개 테이블에 민감 값이 복사되지 않았다(부작용 없음).
    assertThat(
            dsl.fetchValue("SELECT count(*) FROM " + schema + "." + pubTable + " WHERE v = 4242"))
        .isEqualTo(0L);
    // 양성 대조: 하향이 아닌 쓰기(공개 소스 → 공개 대상)는 허용된다.
    MvcResult ok =
        postJson(path, high, sqlBody("INSERT INTO " + pubTable + " (v) SELECT v FROM " + pubTable));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(dsl.fetchValue("SELECT count(*) FROM " + schema + "." + pubTable)).isEqualTo(2L);
  }

  @Test
  void adhocAnalytics_isJudgedForExecutor() throws Exception {
    String url = "/api/v1/analytics/queries/execute";
    String low = tokenAt("공개");
    assertDenied(
        postJson(url, low, sqlBody("SELECT v FROM " + secTable)), "DATASET_SQL_ACCESS_DENIED");
    assertRows(postJson(url, low, sqlBody("SELECT v FROM " + pubTable)), 1);
    assertRows(postJson(url, tokenAt("민감"), sqlBody("SELECT v FROM " + secTable)), 4242);
  }

  @Test
  void sharedSavedQuery_isJudgedForExecutor_notOwner() throws Exception {
    String url = "/api/v1/analytics/queries/" + savedQueryId + "/execute";
    assertDenied(postJson(url, tokenAt("공개"), "{\"maxRows\":10}"), "DATASET_SQL_ACCESS_DENIED");
    assertRows(postJson(url, tokenAt("민감"), "{\"maxRows\":10}"), 4242);
  }

  @Test
  void schemaListing_showsOnlyVisibleDatasetTables() throws Exception {
    // 가시성과 무관하게 data 스키마에 존재하는 비데이터셋 테이블(스테이징) — 항상 숨겨져야 한다.
    String staging = "stg_import_sp" + System.nanoTime();
    dsl.execute("CREATE TABLE " + schema + "." + staging + " (v int)");
    try {
      List<String> low = schemaTables("/api/v1/analytics/queries/schema", tokenAt("공개"));
      assertThat(low).contains(pubTable).doesNotContain(secTable, staging);
      // 양성 대조: 민감 자격은 민감 데이터셋 테이블도 본다(필터가 전부 지우는 고장이 아님).
      List<String> high = schemaTables("/api/v1/analytics/queries/schema", tokenAt("민감"));
      assertThat(high).contains(pubTable, secTable).doesNotContain(staging);
      // datasetIds 로 숨김 데이터셋을 직접 지목해도 노출되지 않는다(양성 대조: 민감 자격은 받는다).
      String byId = "/api/v1/analytics/queries/schema?datasetIds=" + secId;
      assertThat(schemaTables(byId, tokenAt("공개"))).isEmpty();
      assertThat(schemaTables(byId, tokenAt("민감"))).containsExactly(secTable);
    } finally {
      dsl.execute("DROP TABLE IF EXISTS " + schema + "." + staging);
    }
  }

  private List<String> schemaTables(String url, String token) throws Exception {
    MvcResult r = mockMvc.perform(get(url).header("Authorization", token)).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    List<String> tables = new ArrayList<>();
    json(r).get("tables").forEach(t -> tables.add(t.get("tableName").asText()));
    return tables;
  }

  @Test
  void parseError_inAnalytics_keepsLegacy200ErrorContract() throws Exception {
    MvcResult r = postJson("/api/v1/analytics/queries/execute", tokenAt("공개"), sqlBody("SELEC 1"));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(json(r).get("error").isNull()).isFalse();
  }
}
