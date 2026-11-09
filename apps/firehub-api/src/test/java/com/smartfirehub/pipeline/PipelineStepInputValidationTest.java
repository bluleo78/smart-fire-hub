package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 파이프라인 생성·수정 API 의 스텝 입력 검증(WD-547) — HTTP 왕복으로 상태 코드와 한국어 문구를 확인한다.
 *
 * <p>결함: steps[] 를 느슨하게 검증해 잘못된 입력이 조용히 저장되거나(빈 이름·없는 선행 스텝) 엉뚱한 상태 코드로 거부됐다(허용 밖 스크립트 유형 → DB 제약
 * 409, 같은 이름 스텝 → 409 「이미 같은 값이…」, 순환 → 영어 원문). 이제 모두 쓰기 전에 400 + 한국어 문구로 거부한다.
 *
 * <p>빈 SQL·PYTHON 스크립트 거부는 WD-585 담당이라 여기서 다루지 않는다 — {@link PipelineStepShapeValidationTest} 가
 * 문구·이미 빈 채로 저장된 스텝의 왕복 허용까지 본다.
 *
 * <p>MockMvc 로 하는 이유: 상태 코드가 GlobalExceptionHandler 의 예외 매핑으로 정해지므로(DB 제약 409·NPE 500 이 400 으로
 * 바뀌었는지) 서비스 호출만으로는 확인되지 않는다. 수정 경로는 거부된 요청이 아무것도 바꾸지 않았는지(스텝·의존·수정 시각 그대로)도 본다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋돼야 거부 후 상태를 다시 읽어 비교할 수 있다. 정리는 {@link
 * #tearDown()}.
 */
@AutoConfigureMockMvc
class PipelineStepInputValidationTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd547-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID,
                () -> dsl.execute("delete from pipeline where created_by = ?", editor)),
        () -> fx.deleteUser(editor));
  }

  // ── 거부 사례 표(생성·수정 공통) ──────────────────────────────────────────────

  /** (설명, 요청 스텝 목록, 기대 400 문구) — 계획(WD-547)의 표 각 행. */
  static Stream<Arguments> invalidSteps() {
    return Stream.of(
        Arguments.of(
            "요청에 없는 선행 스텝 이름",
            List.of(sql("a", "SELECT 1", List.of("zzz"))),
            "스텝 'a' 의 선행 스텝 'zzz' 를 찾을 수 없습니다."),
        Arguments.of("스텝 이름 null", List.of(sql(null, "SELECT 1", List.of())), "스텝 이름을 입력하세요."),
        Arguments.of("스텝 이름 빈 문자열", List.of(sql("", "SELECT 1", List.of())), "스텝 이름을 입력하세요."),
        Arguments.of("스텝 이름 공백뿐", List.of(sql("   ", "SELECT 1", List.of())), "스텝 이름을 입력하세요."),
        Arguments.of(
            "허용 밖 스크립트 유형",
            List.of(step("r1", "RUBY", "puts 1", List.of())),
            "스텝 'r1': 지원하지 않는 스크립트 유형입니다: RUBY (SQL, PYTHON, API_CALL, AI_CLASSIFY 중 하나)"),
        Arguments.of(
            "스크립트 유형 null",
            List.of(step("n1", null, "SELECT 1", List.of())),
            "스텝 'n1': 스크립트 유형을 입력하세요 (SQL, PYTHON, API_CALL, AI_CLASSIFY 중 하나)"),
        Arguments.of(
            "순환 의존",
            List.of(
                sql("a", "SELECT 1", List.of("b")),
                sql("b", "SELECT 1", List.of("a")),
                sql("c", "SELECT 1", List.of())),
            "스텝 사이에 순환 의존이 있어 실행 순서를 정할 수 없습니다. 순환에 걸린 스텝: 'a', 'b'"),
        // WD-620: 순환과 없는 이름이 함께 있으면 없는 이름을 먼저 알린다 — 없는 이름이 빠진 그래프로 순환을 판정하면 원인을 잘못 짚는다.
        Arguments.of(
            "순환과 없는 선행 스텝 이름이 함께",
            List.of(
                sql("a", "SELECT 1", List.of("b")),
                sql("b", "SELECT 1", List.of("a")),
                sql("c", "SELECT 1", List.of("ghost"))),
            "스텝 'c' 의 선행 스텝 'ghost' 를 찾을 수 없습니다."),
        Arguments.of(
            "자기 자신 의존",
            List.of(sql("a", "SELECT 1", List.of("a"))),
            "스텝 사이에 순환 의존이 있어 실행 순서를 정할 수 없습니다. 순환에 걸린 스텝: 'a'"),
        Arguments.of(
            "같은 이름 스텝 둘",
            List.of(sql("x", "SELECT 1", List.of()), sql("x", "SELECT 2", List.of())),
            "스텝 이름 'x' 가 중복됩니다."));
  }

  @ParameterizedTest(name = "생성 거부: {0}")
  @MethodSource("invalidSteps")
  void create_rejects400WithKoreanMessage(
      String label, List<Map<String, Object>> steps, String expectedMessage) throws Exception {
    MvcResult result = postPipeline("wd547-" + System.nanoTime(), steps);

    assertThat(result.getResponse().getStatus()).as(label).isEqualTo(400);
    assertThat(readTree(result).get("message").asText()).as(label).isEqualTo(expectedMessage);
    assertThat(
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID,
                () -> dsl.fetchCount(dsl.selectFrom("pipeline").where("created_by = ?", editor))))
        .as("거부된 생성은 파이프라인을 남기지 않는다: " + label)
        .isZero();
  }

  @ParameterizedTest(name = "수정 거부: {0}")
  @MethodSource("invalidSteps")
  void update_rejects400WithKoreanMessage_andChangesNothing(
      String label, List<Map<String, Object>> steps, String expectedMessage) throws Exception {
    long pipelineId = createBaseline();
    JsonNode before = detail(pipelineId);

    MvcResult result = putPipeline(pipelineId, before.get("name").asText(), steps);

    assertThat(result.getResponse().getStatus()).as(label).isEqualTo(400);
    assertThat(readTree(result).get("message").asText()).as(label).isEqualTo(expectedMessage);
    JsonNode after = detail(pipelineId);
    assertThat(stepSummary(after))
        .as("거부된 수정은 스텝을 바꾸지 않는다: " + label)
        .isEqualTo(stepSummary(before));
    assertThat(after.get("updatedAt").asText())
        .as("거부된 수정은 수정 시각도 바꾸지 않는다: " + label)
        .isEqualTo(before.get("updatedAt").asText());
  }

  // ── 정상 입력 회귀 ────────────────────────────────────────────────────────

  /** 선행 스텝 이름이 맞으면 그대로 저장되고 의존이 남는다. */
  @Test
  void create_validDependencyNames_savesDependency() throws Exception {
    long pipelineId = createBaseline();

    JsonNode steps = detail(pipelineId).get("steps");
    assertThat(steps).hasSize(2);
    assertThat(steps.get(1).get("name").asText()).isEqualTo("load");
    assertThat(steps.get(1).get("dependsOnStepNames").get(0).asText()).isEqualTo("extract");
  }

  /** 수정에서도 맞는 선행 스텝 이름(이름 바꾸기 포함)은 저장된다. */
  @Test
  void update_validDependencyNames_saves() throws Exception {
    long pipelineId = createBaseline();
    MvcResult result =
        putPipeline(
            pipelineId,
            detail(pipelineId).get("name").asText(),
            List.of(
                sql("extract2", "SELECT 1", List.of()),
                sql("load", "SELECT 2", List.of("extract2"))));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode steps = detail(pipelineId).get("steps");
    assertThat(steps.get(1).get("dependsOnStepNames").get(0).asText()).isEqualTo("extract2");
  }

  /** API_CALL 은 스크립트 내용 없이(null) 저장된다 — 유형 검증(WD-547)이 내용 없는 API_CALL 을 막지 않는지 본다. */
  @Test
  void create_apiCallWithNullContent_succeeds() throws Exception {
    MvcResult result = postPipeline("wd547-api-" + System.nanoTime(), List.of(apiCall("api")));

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
  }

  /** 수정 경로에서도 API_CALL 내용 null 은 허용된다. */
  @Test
  void update_apiCallWithNullContent_succeeds() throws Exception {
    long pipelineId = createBaseline();
    MvcResult result =
        putPipeline(pipelineId, detail(pipelineId).get("name").asText(), List.of(apiCall("api")));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** extract(SQL) ← load(SQL) 두 스텝 파이프라인을 만들고 id 를 돌려준다. */
  private long createBaseline() throws Exception {
    MvcResult created =
        postPipeline(
            "wd547-base-" + System.nanoTime(),
            List.of(
                sql("extract", "SELECT 1", List.of()),
                sql("load", "SELECT 2", List.of("extract"))));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    return readTree(created).get("id").asLong();
  }

  private static Map<String, Object> sql(String name, String content, List<String> dependsOn) {
    return step(name, "SQL", content, dependsOn);
  }

  /** null 값도 JSON 에 그대로 싣기 위해 LinkedHashMap 으로 만든다(Map.of 는 null 불가). */
  private static Map<String, Object> step(
      String name, String scriptType, String content, List<String> dependsOn) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("name", name);
    step.put("scriptType", scriptType);
    step.put("scriptContent", content);
    step.put("dependsOnStepNames", dependsOn);
    step.put("loadStrategy", "REPLACE");
    return step;
  }

  /** 출력 자동 생성 API_CALL 스텝 — 스크립트 내용 없음. */
  private static Map<String, Object> apiCall(String name) {
    Map<String, Object> step = step(name, "API_CALL", null, List.of());
    step.put(
        "apiConfig",
        Map.of("customUrl", "https://example.com/items", "method", "GET", "dataPath", "$"));
    return step;
  }

  /** 비교용 스텝 요약 — 이름·유형·내용·선행 스텝(id 는 제자리 갱신이라 함께 본다). */
  private List<String> stepSummary(JsonNode detail) {
    List<String> summary = new java.util.ArrayList<>();
    for (JsonNode s : detail.get("steps")) {
      summary.add(
          s.get("id").asText()
              + "|"
              + s.get("name").asText()
              + "|"
              + s.get("scriptType").asText()
              + "|"
              + s.get("scriptContent").asText()
              + "|"
              + s.get("dependsOnStepNames").toString());
    }
    return summary;
  }

  private MvcResult postPipeline(String name, List<Map<String, Object>> steps) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "WD-547");
    body.put("steps", steps);
    return mockMvc
        .perform(
            post("/api/v1/pipelines")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private MvcResult putPipeline(long pipelineId, String name, List<Map<String, Object>> steps)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "WD-547 수정");
    body.put("steps", steps);
    return mockMvc
        .perform(
            put("/api/v1/pipelines/" + pipelineId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private JsonNode detail(long pipelineId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/pipelines/" + pipelineId).header("Authorization", token()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return readTree(result);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
