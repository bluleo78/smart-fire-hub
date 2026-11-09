package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.AdhocSqlErrorMessages;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WD-601 — 데이터셋 삭제와 같은 데이터셋 요청이 겹칠 때 500 대신 404 로 끝나는지 HTTP 왕복으로 검증한다.
 *
 * <p>결함: 조회·통계·필드 추가 등은 데이터셋 존재를 확인(findById)한 뒤 별도 단계에서 데이터 테이블에 SQL 을 보낸다. 그 사이 삭제가 DROP TABLE 을
 * 커밋하면 SQL 이 「relation does not exist」(SQLState 42P01)로 실패했고, 이를 「데이터셋 없음」으로 바꾸는 곳이 없어 전역 처리기가 500
 * 「예기치 않은 오류」로 답했다.
 *
 * <p>경합 재현: 타이밍에 기대지 않도록 카탈로그(dataset 행)는 남긴 채 물리 테이블만 직접 DROP 한다 — 「존재 확인은 통과했는데 테이블은 이미 없다」는 경합
 * 순간과 같은 상태다. 사용자 SQL(/query)의 없는 테이블 안내는 그대로여야 한다(회귀).
 */
@AutoConfigureMockMvc
class DatasetTableGoneTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;
  private String unique;
  private final List<Long> datasets = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    unique = String.valueOf(System.nanoTime());
    editor = fx.createUser("wd601-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    // 삭제 경로는 DROP TABLE IF EXISTS 라 물리 테이블을 먼저 지운 데이터셋도 카탈로그까지 정리된다.
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token()));
    }
    fx.deleteUser(editor);
  }

  /** 데이터 조회(GET /data) — 테이블이 사라졌으면 404 + 데이터셋 없음 문구. */
  @Test
  void getData_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_data_");
    assertDatasetGone(perform(get(BASE + "/" + id + "/data")));
  }

  /** 통계(GET /stats) — 행 수 세기에서 42P01 이 난다. */
  @Test
  void getStats_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_stats_");
    assertDatasetGone(perform(get(BASE + "/" + id + "/stats")));
  }

  /** 행 수(GET /data/count). */
  @Test
  void getRowCount_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_count_");
    assertDatasetGone(perform(get(BASE + "/" + id + "/data/count")));
  }

  /** 단건 행 조회(GET /data/rows/{rowId}). */
  @Test
  void getRow_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_getrow_");
    assertDatasetGone(perform(get(BASE + "/" + id + "/data/rows/1")));
  }

  /** 행 추가(POST /data/rows) — 쓰기 잠금 뒤 INSERT 에서 42P01 이 나도 404 다(잠금으로 막히지 않는 경로의 방어). */
  @Test
  void addRow_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_addrow_");
    assertDatasetGone(
        perform(jsonPost(BASE + "/" + id + "/data/rows", "{\"data\":{\"title\":\"x\"}}")));
  }

  /** 행 일괄 추가(POST /data/rows/batch). */
  @Test
  void addRowsBatch_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_batch_");
    assertDatasetGone(
        perform(jsonPost(BASE + "/" + id + "/data/rows/batch", "{\"rows\":[{\"title\":\"x\"}]}")));
  }

  /** 행 수정(PUT /data/rows/{rowId}). */
  @Test
  void updateRow_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_updrow_");
    assertDatasetGone(
        perform(
            put(BASE + "/" + id + "/data/rows/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":{\"title\":\"y\"}}")));
  }

  /** 행 삭제(POST /data/delete). */
  @Test
  void deleteRows_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_delrow_");
    assertDatasetGone(perform(jsonPost(BASE + "/" + id + "/data/delete", "{\"rowIds\":[1]}")));
  }

  /** 필드 추가(POST /columns) — 행 수 세기에서 42P01 이 난다. */
  @Test
  void addColumn_tableDropped_returns404() throws Exception {
    long id = createDatasetThenDropTable("wd601_addcol_");
    assertDatasetGone(
        perform(
            jsonPost(
                BASE + "/" + id + "/columns",
                "{\"columnName\":\"b\",\"dataType\":\"TEXT\",\"isNullable\":true}")));
  }

  /**
   * 회귀: 사용자 SQL(/query)이 카탈로그에 없는 테이블을 참조하면 예전처럼 보안 판정의 403(존재 은닉 단일 문구)이다 — 「데이터셋 없음」 404 로 바뀌지
   * 않는다(수정 전 실측 동작 그대로).
   */
  @Test
  void query_unknownTableInUserSql_keeps403AccessDenied() throws Exception {
    long id = createDataset("wd601_query_");
    MvcResult r =
        perform(
            jsonPost(
                BASE + "/" + id + "/query",
                "{\"sql\":\"SELECT * FROM non_existent_table_wd601\"}"));
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
    assertThat(json(r).get("message").asText())
        .isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
  }

  /**
   * 회귀: 경로의 데이터셋 테이블이 사라진 뒤 그 테이블을 /query 로 읽으면 예전처럼 사용자 SQL 오류(200 + error, 첫 줄 WD-571 안내)다 — 이
   * 경로의 42P01 은 「데이터셋 없음」 404 로 바꾸지 않는다(사용자 SQL 실행 경로는 번역 범위 밖).
   */
  @Test
  void query_ownTableDropped_keeps200WithGuide() throws Exception {
    String table = "wd601_qdrop_" + unique;
    long id = createDataset(table, true);
    MvcResult r =
        perform(jsonPost(BASE + "/" + id + "/query", "{\"sql\":\"SELECT * FROM " + table + "\"}"));
    assertUserSqlUndefinedTable(r);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  private void assertUserSqlUndefinedTable(MvcResult r) throws Exception {
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    String error = json(r).get("error").asText();
    assertThat(error.split("\n", 2)[0]).isEqualTo(AdhocSqlErrorMessages.UNDEFINED_TABLE_GUIDE);
  }

  /** 404 이고 message 가 데이터셋 없음 기본 문구인지 본다. */
  private void assertDatasetGone(MvcResult r) throws Exception {
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(404);
    assertThat(json(r).get("message").asText()).isEqualTo(DatasetNotFoundException.MESSAGE);
  }

  private long createDatasetThenDropTable(String prefix) throws Exception {
    return createDataset(prefix + unique, true);
  }

  private long createDataset(String prefix) throws Exception {
    return createDataset(prefix + unique, false);
  }

  /** TEXT 필드 title 하나를 가진 데이터셋을 만들고, dropTable 이면 카탈로그는 둔 채 물리 테이블만 지운다(경합 순간 재현). */
  private long createDataset(String tableName, boolean dropTable) throws Exception {
    String body =
        "{\"name\":\"WD601 "
            + tableName
            + "\",\"tableName\":\""
            + tableName
            + "\",\"columns\":[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true}]}";
    MvcResult r = perform(jsonPost(BASE, body));
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    if (dropTable) {
      inTenantFixture(
          () ->
              dataDsl.execute(
                  "DROP TABLE "
                      + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID)
                      + ".\""
                      + tableName
                      + "\""));
    }
    return id;
  }

  private MockHttpServletRequestBuilder jsonPost(String url, String body) {
    return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.header("Authorization", token())).andReturn();
  }

  private String body(MvcResult r) throws Exception {
    return "응답 본문: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
