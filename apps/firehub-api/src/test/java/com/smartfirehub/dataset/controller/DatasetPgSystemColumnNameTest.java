package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * WD-564 — PostgreSQL 시스템 컬럼명(xmin·xmax·cmin·cmax·ctid·tableoid)을 컬럼명으로 쓰면 400 으로 거부한다(HTTP 왕복).
 *
 * <p>결함: 앱 예약어(id 등)만 사전 검사해, 위 이름은 DDL 까지 내려가 PSQLException("conflicts with a system column name")
 * → 500 이 됐다. 이제 생성·필드 추가·이름 변경 모두 앱 예약어와 같은 「시스템 예약어」 400 문구로 거부한다.
 *
 * <p>MockMvc 로 하는 이유: 핵심이 「500 이 아니라 400 + 한국어 문구가 응답에 실리는가」라서다. 클래스 레벨 {@code @Transactional} 을 쓰지
 * 않는다 — 실패한 생성이 카탈로그 행·물리 테이블을 남기지 않았는지 커밋된 상태로 확인한다.
 */
@AutoConfigureMockMvc
class DatasetPgSystemColumnNameTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;

  /** 물리 테이블 존재 확인용 — 분리 모드에서는 플랫폼 DB 와 다른 데이터 저장소 DB 다. */
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
    editor = fx.createUser("wd564-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token()));
    }
    fx.deleteUser(editor);
  }

  /** 생성: PG 시스템 컬럼명이 섞이면 400 + 예약어 문구, 카탈로그 행·물리 테이블이 남지 않는다. */
  @ParameterizedTest
  @ValueSource(strings = {"xmin", "xmax", "cmin", "cmax", "ctid", "tableoid"})
  void create_pgSystemColumnName_returns400(String name) throws Exception {
    String table = "wd564_c_" + name + "_" + unique;
    MvcResult r = create(table, "[" + col("title") + "," + col(name) + "]");
    assertReserved400(r, name);
    assertNoDataset(table);
    assertNoPhysicalTable(table);
  }

  /** 필드 추가: PG 시스템 컬럼명이면 400, 기존 필드 목록은 그대로다. */
  @ParameterizedTest
  @ValueSource(strings = {"xmin", "xmax", "cmin", "cmax", "ctid", "tableoid"})
  void addColumn_pgSystemColumnName_returns400(String name) throws Exception {
    long id = createValidDataset("wd564_a_" + name + "_" + unique);
    MvcResult r =
        mockMvc
            .perform(
                post(BASE + "/" + id + "/columns")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(col(name)))
            .andReturn();
    assertReserved400(r, name);
    assertColumnNames(id, List.of("title"));
  }

  /** 이름 변경(빈 데이터셋): PG 시스템 컬럼명으로 바꾸면 400, 이름은 그대로다. */
  @ParameterizedTest
  @ValueSource(strings = {"xmin", "xmax", "cmin", "cmax", "ctid", "tableoid"})
  void renameColumn_pgSystemColumnName_returns400(String name) throws Exception {
    long id = createValidDataset("wd564_r_" + name + "_" + unique);
    long columnId = json(detail(id)).get("columns").get(0).get("id").asLong();
    MvcResult r =
        mockMvc
            .perform(
                put(BASE + "/" + id + "/columns/" + columnId)
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"columnName\":\"" + name + "\"}"))
            .andReturn();
    assertReserved400(r, name);
    assertColumnNames(id, List.of("title"));
  }

  /** 시스템 컬럼명을 접두·접미로 포함한 이름(xmin_value·ctid2 등)은 정확히 일치하지 않으므로 정상 생성된다. */
  @Test
  void create_namesContainingPgSystemColumnName_areAllowed() throws Exception {
    String table = "wd564_ok_" + unique;
    MvcResult r =
        create(
            table,
            "["
                + col("xmin_value")
                + ","
                + col("ctid2")
                + ","
                + col("my_tableoid")
                + ","
                + col("cmaxx")
                + "]");
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    assertColumnNames(id, List.of("xmin_value", "ctid2", "my_tableoid", "cmaxx"));
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  private static String col(String name) {
    return "{\"columnName\":\"" + name + "\",\"dataType\":\"TEXT\",\"isNullable\":true}";
  }

  private MvcResult create(String tableName, String columnsJson) throws Exception {
    String body =
        "{\"name\":\"WD564 "
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
    MvcResult r = create(tableName, "[" + col("title") + "]");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    return id;
  }

  private MvcResult detail(long id) throws Exception {
    return mockMvc.perform(get(BASE + "/" + id).header("Authorization", token())).andReturn();
  }

  /** 400 이고 문구가 앱 예약어와 같은 형식(이름 + 예약어 목록에 PG 시스템 컬럼 포함)인지 본다. */
  private void assertReserved400(MvcResult r, String name) throws Exception {
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(400);
    assertThat(json(r).path("message").asText())
        .startsWith("컬럼명 '" + name + "'은 시스템 예약어입니다.")
        .contains("xmin, xmax, cmin, cmax, ctid, tableoid");
  }

  private void assertColumnNames(long id, List<String> expected) throws Exception {
    List<String> names = new ArrayList<>();
    json(detail(id)).get("columns").forEach(c -> names.add(c.get("columnName").asText()));
    assertThat(names).containsExactlyElementsOf(expected);
  }

  private void assertNoDataset(String tableName) {
    Integer count =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () ->
                ((Number)
                        dsl.fetchValue(
                            "select count(*)::int from dataset where table_name = ?", tableName))
                    .intValue());
    assertThat(count).isZero();
  }

  private void assertNoPhysicalTable(String tableName) {
    Object reg =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () -> dataDsl.fetchValue("select to_regclass(?)::text", DataSchema.qualify(tableName)));
    assertThat(reg).isNull();
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
