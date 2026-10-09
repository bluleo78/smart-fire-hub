package com.smartfirehub.securitylevel.ai;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.service.PipelineExecutionService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 스펙 §4.3: AI 대행 요청(MCP 실행 조회)이 받는 파이프라인 실행 기록의 원문 오류·로그는 외부 LLM 으로 간다 — 원문에는 행 값이 실릴 수 있으므로 스텝이
 * 다루는 데이터셋이 AI 불허 등급이면 가림 문구로 바뀐다(예외 대신 가림 — 실행 기록 자체는 200). 같은 데이터·같은 조회자의 웹(JWT) 요청은 원문 그대로(비AI
 * 동작 불변), AI 허용 등급(공개)은 AI 요청에서도 원문이다.
 *
 * <p>조회자는 실행 주체가 아닌 민감 자격 사용자다 — 실행 주체 본인 허용(undeterminedAllowed)이 판정에 섞이지 않게.
 */
@AutoConfigureMockMvc
class AiPipelineExecutionErrorTest extends IntegrationTestBase {

  /** application-test.yml 의 agent.internal-token. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private DatasetService datasetService;
  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private TenantSettingsRepository tenantSettings;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> pipelines = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String m;
  private long owner;
  private long runner;
  private long viewer;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "ape" + System.nanoTime();
    owner = fx.createUser("ape_owner");
    users.add(owner);
    runner = userAt("민감");
    viewer = userAt("민감");
    // 공유 DB 에 남은 자체 호스팅 선언이 있으면 민감이 AI 허용이 돼 가림 단언이 공허해진다 — 기본(외부)으로 고정.
    clearHosting();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
    for (long p : pipelines) {
      List<Long> temps =
          inTenantFixture(
              () ->
                  dsl.select(DATASET.ID)
                      .from(DATASET)
                      .where(DATASET.TABLE_NAME.like("ptmp\\_" + p + "\\_%"))
                      .fetch(DATASET.ID));
      temps.forEach(this::deleteQuietly);
      try {
        pipelineService.deletePipeline(p);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다.
      }
    }
    datasets.forEach(this::deleteQuietly);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void deleteQuietly(long datasetId) {
    try {
      datasetService.deleteDataset(datasetId);
    } catch (Exception ignored) {
      // 정리 실패는 다음 정리에 맡긴다.
    }
  }

  private void clearHosting() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(EmbeddingConfigService.KEY);
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격의 역할 하나만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("ape_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "ape_r_" + System.nanoTime(),
            fx.levelId(level),
            "pipeline:read",
            "pipeline:write",
            "pipeline:execute");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /** 지정 등급의 실제 테이블 데이터셋 + 정수 캐스트에 실패하는 표지 행. 반환은 한정 테이블 이름. */
  private String tableAt(String level, String suffix) {
    String t = m + "_" + suffix;
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    t,
                    t,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                owner)
            .id();
    datasets.add(id);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(id))
                .execute());
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO " + DataSchema.qualify(t) + " (v) VALUES (?)", "SECRETVALUE42"));
    return DataSchema.qualify(t);
  }

  /** SQL 스텝 하나짜리 파이프라인을 실행 주체로 실행하고 실패로 끝나기를 기다린다. 반환은 실행 기록 URL. */
  private String failedRun(String sql) throws InterruptedException {
    long p =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(
                    "APE " + m + " " + System.nanoTime(),
                    "S3 AI TC",
                    List.of(
                        new PipelineStepRequest(
                            "step", null, "SQL", sql, null, null, null, "REPLACE"))),
                runner)
            .id();
    pipelines.add(p);
    long exec = executionService.executePipeline(p, runner);
    assertThat(waitForEnd(exec)).isEqualTo("FAILED");
    return "/api/v1/pipelines/" + p + "/executions/" + exec;
  }

  /** ai-agent 의 MCP 대행 호출 재현(내부 토큰 + 대행 사용자·테넌트). */
  private MockHttpServletRequestBuilder ai(String url) {
    return get(url)
        .header("Authorization", "Internal " + INTERNAL_TOKEN)
        .header(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(viewer))
        .header(InternalCallHeaders.ON_BEHALF_OF_TENANT, String.valueOf(DEFAULT_TEST_TENANT_ID));
  }

  /** 같은 조회자의 웹 요청(JWT) — 비AI 대조군. */
  private MockHttpServletRequestBuilder web(String url) {
    return get(url)
        .header(
            "Authorization",
            "Bearer " + jwt.generateAccessToken(viewer, "ape" + viewer, DEFAULT_TEST_TENANT_ID));
  }

  /** 첫 스텝 실행 기록(200 이어야 한다 — 가림은 예외가 아니라 문구 교체). */
  private JsonNode firstStep(MockHttpServletRequestBuilder b) throws Exception {
    MockHttpServletResponse r = mockMvc.perform(b).andReturn().getResponse();
    r.setCharacterEncoding("UTF-8");
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return om.readTree(r.getContentAsString()).get("stepExecutions").get(0);
  }

  /** AI 요청은 가림 문구, 같은 조회자의 웹 요청은 행 값이 실린 원문(양성 대조 — 원문이 애초에 값을 싣지 않았다는 공허한 통과 배제). */
  private void assertMaskedForAiButRawForWeb(String url) throws Exception {
    JsonNode aiStep = firstStep(ai(url));
    assertThat(aiStep.get("errorMessage").asText())
        .isEqualTo(PipelineService.WITHHELD_STEP_ERROR_MESSAGE)
        .doesNotContain("SECRETVALUE42");
    assertThat(aiStep.get("errorMasked").asBoolean()).isTrue();
    JsonNode webStep = firstStep(web(url));
    assertThat(webStep.get("errorMessage").asText()).contains("SECRETVALUE42");
    assertThat(webStep.get("errorMasked").asBoolean()).isFalse();
  }

  /** SELECT 스텝(러너 TEMP 출력이 민감으로 오름) — 출력·SQL 참조 모두 민감. */
  @Test
  void selectOnSensitive_aiRequest_masksRawError_webShowsRaw() throws Exception {
    String t = tableAt("민감", "sel");
    assertMaskedForAiButRawForWeb(failedRun("SELECT v::int AS n FROM " + t));
  }

  /** 출력 없는 DML 스텝 — 출력 판정이 없으므로 SQL 참조(쓰기 대상) id 가 AI 판정 집합에 들어가야 가려진다. */
  @Test
  void dmlWithoutOutputOnSensitive_aiRequest_masksRawError_webShowsRaw() throws Exception {
    String t = tableAt("민감", "dml");
    assertMaskedForAiButRawForWeb(failedRun("UPDATE " + t + " SET v = ((v)::int + 1)::text"));
  }

  /** AI 허용 등급(공개)은 AI 요청에서도 원문 — 가림이 AI 요청 전체가 아니라 등급 정책 때문임을 보인다. */
  @Test
  void selectOnPublic_aiRequest_showsRawError() throws Exception {
    String t = tableAt("공개", "pub");
    JsonNode aiStep = firstStep(ai(failedRun("SELECT v::int AS n FROM " + t)));
    assertThat(aiStep.get("errorMessage").asText()).contains("SECRETVALUE42");
    assertThat(aiStep.get("errorMasked").asBoolean()).isFalse();
  }

  private String waitForEnd(Long executionId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    String status = null;
    while (System.currentTimeMillis() < deadline) {
      status =
          inTenantFixture(
              () ->
                  dsl.fetchOne("SELECT status FROM pipeline_execution WHERE id = ?", executionId)
                      .get(0, String.class));
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        break;
      }
      Thread.sleep(100);
    }
    return status;
  }
}
