package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
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
 * 파이프라인 편집 낙관적 잠금(WD-353) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: 수정 요청이 전체 교체라 두 사람(또는 두 탭)이 같은 파이프라인을 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다. 이제 웹이 편집 기준
 * 버전(상세의 {@code updatedAt})을 {@code expectedUpdatedAt} 으로 보내고, 다르면 409 EDIT_CONFLICT 로 거부한다.
 *
 * <p>서비스 호출이 아니라 MockMvc 로 하는 이유: 상세 응답의 {@code updatedAt} 문자열을 그대로 되돌려 보냈을 때 같다고 판정되는지(직렬화 정밀도 —
 * PG 마이크로초 ↔ JSON ↔ LocalDateTime)가 이 장치의 핵심이라, 객체를 직접 넘기는 테스트로는 확인되지 않는다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋되어야 다음 요청이 바뀐 버전을 본다. 정리는 {@link
 * #tearDown()}.
 */
@AutoConfigureMockMvc
class PipelineEditConflictTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;
  private long pipelineId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd353-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new CreatePipelineRequest(
                                "wd353-" + System.nanoTime(), "처음 설명", List.of()))))
            .andReturn();
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

  /** 상세의 updatedAt 문자열을 그대로 보내면 통과하고, 응답의 새 updatedAt 으로 이어 저장해도 통과한다(자기 저장과 충돌하지 않는다). */
  @Test
  void saveWithDetailVersion_succeeds_andReturnedVersionChainsNextSave() throws Exception {
    String v1 = detail().get("updatedAt").asText();

    MvcResult first = putPipeline("설명 A", v1);
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    String v2 = readTree(first).get("updatedAt").asText();
    assertThat(v2).as("응답은 새 버전을 돌려준다").isNotEqualTo(v1);
    assertThat(v2).as("응답 버전은 저장된 값(상세)과 같아야 한다").isEqualTo(detail().get("updatedAt").asText());

    MvcResult second = putPipeline("설명 B", v2);
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    assertThat(detail().get("description").asText()).isEqualTo("설명 B");
  }

  /** 결함 재현: 편집 시작 뒤 다른 곳에서 저장됐으면 낡은 버전의 저장은 409 EDIT_CONFLICT 이고, 먼저 저장된 변경이 그대로 남는다. */
  @Test
  void saveWithStaleVersion_rejected409_andEarlierSaveKept() throws Exception {
    String base = detail().get("updatedAt").asText();

    // 다른 탭이 먼저 같은 기준으로 저장했다.
    assertThat(putPipeline("다른 탭의 설명", base).getResponse().getStatus()).isEqualTo(200);

    MvcResult stale = putPipeline("내 설명", base);
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    JsonNode body = readTree(stale);
    assertThat(body.get("code").asText()).isEqualTo(EditConflictException.CODE);
    assertThat(body.get("message").asText()).contains("다른 곳에서 변경되었습니다");
    assertThat(detail().get("description").asText())
        .as("먼저 저장된 변경이 되돌려지지 않는다")
        .isEqualTo("다른 탭의 설명");
  }

  /** 기준 버전 없이 보내면(AI 도구 등 기존 호출부, 「내 변경으로 덮어쓰기」) 비교하지 않고 저장된다. */
  @Test
  void saveWithoutVersion_skipsCheck_andOverwrites() throws Exception {
    String base = detail().get("updatedAt").asText();
    assertThat(putPipeline("다른 탭의 설명", base).getResponse().getStatus()).isEqualTo(200);

    MvcResult overwrite = putPipeline("덮어쓴 설명", null);
    assertThat(overwrite.getResponse().getStatus()).isEqualTo(200);
    assertThat(detail().get("description").asText()).isEqualTo("덮어쓴 설명");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** 이름은 그대로 두고 설명만 바꾸는 수정 요청 — expectedUpdatedAt 은 상세에서 받은 문자열을 그대로 싣는다(null 이면 뺀다). */
  private MvcResult putPipeline(String description, String expectedUpdatedAt) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", detail().get("name").asText());
    body.put("description", description);
    body.put("steps", List.of());
    if (expectedUpdatedAt != null) {
      body.put("expectedUpdatedAt", expectedUpdatedAt);
    }
    return mockMvc
        .perform(
            put("/api/v1/pipelines/" + pipelineId)
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
