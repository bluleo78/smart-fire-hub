package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
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
 * 데이터셋 생성·필드 추가의 컬럼 요소·필드 null 검증(WD-587) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: {@code POST /api/v1/datasets} 의 {@code columns} 에 null 요소·빈 객체·dataType null·columnName
 * null 이 들어오면 서비스 검증({@code DataTableService.validateNewColumns})에서 NPE 가 나 500 이 됐다. 이제 요청 경계(Bean
 * Validation)에서 400 + 한국어 필드 문구로 거부한다.
 *
 * <p>MockMvc 로 하는 이유: 핵심이 「@Valid 가 리스트 요소·중첩 필드까지 내려가는가」와 「한국어 문구가 응답 errors 에 실리는가」라서, 서비스를 직접
 * 부르는 테스트로는 확인되지 않는다. 본문은 JSON 문자열로 직접 써서 null 요소·빈 객체를 그대로 보낸다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 실패 요청이 데이터를 남기지 않았는지 커밋된 상태로 확인한다. 정리는 {@link
 * #tearDown()}.
 */
@AutoConfigureMockMvc
class DatasetColumnRequestValidationTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
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
    editor = fx.createUser("wd587-editor-");
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

  // ── 생성: 이슈의 네 경우 ─────────────────────────────────────────────

  /** columns:[null] — 요소 자체가 null 이면 「컬럼 정보가 비어 있습니다.」 */
  @Test
  void create_nullColumnElement_returns400WithKoreanMessage() throws Exception {
    String table = "wd587_nullel_" + unique;
    MvcResult r = createRaw(table, "[null]");
    assertValidation400(r, "columns[0]", "컬럼 정보가 비어 있습니다.");
    assertNoDataset(table);
  }

  /** columns:[{}] — 이름·타입이 모두 없으면 두 칸 모두 한국어 문구. */
  @Test
  void create_emptyColumnObject_returns400WithKoreanMessages() throws Exception {
    String table = "wd587_empty_" + unique;
    MvcResult r = createRaw(table, "[{}]");
    assertValidation400(r, "columns[0].columnName", "컬럼 이름을 입력하세요.");
    assertValidation400(r, "columns[0].dataType", "컬럼 데이터 타입을 입력하세요.");
    assertNoDataset(table);
  }

  /** dataType:null — 「컬럼 데이터 타입을 입력하세요.」 */
  @Test
  void create_nullDataType_returns400WithKoreanMessage() throws Exception {
    String table = "wd587_nulltype_" + unique;
    MvcResult r = createRaw(table, "[{\"columnName\":\"a\",\"dataType\":null}]");
    assertValidation400(r, "columns[0].dataType", "컬럼 데이터 타입을 입력하세요.");
    assertNoDataset(table);
  }

  /** columnName:null — 「컬럼 이름을 입력하세요.」 */
  @Test
  void create_nullColumnName_returns400WithKoreanMessage() throws Exception {
    String table = "wd587_nullname_" + unique;
    MvcResult r = createRaw(table, "[{\"columnName\":null,\"dataType\":\"TEXT\"}]");
    assertValidation400(r, "columns[0].columnName", "컬럼 이름을 입력하세요.");
    assertNoDataset(table);
  }

  /** 공백만 있는 columnName 도 같은 문구로 거부한다(@NotBlank). */
  @Test
  void create_blankColumnName_returns400WithKoreanMessage() throws Exception {
    String table = "wd587_blankname_" + unique;
    MvcResult r = createRaw(table, "[{\"columnName\":\"  \",\"dataType\":\"TEXT\"}]");
    assertValidation400(r, "columns[0].columnName", "컬럼 이름을 입력하세요.");
    assertNoDataset(table);
  }

  /** 정상 컬럼은 그대로 201 — 새 제약이 정상 본문을 막지 않는다. */
  @Test
  void create_validColumns_returns201() throws Exception {
    String table = "wd587_ok_" + unique;
    MvcResult r =
        createRaw(
            table,
            "[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true},"
                + "{\"columnName\":\"amount\",\"dataType\":\"INTEGER\",\"isNullable\":true}]");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    datasets.add(json(r).get("id").asLong());
  }

  // ── 필드 추가: 같은 null 이 영어 기본 문구가 아니라 한국어로 거부된다 ───────────

  /** 필드 추가의 columnName·dataType 누락도 400 + 한국어 문구(이전엔 기본 영어 문구 「must not be blank」). */
  @Test
  void addColumn_missingNameAndType_returns400WithKoreanMessages() throws Exception {
    long id = createValidDataset("wd587_add_" + unique);
    MvcResult r =
        mockMvc
            .perform(
                post(BASE + "/" + id + "/columns")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"columnName\":null,\"dataType\":\" \"}"))
            .andReturn();
    assertValidation400(r, "columnName", "컬럼 이름을 입력하세요.");
    assertValidation400(r, "dataType", "컬럼 데이터 타입을 입력하세요.");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** columns 부분만 JSON 문자열로 받아 생성 요청을 보낸다 — null 요소·빈 객체를 직렬화기 거치지 않고 그대로 싣기 위해서다. */
  private MvcResult createRaw(String tableName, String columnsJson) throws Exception {
    String body =
        "{\"name\":\"WD587 "
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
        createRaw(
            tableName, "[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true}]");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    return id;
  }

  /** 400 이고, errors 의 해당 필드 문구가 기대한 한국어 문장인지 본다 — 웹은 errors 의 첫 문구를 사용자에게 보여 준다. */
  private void assertValidation400(MvcResult r, String field, String expectedMessage)
      throws Exception {
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(400);
    JsonNode errors = json(r).get("errors");
    assertThat(errors).as("errors 맵이 있어야 한다").isNotNull();
    assertThat(errors.path(field).asText()).as("필드 %s 문구", field).isEqualTo(expectedMessage);
  }

  /** 실패 요청은 카탈로그 행을 남기지 않는다. */
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

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
