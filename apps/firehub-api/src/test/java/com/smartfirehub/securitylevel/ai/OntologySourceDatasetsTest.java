package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 보충 스펙 §2.2 WD-31⑤: 온톨로지 생성 요청의 sourceDatasetIds 는 VIEW + AI(공유 호스팅 규칙) + SHARE 판정을 통과해야
 * graph_ontology_source 에 기록된다. 숨김 id 는 없는 id 와 같은 404(존재 은닉), 볼 수 있으나 정책 위반이면 403 POLICY_BLOCKED
 * 이고 온톨로지는 만들어지지 않는다.
 */
@AutoConfigureMockMvc
class OntologySourceDatasetsTest extends IntegrationTestBase {

  /** application-test.yml 의 agent.internal-token. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ObjectMapper om;
  @Autowired private TenantSettingsRepository tenantSettings;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<String> domains = new ArrayList<>();
  private long userId;
  private String m;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "osd" + System.nanoTime();
    userId = userAt("민감");
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
  }

  @AfterEach
  void tearDown() {
    // MockMvc 요청 필터가 끝나며 TenantContext 를 비우므로 정리 전에 다시 세운다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
    // graph_ontology_source 는 ontology ON DELETE CASCADE 라 온톨로지만 지우면 출처도 함께 지워진다.
    domains.forEach(
        d -> inTenantFixture(() -> dsl.execute("delete from ontology where domain = ?", d)));
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void clearHosting() {
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(EmbeddingConfigService.KEY);
  }

  /** 채팅·임베딩을 자체 호스팅으로 선언한다(forShare 는 둘 다 자체 호스팅이어야 자체 호스팅) — AI 는 통과시키고 SHARE 만 남겨 본다. */
  private void declareSelfHosted() {
    tenantSettings.upsert(
        AiCredentialSlot.CHAT.key(),
        "{\"v\":1,\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"corp\","
            + "\"baseURL\":\"http://10.0.0.5/v1\",\"hosting\":\"SELF_HOSTED\"},\"secret\":{}}",
        null);
    tenantSettings.upsert(
        EmbeddingConfigService.KEY,
        "{\"v\":1,\"provider\":\"OLLAMA\",\"model\":\"bge-m3\",\"baseUrl\":\"http://ollama:11434\","
            + "\"dimension\":1024,\"hosting\":\"SELF_HOSTED\",\"secret\":{\"apiKey\":\"\"}}",
        null);
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격 + 온톨로지 쓰기 권한만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("osd");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "osd_r_" + System.nanoTime(), fx.levelId(level), "dataset:read", "ontology:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private long ds(String level) {
    long id = fx.createDatasetRow(m + "_" + datasets.size(), fx.levelId(level), userId);
    datasets.add(id);
    return id;
  }

  /** AI 대행 요청(Internal + X-On-Behalf-Of, 목적 헤더 없음 = 채팅)으로 draft 온톨로지를 만든다. */
  private MockHttpServletResponse create(long asUser, List<Long> sources) throws Exception {
    String domain = "출처 " + m + " " + System.nanoTime();
    domains.add(domain);
    String body =
        om.writeValueAsString(
            Map.of(
                "domain",
                domain,
                "entities",
                List.of(),
                "relations",
                List.of(),
                "status",
                "draft",
                "sourceDatasetIds",
                sources));
    return mockMvc
        .perform(
            post("/api/v1/ontologies")
                .header("Authorization", "Internal " + INTERNAL_TOKEN)
                .header(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(asUser))
                .header(
                    InternalCallHeaders.ON_BEHALF_OF_TENANT, String.valueOf(DEFAULT_TEST_TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  /** 이 테스트가 만든 온톨로지 개수 — 차단 시 아무것도 만들어지지 않았는지 본다. */
  private int createdOntologies() {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                dsl.selectOne()
                    .from(table(name("ontology")))
                    .where(field(name("domain"), String.class).in(domains))));
  }

  private List<Long> recordedSources(long ontologyId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "select dataset_id from graph_ontology_source where ontology_id = ?",
                    ontologyId)
                .getValues(0, Long.class));
  }

  @Test
  void allowedSources_areRecorded() throws Exception {
    long pub = ds("공개");
    long internal = ds("내부");
    MockHttpServletResponse r = create(userId, List.of(pub, internal));
    assertThat(r.getStatus()).isEqualTo(201);
    long id = Long.parseLong(r.getContentAsString());
    assertThat(recordedSources(id)).containsExactlyInAnyOrder(pub, internal);
  }

  /** 출처를 생략한 기존 호출(하위호환)은 출처 없이 만들어진다. */
  @Test
  void noSources_createsWithoutRecords() throws Exception {
    MockHttpServletResponse r = create(userId, List.of());
    assertThat(r.getStatus()).isEqualTo(201);
    assertThat(recordedSources(Long.parseLong(r.getContentAsString()))).isEmpty();
  }

  @Test
  void aiBlockedSource_isPolicyBlocked_andNothingCreated() throws Exception {
    long pub = ds("공개");
    long sens = ds("민감"); // 외부 호스팅 기본(선언 없음) → 민감 SELF_HOSTED_ONLY 불허
    MockHttpServletResponse r = create(userId, List.of(pub, sens));
    assertThat(r.getStatus()).isEqualTo(403);
    JsonNode body = om.readTree(r.getContentAsString());
    assertThat(body.path("code").asText()).isEqualTo("POLICY_BLOCKED");
    assertThat(body.path("errors").path("action").asText()).isEqualTo("AI");
    assertThat(body.path("errors").path("levelName").asText()).isEqualTo("민감");
    assertThat(createdOntologies()).isZero();
  }

  /**
   * 자체 호스팅이라 AI 는 통과하지만 기밀(share_policy DENY)은 공유 저장소(온톨로지)에 쓸 수 없다. 목적 헤더가 없는 채팅 대행이라 가드 코어 훅은
   * SHARE 를 보지 않는다 — 이 거부는 createOntology 의 명시 판정(requireAiForDatasets share=true)만 만든다(변이 확인 대상).
   */
  @Test
  void shareDeniedSource_isPolicyBlocked_evenWhenSelfHosted() throws Exception {
    declareSelfHosted();
    long topUser = userAt("기밀");
    long secret = fx.createDatasetRow(m + "_secret", fx.levelId("기밀"), userId);
    datasets.add(secret);
    fx.grantUser(secret, topUser); // 기밀은 허용 목록 필수 — 볼 수 있게 한다
    MockHttpServletResponse r = create(topUser, List.of(secret));
    assertThat(r.getStatus()).isEqualTo(403);
    JsonNode body = om.readTree(r.getContentAsString());
    assertThat(body.path("code").asText()).isEqualTo("POLICY_BLOCKED");
    assertThat(body.path("errors").path("action").asText()).isEqualTo("SHARE");
    assertThat(body.path("errors").path("levelName").asText()).isEqualTo("기밀");
    assertThat(createdOntologies()).isZero();
  }

  /**
   * 응답이 입력 순서에 달라지지 않는다: [볼 수 있으나 AI 불허(외부 호스팅의 민감), 숨김(기밀)] 순서여도 숨김 쪽 404 가 우선이다 — VIEW 를 전부 먼저 보기
   * 때문. requireView(AI 대행 코어 훅)로 하나씩 판정하면 앞쪽 민감에서 403 POLICY_BLOCKED 가 먼저 나간다(변이 확인 대상).
   */
  @Test
  void visibleBlockedThenHidden_isNotFound_regardlessOfOrder() throws Exception {
    long sens = ds("민감");
    long secret = fx.createDatasetRow(m + "_hid2", fx.levelId("기밀"), userId);
    datasets.add(secret);
    MockHttpServletResponse r = create(userId, List.of(sens, secret));
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(r.getContentAsString()).doesNotContain("POLICY_BLOCKED").doesNotContain("기밀");
    assertThat(createdOntologies()).isZero();
  }

  @Test
  void hiddenSource_isSameAsMissing() throws Exception {
    // 민감 자격 사용자에게 기밀 데이터셋은 자격 밖이다 — 존재를 드러내지 않아야 한다.
    long secret = fx.createDatasetRow(m + "_hidden", fx.levelId("기밀"), userId);
    datasets.add(secret);
    long missingId = 999_999_999L;
    MockHttpServletResponse hidden = create(userId, List.of(secret));
    MockHttpServletResponse missing = create(userId, List.of(missingId));
    assertThat(hidden.getStatus()).isEqualTo(404);
    assertThat(missing.getStatus()).isEqualTo(404);
    JsonNode h = om.readTree(hidden.getContentAsString());
    JsonNode x = om.readTree(missing.getContentAsString());
    // 타임스탬프를 빼고 상태·코드·메시지가 같아야 한다(id 자리만 다름).
    assertThat(h.path("status")).isEqualTo(x.path("status"));
    assertThat(h.path("code")).isEqualTo(x.path("code"));
    assertThat(h.path("message").asText().replace(String.valueOf(secret), "X"))
        .isEqualTo(x.path("message").asText().replace(String.valueOf(missingId), "X"));
    assertThat(h.path("errors")).isEqualTo(x.path("errors"));
    assertThat(hidden.getContentAsString()).doesNotContain("기밀");
    assertThat(createdOntologies()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // 흐름 B(S4) 감사 연결 — 다건 강제(requireViewThenAiForDatasets)의 거부·허용도 감사에 남는다.
  // ---------------------------------------------------------------------------------------------

  /** 이 사용자의 접근 거부 감사 행. */
  private List<Record> denials(long uid) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                "SELECT resource_id, metadata->>'action' a, metadata->>'reason' r FROM audit_log"
                    + " WHERE user_id = ? AND action_type = 'DATASET_ACCESS_DENIED' ORDER BY id",
                uid));
  }

  /** 출처 중 AI 불허(외부 호스팅의 민감)는 실제 사유 AI_EXTERNAL_DENIED 로 AI 동작 거부 감사에 남는다. */
  @Test
  void aiBlockedSource_isAuditedAsAiDenial() throws Exception {
    long pub = ds("공개");
    long sens = ds("민감");
    assertThat(create(userId, List.of(pub, sens)).getStatus()).isEqualTo(403);
    assertThat(denials(userId))
        .extracting(
            r ->
                r.get("a", String.class)
                    + "/"
                    + r.get("r", String.class)
                    + "/"
                    + r.get("resource_id", String.class))
        .containsExactly("AI/AI_EXTERNAL_DENIED/" + sens);
  }

  /** 숨김 출처의 404 는 실제 사유와 함께 VIEW 거부로 남고, 없는 id 의 404 는 남지 않는다("없음"은 거부가 아니다). */
  @Test
  void hiddenSource_isAuditedAsViewDenial_missingIsNot() throws Exception {
    long secret = fx.createDatasetRow(m + "_hid3", fx.levelId("기밀"), userId);
    datasets.add(secret);
    assertThat(create(userId, List.of(secret)).getStatus()).isEqualTo(404);
    assertThat(create(userId, List.of(999_999_999L)).getStatus()).isEqualTo(404);
    List<Record> rows = denials(userId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("a", String.class)).isEqualTo("VIEW");
    assertThat(rows.get(0).get("resource_id", String.class)).isEqualTo(String.valueOf(secret));
    assertThat(rows.get(0).get("r", String.class)).isNotBlank().isNotEqualTo("LEVEL_UNKNOWN");
  }
}
