package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bean Validation 문구 한국어화(WD-570) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: message= 를 명시하지 않은 DTO 제약은 Hibernate Validator 의 영어 기본 문구("must not be blank")로, 최상위
 * message 는 "Validation failed" 로 응답했다. 이제 기본 번들 {@code ValidationMessages.properties} 가 한국어 기본 문구를
 * 주고, GlobalExceptionHandler 가 최상위 문구를 한국어로 낸다.
 *
 * <p>MockMvc 로 하는 이유: 문구는 Spring 의 검증기 → HV 메시지 보간 → 예외 처리기를 모두 거쳐야 정해지고(요청 로케일·번들 인코딩 포함), 서비스 직접
 * 호출로는 확인되지 않는다. Accept-Language: en 요청도 한국어여야 한다 — 기본 번들이라 로케일과 상관없이 쓰이는지 확인한다.
 */
@AutoConfigureMockMvc
class ValidationMessagesLocalizationTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long admin;
  private final List<Long> datasets = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    admin = fx.createUser("wd570-admin-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    // 데이터셋 경로는 존재 확인이 검증보다 먼저라 실제 데이터셋을 만든다 — 삭제 경로가 카탈로그·물리 테이블을 함께 정리한다.
    for (Long id : datasets) {
      mockMvc.perform(delete("/api/v1/datasets/" + id).header("Authorization", token()));
    }
    fx.deleteUser(admin);
  }

  /** 이슈의 예: POST /api/v1/datasets {} → 최상위·필드 문구 모두 한국어. */
  @Test
  void createDataset_emptyBody_returnsKoreanMessages() throws Exception {
    JsonNode body = assert400(postJson("/api/v1/datasets", "{}", null));
    assertThat(body.path("errors").path("name").asText()).isEqualTo("필수 입력값입니다.");
    assertThat(body.path("errors").path("tableName").asText()).isEqualTo("필수 입력값입니다.");
  }

  /** Accept-Language: en 이어도 한국어 — 로케일 접미사 없는 기본 번들이라 요청 로케일에 끌려가지 않는다. */
  @Test
  void createDataset_acceptLanguageEn_stillKorean() throws Exception {
    JsonNode body = assert400(postJson("/api/v1/datasets", "{}", "en"));
    assertThat(body.path("errors").path("name").asText()).isEqualTo("필수 입력값입니다.");
    assertThat(body.path("errors").path("tableName").asText()).isEqualTo("필수 입력값입니다.");
  }

  /** 다른 경로(역할 생성)의 기본 @NotBlank 도 한국어. */
  @Test
  void createRole_emptyBody_returnsKoreanMessages() throws Exception {
    JsonNode body = assert400(postJson("/api/v1/roles", "{}", "en"));
    assertThat(body.path("errors").path("name").asText()).isEqualTo("필수 입력값입니다.");
  }

  /** 메시지 없는 @Size(max) 는 EL 로 「N자 이하」만 말한다(기본 min=0 을 「0자 이상」으로 보이지 않게). */
  @Test
  void createApiConnection_tooLongName_returnsKoreanSizeMessage() throws Exception {
    String json =
        objectMapper.writeValueAsString(
            java.util.Map.of(
                "name", "a".repeat(101), "authType", "BEARER", "baseUrl", "https://example.com"));
    JsonNode body = assert400(postJson("/api/v1/api-connections", json, null));
    assertThat(body.path("errors").path("name").asText()).isEqualTo("100자 이하로 입력하세요.");
  }

  /** DTO 에 명시한 한국어 message= (사용자 추가)는 기본 번들이 덮어쓰지 않는다. */
  @Test
  void createUser_explicitKoreanMessage_unchanged() throws Exception {
    JsonNode body = assert400(postJson("/api/v1/users", "{}", "en"));
    assertThat(body.path("errors").path("email").asText()).isEqualTo("이메일은 필수입니다");
    assertThat(body.path("errors").path("name").asText()).isEqualTo("이름은 필수입니다");
  }

  /**
   * Accept-Language: ko 도 우리 번들 문구다 — HV 는 자체 한국어 번들(「공백일 수 없습니다」)을 갖고 있지만, 사용자 번들의 기본 파일이 HV 내장
   * 번들보다 먼저 조회되므로 「필수 입력값입니다.」가 나와야 한다.
   */
  @Test
  void createDataset_acceptLanguageKo_usesOurBundleNotHibernateKorean() throws Exception {
    JsonNode body = assert400(postJson("/api/v1/datasets", "{}", "ko"));
    assertThat(body.path("errors").path("name").asText()).isEqualTo("필수 입력값입니다.");
    assertThat(body.path("errors").path("tableName").asText()).isEqualTo("필수 입력값입니다.");
  }

  /**
   * 한 필드가 @NotBlank 와 @Pattern 을 동시에 어기면(tableName="") 필수값 문구가 항상 이긴다. 위반 목록 순서는 정해져 있지 않아 예전엔 요청마다
   * 「형식이 올바르지 않습니다.」가 섞여 나왔다 — 여러 번 보내 결정적인지 확인한다.
   */
  @Test
  void cloneDataset_blankTableName_requiredMessageAlwaysWins() throws Exception {
    long id = createDataset();
    for (int i = 0; i < 10; i++) {
      JsonNode body =
          assert400(
              send(
                  post("/api/v1/datasets/" + id + "/clone"),
                  "{\"name\":\"x\",\"tableName\":\"\"}",
                  "ko"));
      assertThat(body.path("errors").path("tableName").asText()).isEqualTo("필수 입력값입니다.");
    }
  }

  /** AI 세션 제목 수정: 예전 영어 "Title is required" → 한국어 명시 문구. */
  @Test
  void updateAiSession_blankTitle_returnsKoreanMessage() throws Exception {
    JsonNode body =
        assert400(send(put("/api/v1/ai/sessions/999999999"), "{\"title\":\" \"}", "en"));
    assertThat(body.path("errors").path("title").asText()).isEqualTo("제목을 입력하세요.");
  }

  /** 설정 일괄 수정: 예전 영어 "Settings map must not be empty" → 한국어 명시 문구. */
  @Test
  void updateSettings_emptyMap_returnsKoreanMessage() throws Exception {
    JsonNode body = assert400(send(put("/api/v1/settings"), "{\"settings\":{}}", "en"));
    assertThat(body.path("errors").path("settings").asText()).isEqualTo("변경할 설정이 없습니다.");
  }

  /** 행 일괄 추가: 컬렉션 @Size 는 「자」가 아니라 개수 기준 명시 문구. */
  @Test
  void addRowsBatch_emptyRows_returnsCountMessage() throws Exception {
    JsonNode body =
        assert400(
            send(
                post("/api/v1/datasets/" + createDataset() + "/data/rows/batch"),
                "{\"rows\":[]}",
                null));
    assertThat(body.path("errors").path("rows").asText()).isEqualTo("행은 1개 이상 100개 이하로 보내야 합니다.");
  }

  // ── 헬퍼 ───────────────────────────────────────────────────────

  /** JSON 본문 POST. acceptLanguage 가 null 이면 헤더를 보내지 않는다. */
  private MvcResult postJson(String url, String json, String acceptLanguage) throws Exception {
    return send(post(url), json, acceptLanguage);
  }

  /** 검증 대상 경로용 데이터셋을 하나 만들고 id 를 돌려준다(정리는 tearDown). */
  private long createDataset() throws Exception {
    String table = "wd570_" + System.nanoTime();
    String json =
        "{\"name\":\"WD570 "
            + table
            + "\",\"tableName\":\""
            + table
            + "\",\"columns\":[{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true}]}";
    MvcResult r = postJson("/api/v1/datasets", json, null);
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    long id =
        objectMapper
            .readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .get("id")
            .asLong();
    datasets.add(id);
    return id;
  }

  /** 주어진 요청(POST/PUT)에 인증·JSON 본문·선택적 Accept-Language 를 실어 보낸다. */
  private MvcResult send(MockHttpServletRequestBuilder base, String json, String acceptLanguage)
      throws Exception {
    var req =
        base.header("Authorization", token()).contentType(MediaType.APPLICATION_JSON).content(json);
    if (acceptLanguage != null) {
      req = req.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
    }
    return mockMvc.perform(req).andReturn();
  }

  /** 400 + 한국어 최상위 문구를 확인하고 본문을 돌려준다. */
  private JsonNode assert400(MvcResult r) throws Exception {
    String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(r.getResponse().getStatus()).as("응답 본문: %s", raw).isEqualTo(400);
    JsonNode body = objectMapper.readTree(raw);
    assertThat(body.path("message").asText())
        .isEqualTo(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE)
        .isEqualTo("입력값이 올바르지 않습니다.");
    return body;
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(admin, "u" + admin, DEFAULT_TEST_TENANT_ID);
  }
}
