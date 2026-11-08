package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * 데이터셋 컬럼 타입 검증 문구의 한국어화(WD-569) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: VARCHAR 길이가 1~10000 을 벗어나거나 알 수 없는 데이터 타입을 보내면 400 은 났지만 응답 message 가 영어(「VARCHAR length
 * must be between 1 and 10000」·「Unknown data type: NOPE」)였고, 웹은 이 문구를 토스트로 그대로 보여 줬다. 이제 데이터셋 생성·필드
 * 추가·필드 타입 변경 모두 400 + 한국어 message 로 거부한다.
 *
 * <p>MockMvc 로 하는 이유: 서비스 예외가 GlobalExceptionHandler 를 거쳐 응답 message 까지 그대로 실리는지가 핵심이라서다. 클래스 레벨
 * {@code @Transactional} 을 쓰지 않으므로 정리는 {@link #tearDown()} 이 한다.
 */
@AutoConfigureMockMvc
class DatasetColumnTypeMessageTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";
  private static final String DATA_TYPE_REQUIRED_MESSAGE = "컬럼 데이터 타입을 입력하세요.";
  private static final String UNKNOWN_TYPE_MESSAGE = "알 수 없는 데이터 타입입니다: NOPE";

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
    editor = fx.createUser("wd569-editor-");
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

  // ── 데이터셋 생성 ─────────────────────────────────────────────────────

  /** 생성: VARCHAR 길이 10001(상한 초과) → 400 + 한국어 문구. */
  @Test
  void create_varcharTooLong_returns400WithKoreanMessage() throws Exception {
    MvcResult r =
        create(
            "wd569_vlong_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"VARCHAR\",\"maxLength\":10001}]");
    assertBadRequest(r, varcharMessage(10001));
  }

  /** 생성: VARCHAR 길이 0(하한 미만) → 400 + 한국어 문구. */
  @Test
  void create_varcharZeroLength_returns400WithKoreanMessage() throws Exception {
    MvcResult r =
        create(
            "wd569_vzero_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"VARCHAR\",\"maxLength\":0}]");
    assertBadRequest(r, varcharMessage(0));
  }

  /** 생성: 알 수 없는 타입 NOPE → 400 + 입력값을 담은 한국어 문구. */
  @Test
  void create_unknownType_returns400WithKoreanMessage() throws Exception {
    MvcResult r = create("wd569_unk_" + unique, "[{\"columnName\":\"a\",\"dataType\":\"NOPE\"}]");
    assertBadRequest(r, UNKNOWN_TYPE_MESSAGE);
  }

  /** 경계값 10000 은 그대로 201 — 문구만 바꿨고 허용 범위는 그대로다. */
  @Test
  void create_varcharMaxBoundary_returns201() throws Exception {
    MvcResult r =
        create(
            "wd569_vmax_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"VARCHAR\",\"maxLength\":10000}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    datasets.add(json(r).get("id").asLong());
  }

  // ── 필드 추가 ─────────────────────────────────────────────────────────

  /** 필드 추가: VARCHAR 길이 10001 → 400 + 한국어 문구. */
  @Test
  void addColumn_varcharTooLong_returns400WithKoreanMessage() throws Exception {
    long id = createValidDataset("wd569_addv_" + unique);
    MvcResult r =
        addColumn(id, "{\"columnName\":\"b\",\"dataType\":\"VARCHAR\",\"maxLength\":10001}");
    assertBadRequest(r, varcharMessage(10001));
  }

  /** 필드 추가: 알 수 없는 타입 NOPE → 400 + 한국어 문구. */
  @Test
  void addColumn_unknownType_returns400WithKoreanMessage() throws Exception {
    long id = createValidDataset("wd569_addu_" + unique);
    MvcResult r = addColumn(id, "{\"columnName\":\"b\",\"dataType\":\"NOPE\"}");
    assertBadRequest(r, UNKNOWN_TYPE_MESSAGE);
  }

  // ── 필드 타입 변경 ────────────────────────────────────────────────────

  /** 타입 변경: TEXT → VARCHAR(10001) → 400 + 한국어 문구(물리 DDL 쪽 검증 경로). */
  @Test
  void updateColumn_varcharTooLong_returns400WithKoreanMessage() throws Exception {
    long id = createValidDataset("wd569_updv_" + unique);
    MvcResult r =
        updateColumn(id, firstColumnId(id), "{\"dataType\":\"VARCHAR\",\"maxLength\":10001}");
    assertBadRequest(r, varcharMessage(10001));
  }

  /** 타입 변경: TEXT → NOPE → 400 + 한국어 문구. */
  @Test
  void updateColumn_unknownType_returns400WithKoreanMessage() throws Exception {
    long id = createValidDataset("wd569_updu_" + unique);
    MvcResult r = updateColumn(id, firstColumnId(id), "{\"dataType\":\"NOPE\"}");
    assertBadRequest(r, UNKNOWN_TYPE_MESSAGE);
  }

  // ── WD-636 ────────────────────────────────────────────────────────────

  /** WD-636: 소문자·앞뒤 공백 타입은 받아 주고 대문자로 저장한다 — 앱 전체가 "VARCHAR".equals 처럼 대문자로 비교한다. */
  @Test
  void create_lowercaseTypes_returns201AndStoresUppercase() throws Exception {
    MvcResult r =
        create(
            "wd636_lower_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"varchar\",\"maxLength\":20},"
                + "{\"columnName\":\"b\",\"dataType\":\" integer \"}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    JsonNode columns = detail(id).get("columns");
    assertThat(columns.get(0).get("dataType").asText()).isEqualTo("VARCHAR");
    assertThat(columns.get(0).get("maxLength").asInt()).isEqualTo(20);
    assertThat(columns.get(1).get("dataType").asText()).isEqualTo("INTEGER");
  }

  /** WD-636: 알 수 없는 타입에 섞인 서식 문자(U+202E 양방향 재정렬·U+200B 영폭 공백)는 응답 문구에 실리지 않는다. */
  @Test
  void create_unknownTypeWithFormatChars_messageStripsThem() throws Exception {
    MvcResult r =
        create(
            "wd636_fmt_" + unique, "[{\"columnName\":\"a\",\"dataType\":\"NO\\u202EPE\\u200BX\"}]");
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
    String message = json(r).get("message").asText();
    assertThat(message).startsWith("알 수 없는 데이터 타입입니다: ");
    assertThat(message).doesNotContain("\u202E").doesNotContain("\u200B");
  }

  /** WD-636: 타입 변경 경로에서 빈 타입은 「알 수 없는 타입: 」(뒤가 빔) 대신 타입을 입력하라는 안내로 400. */
  @Test
  void updateColumn_blankType_returns400WithRequiredMessage() throws Exception {
    long id = createValidDataset("wd636_updb_" + unique);
    long columnId = firstColumnId(id);
    assertBadRequest(updateColumn(id, columnId, "{\"dataType\":\"\"}"), DATA_TYPE_REQUIRED_MESSAGE);
    assertBadRequest(
        updateColumn(id, columnId, "{\"dataType\":\"   \"}"), DATA_TYPE_REQUIRED_MESSAGE);
  }

  /** WD-636: 타입 변경도 소문자 타입을 받아 대문자로 저장한다. */
  @Test
  void updateColumn_lowercaseType_returns200AndStoresUppercase() throws Exception {
    long id = createValidDataset("wd636_updl_" + unique);
    MvcResult r =
        updateColumn(id, firstColumnId(id), "{\"dataType\":\"varchar\",\"maxLength\":50}");
    assertThat(r.getResponse().getStatus()).as(body(r)).isBetween(200, 204);
    JsonNode column = detail(id).get("columns").get(0);
    assertThat(column.get("dataType").asText()).isEqualTo("VARCHAR");
    assertThat(column.get("maxLength").asInt()).isEqualTo(50);
  }

  /** WD-636: 타입 변경의 알 수 없는 타입 문구도 서식 문자를 지운다(생성·추가와 같은 문장). */
  @Test
  void updateColumn_unknownTypeWithFormatChars_messageStripsThem() throws Exception {
    long id = createValidDataset("wd636_updf_" + unique);
    MvcResult r = updateColumn(id, firstColumnId(id), "{\"dataType\":\"NO\\u202EPE\"}");
    assertBadRequest(r, "알 수 없는 데이터 타입입니다: NO PE");
  }

  /**
   * WD-636 리뷰: 저장소 별칭(bigint·NUMERIC)은 생성·필드 추가·타입 변경 모두 같은 400 + 같은 문구로 막고, 문구는 사용자가 보낸 값을 그대로 보여
   * 준다(대문자로 바꾸지 않음). Tibero 저장소는 별칭을 DDL 로 받아 줘서 앞단에서 막지 않으면 BIGINT 가 저장됐다.
   */
  @Test
  void aliasTypes_rejectedWithRawEchoOnCreateAddAndUpdate() throws Exception {
    for (String alias : List.of("bigint", "NUMERIC")) {
      MvcResult created =
          create(
              "wd636_alias_" + alias.toLowerCase() + "_" + unique,
              "[{\"columnName\":\"a\",\"dataType\":\"" + alias + "\"}]");
      assertBadRequest(created, "알 수 없는 데이터 타입입니다: " + alias);
    }
    long id = createValidDataset("wd636_aliasu_" + unique);
    long columnId = firstColumnId(id);
    for (String alias : List.of("bigint", "NUMERIC")) {
      assertBadRequest(
          addColumn(id, "{\"columnName\":\"b\",\"dataType\":\"" + alias + "\"}"),
          "알 수 없는 데이터 타입입니다: " + alias);
      assertBadRequest(
          updateColumn(id, columnId, "{\"dataType\":\"" + alias + "\"}"),
          "알 수 없는 데이터 타입입니다: " + alias);
    }
  }

  /** WD-636 리뷰: 알 수 없는 타입 문구는 원문을 보여 준다 — 「straße」가 대문자 변환으로 「STRASSE」가 되지 않는다. */
  @Test
  void create_unknownNonAsciiType_echoesRawInput() throws Exception {
    MvcResult r = create("wd636_sz_" + unique, "[{\"columnName\":\"a\",\"dataType\":\"straße\"}]");
    assertBadRequest(r, "알 수 없는 데이터 타입입니다: straße");
  }

  /**
   * WD-636 리뷰: 타입 없이 길이만 바꾸는 요청({maxLength})도 기존 타입이 VARCHAR 면 저장소 상한으로 DDL 전에 검사한다. 예전에는 타입이 없으면
   * 길이 검사를 건너뛰었다(Tibero 4001~ 이 그대로 통과).
   */
  @Test
  void updateColumn_maxLengthOnlyOnVarchar_checksLimit() throws Exception {
    MvcResult c =
        create(
            "wd636_lenonly_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"VARCHAR\",\"maxLength\":100}]");
    assertThat(c.getResponse().getStatus()).as(body(c)).isEqualTo(201);
    long id = json(c).get("id").asLong();
    datasets.add(id);
    assertBadRequest(
        updateColumn(id, firstColumnId(id), "{\"maxLength\":10001}"), varcharMessage(10001));
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** WD-636: PG 상한(10000)과 입력값을 담은 VARCHAR 길이 문구. */
  private static String varcharMessage(int given) {
    return "문자열(VARCHAR) 필드 길이는 1~10000 사이여야 합니다(입력 " + given + ").";
  }

  /** 상세 조회 응답 본문. */
  private JsonNode detail(long datasetId) throws Exception {
    MvcResult r =
        mockMvc.perform(get(BASE + "/" + datasetId).header("Authorization", token())).andReturn();
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    return json(r);
  }

  /** columns 부분만 JSON 문자열로 받아 생성 요청을 보낸다. */
  private MvcResult create(String tableName, String columnsJson) throws Exception {
    String body =
        "{\"name\":\"WD569 "
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

  private MvcResult updateColumn(long datasetId, long columnId, String body) throws Exception {
    return mockMvc
        .perform(
            put(BASE + "/" + datasetId + "/columns/" + columnId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  /** 상세 조회 응답의 첫 컬럼 id — 타입 변경 대상. */
  private long firstColumnId(long datasetId) throws Exception {
    MvcResult r =
        mockMvc.perform(get(BASE + "/" + datasetId).header("Authorization", token())).andReturn();
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    return json(r).get("columns").get(0).get("id").asLong();
  }

  /** 400 이고 응답 message 가 기대한 한국어 문장인지 본다 — 웹은 이 message 를 토스트로 그대로 보여 준다. */
  private void assertBadRequest(MvcResult r, String expectedMessage) throws Exception {
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
    assertThat(json(r).get("message").asText()).isEqualTo(expectedMessage);
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
