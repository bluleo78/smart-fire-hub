package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
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

/**
 * 컬럼 옵션(isNullable·isIndexed·isPrimaryKey) 누락 시 기본값(WD-698) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: {@code AddColumnRequest}·{@code DatasetColumnRequest} 의 세 필드가 원시 boolean 이라 JSON 에서 빠지면
 * false 가 됐다. isNullable 을 빼면 NOT NULL 컬럼이 만들어지고, 행이 있는 데이터셋에는 필드 추가 자체가 거부됐다(웹은 늘 보내지만 API 직접·AI
 * 도구 호출에서 생긴다). 이제 누락·JSON null 은 SQL 기본과 같게 채운다 — isNullable=true(단 기본 키는 NOT NULL),
 * isIndexed=false, isPrimaryKey=false. 명시한 값은 그대로 따른다.
 *
 * <p>MockMvc 로 하는 이유: 핵심이 「JSON 에서 필드가 빠졌을 때 역직렬화 결과」라 서비스를 직접 부르면 확인되지 않는다. 카탈로그(응답·상세)와 실제
 * DDL({@code information_schema.columns.is_nullable}) 둘 다 본다. 클래스 레벨 {@code @Transactional} 을 쓰지
 * 않으므로 정리는 {@link #tearDown()} 이 한다.
 */
@AutoConfigureMockMvc
class DatasetColumnOptionDefaultTest extends IntegrationTestBase {

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
    editor = fx.createUser("wd698-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    // 성공한 생성만 지운다 — 삭제 경로가 카탈로그·물리 테이블을 함께 정리한다.
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token()));
    }
    fx.deleteUser(editor);
  }

  // ── 필드 추가 ─────────────────────────────────────────────────────────

  /** isNullable 없이 필드 추가 → 카탈로그·실제 컬럼 모두 NULL 허용. */
  @Test
  void addColumn_withoutIsNullable_createsNullableColumn() throws Exception {
    String table = "wd698_addnull_" + unique;
    long id = createValidDataset(table);

    MvcResult r = addColumn(id, "{\"columnName\":\"memo\",\"dataType\":\"TEXT\"}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(json(r).get("isNullable").asBoolean()).as("응답의 isNullable").isTrue();
    assertThat(detailColumn(id, "memo").get("isNullable").asBoolean())
        .as("상세의 isNullable")
        .isTrue();
    assertThat(physicalIsNullable(table, "memo"))
        .as("information_schema.is_nullable")
        .isEqualTo("YES");
  }

  /** 행이 있는 데이터셋에도 isNullable 없이 필드를 추가할 수 있다(이전엔 NOT NULL 로 묶여 거부). */
  @Test
  void addColumn_withoutIsNullable_onDatasetWithRows_succeeds() throws Exception {
    String table = "wd698_addrows_" + unique;
    long id = createValidDataset(table);
    insertRow(table);

    MvcResult r = addColumn(id, "{\"columnName\":\"memo\",\"dataType\":\"TEXT\"}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(physicalIsNullable(table, "memo")).isEqualTo("YES");
  }

  /** 명시한 isNullable=false 는 그대로 NOT NULL(기존 동작 유지). */
  @Test
  void addColumn_explicitNotNullable_createsNotNullColumn() throws Exception {
    String table = "wd698_addnotnull_" + unique;
    long id = createValidDataset(table);

    MvcResult r =
        addColumn(id, "{\"columnName\":\"memo\",\"dataType\":\"TEXT\",\"isNullable\":false}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(json(r).get("isNullable").asBoolean()).isFalse();
    assertThat(physicalIsNullable(table, "memo")).isEqualTo("NO");
  }

  /** isIndexed·isPrimaryKey 누락 → 둘 다 false. */
  @Test
  void addColumn_withoutIndexedAndPrimaryKey_defaultsToFalse() throws Exception {
    long id = createValidDataset("wd698_addidx_" + unique);

    MvcResult r = addColumn(id, "{\"columnName\":\"memo\",\"dataType\":\"TEXT\"}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(json(r).get("isIndexed").asBoolean()).isFalse();
    assertThat(json(r).get("isPrimaryKey").asBoolean()).isFalse();
  }

  /** JSON null 명시는 누락과 같다 — NULL 허용·색인 없음·기본 키 아님. */
  @Test
  void addColumn_explicitJsonNulls_sameAsMissing() throws Exception {
    String table = "wd698_addjsonnull_" + unique;
    long id = createValidDataset(table);
    insertRow(table);

    MvcResult r =
        addColumn(
            id,
            "{\"columnName\":\"memo\",\"dataType\":\"TEXT\",\"isNullable\":null,"
                + "\"isIndexed\":null,\"isPrimaryKey\":null}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(json(r).get("isNullable").asBoolean()).isTrue();
    assertThat(json(r).get("isIndexed").asBoolean()).isFalse();
    assertThat(json(r).get("isPrimaryKey").asBoolean()).isFalse();
    assertThat(physicalIsNullable(table, "memo")).isEqualTo("YES");
  }

  /** 필드 추가: 기본 키인데 isNullable 이 없으면 NOT NULL 기본 키(행 없는 데이터셋) — 생성 경로와 같은 기본값. */
  @Test
  void addColumn_primaryKeyWithoutIsNullable_staysNotNull() throws Exception {
    String table = "wd698_addpk_" + unique;
    long id = createValidDataset(table);

    MvcResult r = addColumn(id, "{\"columnName\":\"code\",\"dataType\":\"TEXT\",\"isPrimaryKey\":true}");

    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    assertThat(json(r).get("isPrimaryKey").asBoolean()).isTrue();
    assertThat(json(r).get("isNullable").asBoolean()).isFalse();
    assertThat(physicalIsNullable(table, "code")).isEqualTo("NO");
  }

  // ── 데이터셋 생성 ─────────────────────────────────────────────────────

  /** columns[] 에 isNullable 이 없으면 NULL 허용 컬럼으로 만든다. */
  @Test
  void create_columnsWithoutIsNullable_areNullable() throws Exception {
    String table = "wd698_create_" + unique;
    MvcResult r =
        create(
            table,
            "[{\"columnName\":\"title\",\"dataType\":\"TEXT\"},"
                + "{\"columnName\":\"amount\",\"dataType\":\"INTEGER\",\"isNullable\":null}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);

    for (String col : List.of("title", "amount")) {
      JsonNode c = detailColumn(id, col);
      assertThat(c.get("isNullable").asBoolean()).as("%s isNullable", col).isTrue();
      assertThat(c.get("isIndexed").asBoolean()).as("%s isIndexed", col).isFalse();
      assertThat(c.get("isPrimaryKey").asBoolean()).as("%s isPrimaryKey", col).isFalse();
      assertThat(physicalIsNullable(table, col)).as("%s is_nullable", col).isEqualTo("YES");
    }
  }

  /** 생성: 명시한 isNullable=false 는 NOT NULL(기존 동작 유지). */
  @Test
  void create_explicitNotNullable_isNotNull() throws Exception {
    String table = "wd698_createnn_" + unique;
    MvcResult r =
        create(table, "[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":false}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    datasets.add(json(r).get("id").asLong());
    assertThat(physicalIsNullable(table, "title")).isEqualTo("NO");
  }

  /**
   * 기본 키인데 isNullable 이 없으면 예전처럼 NOT NULL 기본 키로 만든다 — 누락을 무조건 true 로 채우면 「기본 키는 NULL 허용 불가」 검증에 걸려
   * 이전에 되던 요청이 400 이 된다.
   */
  @Test
  void create_primaryKeyWithoutIsNullable_staysNotNullPrimaryKey() throws Exception {
    String table = "wd698_createpk_" + unique;
    MvcResult r =
        create(table, "[{\"columnName\":\"code\",\"dataType\":\"TEXT\",\"isPrimaryKey\":true}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);

    JsonNode c = detailColumn(id, "code");
    assertThat(c.get("isPrimaryKey").asBoolean()).isTrue();
    assertThat(c.get("isNullable").asBoolean()).isFalse();
    assertThat(physicalIsNullable(table, "code")).isEqualTo("NO");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** 데이터 테이블 컬럼의 실제 NULL 허용 여부(information_schema — 'YES'/'NO'). */
  private String physicalIsNullable(String tableName, String columnName) {
    var row =
        dataDsl.fetchOne(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? AND column_name = ?",
            DataSchema.forTenant(DEFAULT_TEST_TENANT_ID),
            tableName,
            columnName);
    // 컬럼이 없으면 NPE 대신 무엇이 없는지 알려 준다.
    assertThat(row).as("실제 컬럼 %s.%s 가 없다", tableName, columnName).isNotNull();
    return row.get(0, String.class);
  }

  /** 데이터 테이블에 행 하나를 직접 넣는다(행 수 > 0 조건). */
  private void insertRow(String tableName) {
    inTenantFixture(
        () ->
            dataDsl.execute(
                "INSERT INTO "
                    + DataSchema.forTenant(DEFAULT_TEST_TENANT_ID)
                    + ".\""
                    + tableName
                    + "\" (title) VALUES ('x')"));
  }

  /** 상세 조회 응답에서 이름이 같은 컬럼. */
  private JsonNode detailColumn(long datasetId, String columnName) throws Exception {
    MvcResult r =
        mockMvc.perform(get(BASE + "/" + datasetId).header("Authorization", token())).andReturn();
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    for (JsonNode c : json(r).get("columns")) {
      if (columnName.equals(c.get("columnName").asText())) {
        return c;
      }
    }
    throw new AssertionError("컬럼 없음: " + columnName + " / " + body(r));
  }

  /** columns 부분만 JSON 문자열로 받아 생성 요청을 보낸다 — 필드 누락·null 을 직렬화기 거치지 않고 그대로 싣기 위해서다. */
  private MvcResult create(String tableName, String columnsJson) throws Exception {
    String body =
        "{\"name\":\"WD698 "
            + tableName
            + "\",\"tableName\":\""
            + tableName
            + "\",\"columns\":"
            + columnsJson
            + "}";
    return mockMvc
        .perform(
            post(BASE)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private long createValidDataset(String tableName) throws Exception {
    MvcResult r =
        create(tableName, "[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    return id;
  }

  private MvcResult addColumn(long datasetId, String body) throws Exception {
    return mockMvc
        .perform(
            post(BASE + "/" + datasetId + "/columns")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String body(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
