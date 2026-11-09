package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.storage.DataTableStore;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * WD-603 — 필드 수가 DB 테이블 컬럼 한도를 넘으면 500(PSQLException: tables can have at most 1600 columns) 대신 한국어
 * 400 으로 막는다(HTTP 왕복, 실제 PG DDL).
 *
 * <p>PG 한도 1600 에서 앱 시스템 컬럼 수를 빼면 사용자 필드 한도다. 넓은 테이블은 만드는 데 무거워 한 개만 만들어 경계(한도 − 1 → 추가 통과 → 추가
 * 거부)와, 필드를 지운 뒤에도 PG 가 삭제 컬럼을 한도에 세어 ALTER 가 54011 로 실패하는 안전망까지 한 시나리오로 본다. 클래스 레벨
 * {@code @Transactional} 을 쓰지 않는다 — 실패한 요청이 카탈로그 행·물리 테이블을 남기지 않았는지 커밋된 상태로 본다.
 */
@AutoConfigureMockMvc
class DatasetColumnLimitTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";

  /** PG 사용자 필드 한도 = 테이블 컬럼 한도(1600) − 앱 시스템 컬럼 수. */
  private static final int LIMIT = 1600 - DataTableStore.SYSTEM_COLUMN_NAMES.size();

  /** 물리 컬럼 수에서 시스템 컬럼을 빼는 SQL 목록 — 시스템 컬럼 정의에서 만들어 목록이 바뀌어도 따라간다. */
  private static final String SYSTEM_COLUMNS_SQL =
      DataTableStore.SYSTEM_COLUMN_NAMES.stream()
          .map(n -> "'" + n + "'")
          .collect(Collectors.joining(",", "(", ")"));

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;

  /** 물리 테이블 확인용 — 분리 모드에서는 플랫폼 DB 와 다른 데이터 저장소 DB 다. */
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
    editor = fx.createUser("wd603-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token()));
    }
    fx.deleteUser(editor);
  }

  /** 생성: 한도 + 1 개면 400 + 「최대 N개(요청 M개)」 문구, 데이터셋 행·물리 테이블이 남지 않는다. */
  @Test
  void create_overLimit_returns400_andLeavesNothing() throws Exception {
    String table = "wd603_over_" + unique;
    MvcResult r = create(table, LIMIT + 1);

    assertStatus(r, 400);
    assertThat(message(r))
        .contains("최대 " + LIMIT + "개")
        .contains("요청 " + (LIMIT + 1) + "개")
        .doesNotContain("1600 columns");
    assertThat(count(dsl, "select count(*) from dataset where table_name = ?", table)).isZero();
    Object reg =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () -> dataDsl.fetchValue("select to_regclass(?)::text", DataSchema.qualify(table)));
    assertThat(reg).isNull();
  }

  /**
   * 넓은 테이블 하나로 보는 시나리오.
   *
   * <ol>
   *   <li>한도 − 1 개로 생성 → 201, 하나 추가 → 201(카탈로그 = 한도)
   *   <li>하나 더 추가 → 앞단 수 검사가 400 「지금 N개」, 카탈로그·물리 수는 그대로
   *   <li>필드 하나 삭제 후 추가 → 카탈로그는 한도 밑이라 앞단 검사는 통과하지만, PG 는 삭제 컬럼도 한도에 세어 ALTER 가 54011 로 실패한다 → PG
   *       저장소 안전망이 원문 SQL 없는 400 으로 바꾼다
   * </ol>
   */
  @Test
  void wideTable_limitThenDroppedColumnSafetyNet() throws Exception {
    String table = "wd603_wide_" + unique;
    MvcResult created = create(table, LIMIT - 1);
    assertStatus(created, 201);
    long id = json(created).get("id").asLong();
    datasets.add(id);

    // 1) 마지막 한 자리는 채울 수 있다.
    assertStatus(addColumn(id, "last_col"), 201);
    assertThat(catalogColumnCount(id)).isEqualTo(LIMIT);

    // 2) 한도에서 하나 더 → 앞단 수 검사 400, 아무것도 바뀌지 않는다.
    MvcResult over = addColumn(id, "over_col");
    assertStatus(over, 400);
    assertThat(message(over))
        .contains("최대 " + LIMIT + "개")
        .contains("지금 " + LIMIT + "개")
        .doesNotContain("1600 columns");
    assertThat(catalogColumnCount(id)).isEqualTo(LIMIT);
    assertThat(physicalUserColumnCount(table)).isEqualTo(LIMIT);

    // 3) 필드 삭제 API 로 하나 지운 뒤 추가 → 실제 PG 54011 → 안전망 400.
    long c1 =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () ->
                ((Number)
                        dsl.fetchValue(
                            "select id from dataset_column where dataset_id = ? and column_name = 'c1'",
                            id))
                    .longValue());
    MvcResult deleted =
        mockMvc
            .perform(delete(BASE + "/" + id + "/columns/" + c1).header("Authorization", token()))
            .andReturn();
    assertStatus(deleted, 204);

    MvcResult afterDrop = addColumn(id, "after_drop");
    assertStatus(afterDrop, 400);
    String body = afterDrop.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(message(afterDrop)).contains("더 이상 필드를 추가할 수 없습니다").contains(LIMIT + "개");
    assertThat(body).doesNotContain("ALTER TABLE").doesNotContain("1600 columns");
    assertThat(catalogColumnCount(id)).isEqualTo(LIMIT - 1);
    assertThat(physicalUserColumnCount(table)).isEqualTo(LIMIT - 1);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  private static String col(String name) {
    return "{\"columnName\":\"" + name + "\",\"dataType\":\"TEXT\",\"isNullable\":true}";
  }

  /** c1..cN 의 TEXT 필드 N 개로 데이터셋 생성을 요청한다. */
  private MvcResult create(String tableName, int columnCount) throws Exception {
    String cols =
        IntStream.rangeClosed(1, columnCount)
            .mapToObj(i -> col("c" + i))
            .collect(Collectors.joining(",", "[", "]"));
    String body =
        "{\"name\":\"WD603 %s\",\"tableName\":\"%s\",\"columns\":%s}"
            .formatted(tableName, tableName, cols);
    return mockMvc
        .perform(
            post(BASE)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult addColumn(long id, String name) throws Exception {
    return mockMvc
        .perform(
            post(BASE + "/" + id + "/columns")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(col(name)))
        .andReturn();
  }

  private void assertStatus(MvcResult r, int expected) throws Exception {
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(expected);
  }

  /** 테스트 테넌트 안에서 count 쿼리를 실행한다 — 플랫폼 DB(dsl)·데이터 저장소 DB(dataDsl) 공용. */
  private int count(DSLContext db, String sql, Object... args) {
    return inTenantFixture(
        DEFAULT_TEST_TENANT_ID, () -> ((Number) db.fetchValue(sql, args)).intValue());
  }

  private int catalogColumnCount(long id) {
    return count(dsl, "select count(*) from dataset_column where dataset_id = ?", id);
  }

  /** 물리 테이블의 사용자 필드 수(시스템 컬럼 제외, 삭제된 컬럼은 information_schema 에 보이지 않는다). */
  private int physicalUserColumnCount(String tableName) {
    return count(
        dataDsl,
        "select count(*) from information_schema.columns where table_schema = ? and table_name = ?"
            + " and column_name not in "
            + SYSTEM_COLUMNS_SQL,
        DataSchema.forTenant(DEFAULT_TEST_TENANT_ID),
        tableName);
  }

  private String message(MvcResult r) throws Exception {
    return json(r).path("message").asText();
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
