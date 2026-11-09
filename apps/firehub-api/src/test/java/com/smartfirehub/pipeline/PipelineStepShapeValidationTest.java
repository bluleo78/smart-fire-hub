package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 파이프라인 저장 요청의 스텝 형태 검증(WD-585) — HTTP 왕복으로 상태 코드·문구를 고정한다.
 *
 * <p>결함: SQL 스텝의 {@code scriptContent} 가 null(누락)이면 스텝 참조 치환에서 NPE 가 나 500 이었고, PYTHON 스텝은 빈 스크립트로도
 * 저장(201)됐으며, {@code "steps":[null]} 은 DAG 검증에서 NPE 로 500 이었다. 이제 모두 400 + 한국어 사유다.
 * 생성(POST)·수정(PUT)이 같은 서비스 검증을 타는지 함께 본다(AI 에이전트 MCP 도구도 같은 REST 경로를 부른다).
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋된다. 정리는 {@link #tearDown()}.
 */
@AutoConfigureMockMvc
class PipelineStepShapeValidationTest extends IntegrationTestBase {

  private static final String SQL_EMPTY = "SQL 스크립트가 비어 있습니다.";
  private static final String PYTHON_EMPTY = "Python 스크립트가 비어 있습니다.";
  private static final String STEP_EMPTY = "스텝 정보가 비어 있습니다.";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PipelineStepRepository stepRepository;

  private SecurityFixture fx;
  private long editor;
  private long pipelineId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd585-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");

    // 수정 경로 검증용 — 스텝 없는 파이프라인을 하나 만들어 둔다.
    MvcResult created = postPipeline(List.of());
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    pipelineId = readTree(created).get("id").asLong();
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

  // ── SQL ──────────────────────────────────────────────────────────────────

  /** SQL 스텝의 scriptContent 누락 → 생성 400 「SQL 스크립트가 비어 있습니다.」(빈 문자열과 같은 문구). */
  @Test
  void create_sqlStepWithoutScript_returns400() throws Exception {
    assertBadRequest(postPipeline(List.of(step("s1", "SQL", null))), SQL_EMPTY);
  }

  /** 빈 문자열 SQL 은 예전부터 400 이었다 — 문구가 null 과 같은지 고정한다. */
  @Test
  void create_sqlStepWithBlankScript_returns400() throws Exception {
    assertBadRequest(postPipeline(List.of(step("s1", "SQL", "   "))), SQL_EMPTY);
  }

  /** 수정 경로도 같은 400 이고, 파이프라인은 바뀌지 않는다(같은 트랜잭션 롤백). */
  @Test
  void update_sqlStepWithoutScript_returns400() throws Exception {
    assertBadRequest(putPipeline(List.of(step("s1", "SQL", null))), SQL_EMPTY);
    assertThat(detail().get("steps")).isEmpty();
    // 같은 PUT 이 바꾸려던 설명도 그대로다 — 거절이 쓰기 전체를 롤백했다.
    assertThat(detail().get("description").asText()).isEqualTo("WD-585");
  }

  // ── PYTHON ───────────────────────────────────────────────────────────────

  /** PYTHON 스텝의 scriptContent 누락 → 예전엔 201 저장, 이제 400 「Python 스크립트가 비어 있습니다.」. */
  @Test
  void create_pythonStepWithoutScript_returns400() throws Exception {
    assertBadRequest(postPipeline(List.of(step("p1", "PYTHON", null))), PYTHON_EMPTY);
  }

  /** 공백만 있는 PYTHON 스크립트도 빈 스크립트다. */
  @Test
  void create_pythonStepWithBlankScript_returns400() throws Exception {
    assertBadRequest(postPipeline(List.of(step("p1", "PYTHON", " \n\t "))), PYTHON_EMPTY);
  }

  @Test
  void update_pythonStepWithoutScript_returns400() throws Exception {
    assertBadRequest(putPipeline(List.of(step("p1", "PYTHON", null))), PYTHON_EMPTY);
    assertThat(detail().get("steps")).isEmpty();
    // 같은 PUT 이 바꾸려던 설명도 그대로다 — 거절이 쓰기 전체를 롤백했다.
    assertThat(detail().get("description").asText()).isEqualTo("WD-585");
  }

  // ── null 원소 ────────────────────────────────────────────────────────────

  /** {@code "steps":[null]} → 예전엔 DAG 검증 NPE 로 500, 이제 400 + 몇 번째 스텝인지. */
  @Test
  void create_nullStepElement_returns400WithPosition() throws Exception {
    List<Object> steps = new ArrayList<>();
    steps.add(null);
    MvcResult result = postPipeline(steps);
    assertBadRequest(result, STEP_EMPTY);
    assertThat(readTree(result).get("message").asText()).contains("1번째");
  }

  /** 수정 경로 — 이름 매칭(steps.get(i).name())보다 먼저 막혀 NPE 가 나지 않는다. 두 번째 원소가 null 이면 「2번째」. */
  @Test
  void update_nullStepElement_returns400WithPosition() throws Exception {
    List<Object> steps = new ArrayList<>();
    steps.add(step("p1", "PYTHON", "print(1)"));
    steps.add(null);
    MvcResult result = putPipeline(steps);
    assertBadRequest(result, STEP_EMPTY);
    assertThat(readTree(result).get("message").asText()).contains("2번째");
    assertThat(detail().get("steps")).isEmpty();
  }

  // ── 판정 순서(리뷰) ──────────────────────────────────────────────────────

  /** 없는 파이프라인 수정은 스텝 형태와 무관하게 404 — 형태 검사는 존재 확인 뒤에 돈다. */
  @Test
  void update_missingPipeline_withNullStep_returns404() throws Exception {
    List<Object> steps = new ArrayList<>();
    steps.add(null);
    assertStatus(putPipeline(Long.MAX_VALUE, steps, null), 404);
  }

  /** 낡은 기준 버전의 저장은 스텝 형태 오류보다 먼저 409 EDIT_CONFLICT(WD-353 순서 유지). */
  @Test
  void update_staleVersion_withBlankPythonStep_returns409() throws Exception {
    assertStatus(
        putPipeline(pipelineId, List.of(step("p1", "PYTHON", null)), "2000-01-01T00:00:00"), 409);
  }

  /** 이름 중복은 스텝 형태 오류보다 먼저 409. */
  @Test
  void create_duplicateName_withBlankPythonStep_returns409() throws Exception {
    String name = detail().get("name").asText();
    assertStatus(postPipeline(name, List.of(step("p1", "PYTHON", null))), 409);
  }

  // ── 이미 저장된 빈 PYTHON 스텝(왕복 보존) ──────────────────────────────────

  /**
   * 규칙 전에 빈 스크립트로 저장된 PYTHON 스텝은 그대로 되돌려 보내면 저장된다 — 설명만 고친 저장이 막히면 파이프라인을 고칠 수 없다(WD-426 의 "새·바뀐
   * 스텝만" 규칙과 같은 이유). API 가 이제 빈 PYTHON 을 거부하므로 리포지토리로 직접 넣는다.
   */
  @Test
  void update_existingBlankPythonStepSentBackBlank_returns200() throws Exception {
    Long stepId =
        inTenantFixture(
            () ->
                stepRepository.saveStep(
                    pipelineId,
                    new PipelineStepRequest("p1", null, "PYTHON", "", null, List.of(), List.of()),
                    0));
    Map<String, Object> s = step("p1", "PYTHON", "  ");
    s.put("id", stepId);
    assertStatus(putPipeline(List.of(s)), 200);
    assertThat(detail().get("description").asText()).isEqualTo("WD-585 수정");
  }

  /** 내용이 있던 PYTHON 스텝을 빈 스크립트로 바꾸는 것은 "바뀐 스텝"이라 400. */
  @Test
  void update_existingPythonStepClearedToBlank_returns400() throws Exception {
    assertStatus(putPipeline(List.of(step("p1", "PYTHON", "print(1)"))), 200);
    long stepId = detail().get("steps").get(0).get("id").asLong();
    Map<String, Object> s = step("p1", "PYTHON", null);
    s.put("id", stepId);
    assertBadRequest(putPipeline(List.of(s)), PYTHON_EMPTY);
  }

  // ── 이름 없는 스텝의 문구 ─────────────────────────────────────────────────

  /** 이름이 없으면 「스텝 'null'」 대신 몇 번째 스텝인지로 가리킨다. */
  @Test
  void create_unnamedSqlStepWithoutScript_messageUsesPosition() throws Exception {
    MvcResult result =
        postPipeline(List.of(step("ok", "PYTHON", "print(1)"), step(null, "SQL", null)));
    assertBadRequest(result, "2번째 스텝: " + SQL_EMPTY);
    assertThat(readTree(result).get("message").asText()).doesNotContain("'null'");
  }

  /**
   * 이름이 공백인 PYTHON 스텝은 빈 스크립트보다 이름 검사(WD-586 의 validateDAG)가 먼저 걸린다 — 「N번째 스텝」 대체 문구까지 가지 않는다. 그
   * 문구가 실제로 쓰이는 경로는 null 원소({@code steps:[null]})와 이름 null 인 SQL 스텝(위)이다.
   */
  @Test
  void create_blankNamePythonStepWithoutScript_nameCheckComesFirst() throws Exception {
    MvcResult result = postPipeline(List.of(step("  ", "PYTHON", null)));
    assertBadRequest(result, "스텝 이름을 입력하세요.");
  }

  // ── 치환 NPE 근본 방어 ────────────────────────────────────────────────────

  /** 스텝 참조 치환은 null 을 그대로 돌려준다 — 저장 검증 밖의 다른 호출부(참조 집계 등)도 null 본문에서 NPE 가 나지 않게. */
  @Test
  void substituteStepReferencesForValidation_null_returnsNull() {
    assertThat(PipelineService.substituteStepReferencesForValidation(null)).isNull();
  }

  // ── 기존 동작 유지 ────────────────────────────────────────────────────────

  /** scriptType 이 없는 스텝은 형태 검사 대상이 아니다 — 기존 동작 그대로이고 500 이 아니어야 한다. */
  @Test
  void create_stepWithoutScriptType_isNotServerError() throws Exception {
    MvcResult result = postPipeline(List.of(step("t1", null, null)));
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isLessThan(500);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  private void assertStatus(MvcResult result, int expected) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(expected);
  }

  /** 최소 스텝 본문 — scriptContent 가 null 이면 키 자체를 뺀다(누락 요청 재현). */
  private static Map<String, Object> step(String name, String scriptType, String scriptContent) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", name);
    s.put("scriptType", scriptType);
    if (scriptContent != null) {
      s.put("scriptContent", scriptContent);
    }
    return s;
  }

  private void assertBadRequest(MvcResult result, String expectedMessage) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(400);
    assertThat(readTree(result).get("message").asText()).contains(expectedMessage);
  }

  private MvcResult postPipeline(List<?> steps) throws Exception {
    return postPipeline("wd585-" + System.nanoTime(), steps);
  }

  private MvcResult postPipeline(String name, List<?> steps) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "WD-585");
    body.put("steps", steps);
    return mockMvc
        .perform(
            post("/api/v1/pipelines")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private MvcResult putPipeline(List<?> steps) throws Exception {
    return putPipeline(pipelineId, steps, null);
  }

  /** 수정 요청 — expectedUpdatedAt 이 null 이면 뺀다(버전 비교 없음). 없는 id 로도 보낼 수 있게 이름은 고정값을 쓴다. */
  private MvcResult putPipeline(long id, List<?> steps, String expectedUpdatedAt) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", id == pipelineId ? detail().get("name").asText() : "wd585-missing");
    body.put("description", "WD-585 수정");
    body.put("steps", steps);
    if (expectedUpdatedAt != null) {
      body.put("expectedUpdatedAt", expectedUpdatedAt);
    }
    return mockMvc
        .perform(
            put("/api/v1/pipelines/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private JsonNode detail() throws Exception {
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
