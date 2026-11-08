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
  private static final String VARCHAR_MESSAGE = "문자열(VARCHAR) 필드 길이는 1~10000 사이여야 합니다.";
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
    assertBadRequest(r, VARCHAR_MESSAGE);
  }

  /** 생성: VARCHAR 길이 0(하한 미만) → 400 + 한국어 문구. */
  @Test
  void create_varcharZeroLength_returns400WithKoreanMessage() throws Exception {
    MvcResult r =
        create(
            "wd569_vzero_" + unique,
            "[{\"columnName\":\"a\",\"dataType\":\"VARCHAR\",\"maxLength\":0}]");
    assertBadRequest(r, VARCHAR_MESSAGE);
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
    assertBadRequest(r, VARCHAR_MESSAGE);
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
    assertBadRequest(r, VARCHAR_MESSAGE);
  }

  /** 타입 변경: TEXT → NOPE → 400 + 한국어 문구. */
  @Test
  void updateColumn_unknownType_returns400WithKoreanMessage() throws Exception {
    long id = createValidDataset("wd569_updu_" + unique);
    MvcResult r = updateColumn(id, firstColumnId(id), "{\"dataType\":\"NOPE\"}");
    assertBadRequest(r, UNKNOWN_TYPE_MESSAGE);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

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
