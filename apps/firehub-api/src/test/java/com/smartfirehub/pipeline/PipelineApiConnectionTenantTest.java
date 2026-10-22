package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * WD-176 — 파이프라인 스텝이 <b>다른 워크스페이스의 API 연결</b>을 참조로 저장할 수 없다.
 *
 * <p>결함: 워크스페이스 A 사용자가 스텝 {@code apiConnectionId} 에 워크스페이스 B 의 연결 번호를 넣으면 201 로 저장됐다. 외래키 검사는 RLS 를
 * 우회해 남의 행도 통과시키기 때문이다. 그 결과 (1) B 관리자 화면에는 참조 0건인데 삭제는 영영 409, (2) 있는 번호는 201·없는 번호는 409(FK)로 갈려
 * 남의 연결 번호 존재를 떠볼 수 있었다. 저장 전에 RLS 가 걸린 조회로 연결이 보이는지 확인해, 다른 워크스페이스 번호와 없는 번호를 <b>같은 400·같은 문구</b>로
 * 거절하는지를 실제 HTTP(MockMvc + 실제 JWT)로 고정한다.
 *
 * <p>구성: 워크스페이스 A = 기본 테스트 테넌트(1, 이미 역할·등급·데이터 스키마가 프로비저닝돼 있다). 편집자는 A 의 ADMIN. 워크스페이스 B 는 새 테넌트로
 * 만들고 연결 하나(connB)만 심는다. A 에도 자기 연결(connA)과 출력용 데이터셋 메타 행(물리 테이블 없음 — 저장만 보므로 충분)을 둔다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다.</b> 테스트 트랜잭션이 있으면 픽스처의 테넌트 전환(GUC)이 바깥 트랜잭션에 합류해
 * 무시되고, 요청도 같은 트랜잭션에 묶여 교차 테넌트 판정이 공허해진다. 요청은 각자 커밋되므로 정리는 {@link #tearDown()} 에서 직접 한다.
 */
@AutoConfigureMockMvc
class PipelineApiConnectionTenantTest extends IntegrationTestBase {

  /** 서버가 내려야 하는 거절 문구 — 스텝 이름이 앞에 붙는다(기존 스텝 검증 문구 관례). */
  private static final String NOT_FOUND_MESSAGE = "선택한 API 연결을 찾을 수 없습니다. 연결 목록에서 다시 선택하세요.";

  /** 없는 연결 번호 — 공유 테스트 DB 의 시퀀스가 닿지 않을 만큼 크다. */
  private static final long MISSING_CONNECTION_ID = 9_000_000_176L;

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private EncryptionService encryptionService;

  private SecurityFixture fx;

  private long tenantA;
  private long tenantB;
  private long editor;
  private Long ownerB;
  private Long connA;
  private Long connB;
  private long outputDatasetId;

  /** 이 테스트가 만든(또는 만들어졌을 수 있는) 파이프라인 — 정리 대상. */
  private final List<Long> createdPipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    tenantA = DEFAULT_TEST_TENANT_ID;

    // 편집자: A 의 ACTIVE 멤버 + ADMIN(pipeline:write·데이터셋 열람 자격).
    editor = fx.createUser("wd176-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, tenantA, "ADMIN");
    outputDatasetId =
        fx.createDatasetRow("wd176_out_" + System.nanoTime(), fx.levelId("내부"), editor);
    connA = inTenantFixture(tenantA, () -> insertConnection(editor, "WD176 A Conn"));

    // 워크스페이스 B 와 그 연결 — B 의 연결은 A 에서 보이면 안 된다(RLS).
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "wd176-b");
    ownerB = TenantRlsTestSupport.insertUser(dsl, "wd176_b_");
    connB = inTenantFixture(tenantB, () -> insertConnection(ownerB, "WD176 B Conn"));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 스텝(→ api_connection FK RESTRICT)을 먼저 지워야 연결을 지울 수 있다. pipeline 을 지우면 스텝·입력·의존이 CASCADE 로 함께
    // 지워진다.
    // 교차 참조가 새로 저장됐다면(회귀) A 의 스텝이 connB 를 물고 있으므로, 파이프라인 정리를 연결 정리보다 앞에 둔다.
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                tenantA,
                () -> {
                  for (Long id : createdPipelines) {
                    dsl.execute("delete from pipeline where id = ?", id);
                  }
                  dsl.execute("delete from pipeline where created_by = ?", editor);
                }),
        () -> inTenantFixture(tenantA, () -> deleteConnection(connA)),
        () -> inTenantFixture(tenantB, () -> deleteConnection(connB)),
        () -> fx.deleteDatasetRow(outputDatasetId),
        () -> fx.deleteUser(editor),
        () -> TenantRlsTestSupport.deleteTenants(dsl, tenantB),
        () -> TenantRlsTestSupport.deleteUser(dsl, ownerB));
  }

  // ── 생성(POST /pipelines) ───────────────────────────────────────────────────

  /** 결함 재현: 다른 워크스페이스 연결 번호로 생성하면 400·안내 문구, 아무것도 저장되지 않는다. B 는 그 연결을 지울 수 있다. */
  @Test
  void create_withOtherWorkspaceConnection_rejected400_andNothingStored() throws Exception {
    String pipelineName = uniqueName("wd176-cross");

    JsonNode body = postPipeline(pipelineName, apiStep("API 호출", connB));

    assertRejected(body, "API 호출");
    assertThat(pipelineExistsInA(pipelineName)).as("거절된 생성은 파이프라인을 남기지 않는다").isFalse();
    assertThat(stepsReferencingInA(connB)).as("A 의 어떤 스텝도 B 의 연결을 참조하지 않는다").isZero();

    // 증상 쪽 단언: 참조가 생기지 않았으므로 B 는 자기 연결을 지울 수 있다(예전에는 FK 409 로 영영 못 지웠다).
    int deleted = inTenantFixture(tenantB, () -> deleteConnection(connB));
    assertThat(deleted).as("B 는 자기 연결을 지울 수 있어야 한다").isEqualTo(1);
    connB = null;
  }

  /** 존재 오라클 차단: 없는 번호와 다른 워크스페이스 번호가 같은 상태코드·같은 message 를 받는다. */
  @Test
  void create_missingAndOtherWorkspaceConnection_sameStatusAndMessage() throws Exception {
    JsonNode cross = postPipeline(uniqueName("wd176-x"), apiStep("API 호출", connB));
    JsonNode missing =
        postPipeline(uniqueName("wd176-m"), apiStep("API 호출", MISSING_CONNECTION_ID));

    assertRejected(cross, "API 호출");
    assertRejected(missing, "API 호출");
    assertThat(cross.get("status").asInt()).isEqualTo(missing.get("status").asInt());
    assertThat(cross.get("message").asText())
        .as("없는 번호와 남의 번호가 응답 문구로 구분되면 안 된다")
        .isEqualTo(missing.get("message").asText());
    assertThat(cross.get("message").asText())
        .as("문구에 연결 번호를 싣지 않는다")
        .doesNotContain(String.valueOf(connB))
        .doesNotContain(String.valueOf(MISSING_CONNECTION_ID));
  }

  /** 스크립트 유형과 무관: SQL 스텝에 B 의 연결 번호를 실어도 같은 거절 — 리포지토리는 유형을 가리지 않고 api_connection_id 를 기록한다. */
  @Test
  void create_sqlStepCarryingOtherWorkspaceConnection_rejected400() throws Exception {
    String pipelineName = uniqueName("wd176-sql");
    PipelineStepRequest sqlStep =
        new PipelineStepRequest(
            "SQL 스텝",
            null,
            "SQL",
            "SELECT 1 AS x",
            null,
            List.of(),
            List.of(),
            "REPLACE",
            null,
            null,
            null,
            connB);

    JsonNode body = postPipeline(pipelineName, sqlStep);

    assertRejected(body, "SQL 스텝");
    assertThat(pipelineExistsInA(pipelineName)).isFalse();
    assertThat(stepsReferencingInA(connB)).isZero();
  }

  /** 회귀: 같은 워크스페이스 연결은 그대로 저장된다. null(인라인 인증)도 허용된다. */
  @Test
  void create_withOwnConnectionOrNull_succeeds() throws Exception {
    MvcResult own =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new CreatePipelineRequest(
                                uniqueName("wd176-own"), null, List.of(apiStep("API 호출", connA))))))
            .andReturn();
    assertThat(own.getResponse().getStatus())
        .as(own.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    long ownId = readTree(own).get("id").asLong();
    createdPipelines.add(ownId);
    assertThat(stepConnectionIds(ownId)).containsExactly(connA);

    MvcResult inline =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new CreatePipelineRequest(
                                uniqueName("wd176-inline"),
                                null,
                                List.of(apiStep("API 호출", null))))))
            .andReturn();
    assertThat(inline.getResponse().getStatus()).isEqualTo(201);
    createdPipelines.add(readTree(inline).get("id").asLong());
  }

  // ── 수정(PUT /pipelines/{id}) ───────────────────────────────────────────────

  /** 수정 경로도 같은 거절 — 기존 스텝의 연결은 바뀌지 않는다(없는 번호도 같은 응답). */
  @Test
  void update_withOtherWorkspaceOrMissingConnection_rejected400_andStepUnchanged()
      throws Exception {
    String pipelineName = uniqueName("wd176-upd");
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new CreatePipelineRequest(
                                pipelineName, null, List.of(apiStep("API 호출", connA))))))
            .andReturn();
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    long pipelineId = readTree(created).get("id").asLong();
    createdPipelines.add(pipelineId);

    JsonNode cross = putPipeline(pipelineId, pipelineName, apiStep("API 호출", connB));
    JsonNode missing =
        putPipeline(pipelineId, pipelineName, apiStep("API 호출", MISSING_CONNECTION_ID));

    assertRejected(cross, "API 호출");
    assertRejected(missing, "API 호출");
    assertThat(cross.get("message").asText()).isEqualTo(missing.get("message").asText());
    assertThat(stepConnectionIds(pipelineId)).as("거절된 수정은 기존 연결을 바꾸지 않는다").containsExactly(connA);
    assertThat(stepsReferencingInA(connB)).isZero();

    // 회귀: 같은 워크스페이스 연결로의 수정은 그대로 된다(인라인으로 바꾸는 것도).
    MvcResult ok =
        mockMvc
            .perform(
                put("/api/v1/pipelines/" + pipelineId)
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            new UpdatePipelineRequest(
                                pipelineName, null, true, List.of(apiStep("API 호출", null))))))
            .andReturn();
    assertThat(ok.getResponse().getStatus())
        .as(ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isBetween(200, 204);
    assertThat(stepConnectionIds(pipelineId)).containsExactly((Long) null);
  }

  // ── API 가져오기(POST /datasets/{id}/api-import) ─────────────────────────────

  /** API 가져오기도 같은 저장 경로를 탄다 — 남의 번호·없는 번호 같은 400, 파이프라인 생성 안 됨. 자기 연결은 201. */
  @Test
  void apiImport_withOtherWorkspaceOrMissingConnection_rejected400_ownSucceeds() throws Exception {
    String crossName = uniqueName("wd176-imp-x");
    String missingName = uniqueName("wd176-imp-m");

    JsonNode cross = postApiImport(crossName, connB);
    JsonNode missing = postApiImport(missingName, MISSING_CONNECTION_ID);

    // ApiImportService 가 만드는 스텝 이름은 "API Data Collection" 이다.
    assertRejected(cross, "API Data Collection");
    assertRejected(missing, "API Data Collection");
    assertThat(cross.get("message").asText()).isEqualTo(missing.get("message").asText());
    assertThat(pipelineExistsInA(crossName)).isFalse();
    assertThat(pipelineExistsInA(missingName)).isFalse();
    assertThat(stepsReferencingInA(connB)).isZero();

    MvcResult own =
        mockMvc
            .perform(
                post("/api/v1/datasets/" + outputDatasetId + "/api-import")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(apiImportBody(uniqueName("wd176-imp-own"), connA)))
            .andReturn();
    assertThat(own.getResponse().getStatus())
        .as(own.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    long ownPipelineId = readTree(own).get("pipelineId").asLong();
    createdPipelines.add(ownPipelineId);
    assertThat(stepConnectionIds(ownPipelineId)).containsExactly(connA);
  }

  // ── 단언·요청 헬퍼 ───────────────────────────────────────────────────────────

  /** 400 + 「스텝 '<이름>': <안내>」 문구. */
  private static void assertRejected(JsonNode body, String stepName) {
    assertThat(body.get("status").asInt()).as(body.toString()).isEqualTo(400);
    assertThat(body.get("message").asText())
        .isEqualTo("스텝 '" + stepName + "': " + NOT_FOUND_MESSAGE);
  }

  /** API_CALL 스텝 — 출력은 A 의 데이터셋 메타 행(자동 TEMP 를 만들지 않게). 연결이 있으면 path, 없으면 customUrl. */
  private PipelineStepRequest apiStep(String stepName, Long connectionId) {
    Map<String, Object> apiConfig =
        connectionId != null
            ? Map.of("path", "/items", "method", "GET", "dataPath", "$")
            : Map.of("customUrl", "https://example.com/items", "method", "GET", "dataPath", "$");
    return new PipelineStepRequest(
        stepName,
        null,
        "API_CALL",
        null,
        outputDatasetId,
        List.of(),
        List.of(),
        "REPLACE",
        apiConfig,
        null,
        null,
        connectionId);
  }

  private JsonNode postPipeline(String pipelineName, PipelineStepRequest step) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(new CreatePipelineRequest(pipelineName, null, List.of(step)))))
            .andReturn();
    rememberIfCreated(result, "id");
    return errorBody(result);
  }

  private JsonNode putPipeline(long pipelineId, String pipelineName, PipelineStepRequest step)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                put("/api/v1/pipelines/" + pipelineId)
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(new UpdatePipelineRequest(pipelineName, null, true, List.of(step)))))
            .andReturn();
    return errorBody(result);
  }

  private JsonNode postApiImport(String pipelineName, Long connectionId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/datasets/" + outputDatasetId + "/api-import")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(apiImportBody(pipelineName, connectionId)))
            .andReturn();
    rememberIfCreated(result, "pipelineId");
    return errorBody(result);
  }

  /** apiConfig 를 비우지 않는다 — 비면 「apiConfig 필요」 검사가 먼저 걸려 엉뚱한 이유로 400 이 난다. */
  private String apiImportBody(String pipelineName, Long connectionId) throws Exception {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("pipelineName", pipelineName);
    body.put("apiConfig", Map.of("path", "/items", "method", "GET", "dataPath", "$"));
    body.put("apiConnectionId", connectionId);
    body.put("loadStrategy", "REPLACE");
    body.put("executeImmediately", false);
    return objectMapper.writeValueAsString(body);
  }

  /** 변이(검사 제거) 시 생성이 성공하면 정리 대상에 올린다 — 그렇지 않으면 남은 스텝이 연결 정리를 FK 로 막는다. */
  private void rememberIfCreated(MvcResult result, String idField) throws Exception {
    if (result.getResponse().getStatus() == 201) {
      createdPipelines.add(readTree(result).get(idField).asLong());
    }
  }

  /**
   * 오류 본문 + 상태코드. 성공(201·204)처럼 본문이 ErrorResponse 가 아닌 경우에도 단언이 상태코드로 실패하도록 {@code status} 를 채워 둔다.
   */
  private JsonNode errorBody(MvcResult result) throws Exception {
    String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    var node =
        raw.isBlank()
            ? objectMapper.createObjectNode()
            : (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(raw);
    node.put("status", result.getResponse().getStatus());
    if (!node.has("message")) {
      node.put("message", "");
    }
    return node;
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, tenantA);
  }

  private static String uniqueName(String prefix) {
    return prefix + "-" + System.nanoTime();
  }

  // ── DB 관측(워크스페이스 A 컨텍스트) ──────────────────────────────────────────

  private boolean pipelineExistsInA(String pipelineName) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetchExists(
                dsl.selectOne()
                    .from(table(name("pipeline")))
                    .where(field(name("name"), String.class).eq(pipelineName))));
  }

  private int stepsReferencingInA(Long connectionId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetchCount(
                dsl.selectOne()
                    .from(table(name("pipeline_step")))
                    .where(field(name("api_connection_id"), Long.class).eq(connectionId))));
  }

  private List<Long> stepConnectionIds(long pipelineId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.select(field(name("api_connection_id"), Long.class))
                .from(table(name("pipeline_step")))
                .where(field(name("pipeline_id"), Long.class).eq(pipelineId))
                .fetch(field(name("api_connection_id"), Long.class)));
  }

  // ── 픽스처 ─────────────────────────────────────────────────────────────────

  /** api_connection 한 건 — tenant_id 는 현재 픽스처 트랜잭션의 GUC DEFAULT 로 채워진다. */
  private Long insertConnection(Long ownerUserId, String connName) {
    String encrypted = encryptionService.encrypt("{\"apiKey\":\"dummy-key\"}");
    return dsl.insertInto(table(name("api_connection")))
        .set(field(name("name"), String.class), connName + "-" + System.nanoTime())
        .set(field(name("auth_type"), String.class), "API_KEY")
        .set(field(name("auth_config"), String.class), encrypted)
        .set(field(name("created_by"), Long.class), ownerUserId)
        .set(field(name("base_url"), String.class), "https://example.com")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private int deleteConnection(Long id) {
    if (id == null) {
      return 0;
    }
    return dsl.deleteFrom(table(name("api_connection")))
        .where(field(name("id"), Long.class).eq(id))
        .execute();
  }
}
