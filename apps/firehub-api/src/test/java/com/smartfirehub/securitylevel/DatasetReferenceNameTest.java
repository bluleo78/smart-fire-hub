package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Task 3 B1·B2·C1·C2·D1·F1: 다른 엔티티가 참조하는 데이터셋의 <b>이름</b>은 조회자가 볼 수 없으면 null(id 는 유지)이고, 참조를 새로 지정하는
 * 저장 경로는 "숨김"과 "없음"을 같은 응답으로 거부한다(이름·존재 확인 경로 차단). 이미 저장돼 있던 숨김 참조를 그대로 되돌려 보내는 재저장은 막지 않는다 (왕복 보존
 * — 웹 편집기는 받은 id 를 PUT 으로 그대로 돌려보낸다).
 *
 * <p>low = 공개 자격(숨김 데이터셋을 못 봄), high = 민감 자격(봄). 모든 "못 봄" 단언에는 high 의 양성 대조를 둔다.
 */
@AutoConfigureMockMvc
class DatasetReferenceNameTest extends IntegrationTestBase {

  private static final long MISSING_ID = 987_654_321L;

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> pipelines = new ArrayList<>();
  private final List<Long> ontologies = new ArrayList<>();
  private String m;
  private long creator;
  private long hiddenId;
  private long hidden2Id;
  private long visibleId;
  private long low;
  private long high;
  private String lowToken;
  private String highToken;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    m = "rn" + System.nanoTime();
    creator = fx.createUser("rn_creator");
    users.add(creator);
    hiddenId = dataset(m + "_hidden", "민감");
    hidden2Id = dataset(m + "_hidden2", "민감");
    visibleId = dataset(m + "_visible", "공개");
    low = userAt("공개");
    high = userAt("민감");
    lowToken = token(low);
    highToken = token(high);
  }

  private long dataset(String table, String level) {
    long id = fx.createDatasetRow(table, fx.levelId(level), creator);
    datasets.add(id);
    return id;
  }

  private long userAt(String level) {
    long uid = fx.createUser("rn_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "rn_r_" + System.nanoTime(),
            fx.levelId(level),
            "dataset:read",
            "pipeline:read",
            "pipeline:write",
            "pipeline:delete",
            "trigger:read",
            "trigger:write",
            "analytics:read",
            "analytics:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "rn" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          for (long p : pipelines) {
            dsl.execute("delete from pipeline_trigger where pipeline_id = ?", p);
          }
          for (long u : users) {
            dsl.execute("delete from saved_query where created_by = ?", u);
          }
          for (long d : datasets) {
            dsl.execute("delete from dataset_graph_ingest where dataset_id = ?", d);
            dsl.execute("delete from dataset_ontology where dataset_id = ?", d);
          }
          for (long o : ontologies) {
            dsl.execute("delete from ontology where id = ?", o);
          }
        });
    for (long p : pipelines) {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            dsl.execute(
                "delete from pipeline_step_input where step_id in (select id from pipeline_step"
                    + " where pipeline_id = ?)",
                p);
            dsl.execute("delete from pipeline_step where pipeline_id = ?", p);
            dsl.execute("delete from pipeline where id = ?", p);
          });
    }
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  // ---------------------------------------------------------------- HTTP helpers

  private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String token, Object body)
      throws Exception {
    req.header("Authorization", token);
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
    }
    MockHttpServletResponse r = mockMvc.perform(req).andReturn().getResponse();
    r.setCharacterEncoding("UTF-8");
    return r;
  }

  private JsonNode json(MockHttpServletResponse r) throws Exception {
    return om.readTree(r.getContentAsString());
  }

  /** 상태·본문 바이트 동일(존재 은닉) 비교 — timestamp 같은 가변 필드는 제외한다. */
  private void assertSameRejection(MockHttpServletResponse a, MockHttpServletResponse b)
      throws Exception {
    assertSameRejection(a, b, hidden2Id);
  }

  /** {@code b} 가 숨김 id {@code hiddenInB} 로 거부된 응답일 때, 없는 id 로 거부된 {@code a} 와 상태·본문이 같다. */
  private void assertSameRejection(
      MockHttpServletResponse a, MockHttpServletResponse b, long hiddenInB) throws Exception {
    assertThat(a.getStatus()).isEqualTo(b.getStatus());
    JsonNode ja = json(a);
    JsonNode jb = json(b);
    ((com.fasterxml.jackson.databind.node.ObjectNode) ja).remove("timestamp");
    ((com.fasterxml.jackson.databind.node.ObjectNode) jb).remove("timestamp");
    assertThat(ja.toString().replace(String.valueOf(MISSING_ID), "<id>"))
        .isEqualTo(jb.toString().replace(String.valueOf(hiddenInB), "<id>"));
  }

  // ---------------------------------------------------------------- pipeline (B1·B2)

  private static Map<String, Object> sqlStep(String name, Long outputDatasetId) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", name);
    s.put("scriptType", "SQL");
    // 스텝 참조 더미는 저장 시 판정에서 빠진다 — 이 TC 의 관심사는 출력 id 판정뿐이다.
    s.put("scriptContent", "SELECT * FROM {{#1}}");
    s.put("outputDatasetId", outputDatasetId);
    s.put("loadStrategy", "REPLACE");
    return s;
  }

  private Map<String, Object> pipelineBody(String desc, List<Map<String, Object>> steps) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("name", "RN " + m + " " + desc); // 생성 경로 이름 중복(409)과 섞이지 않게 저장마다 다른 이름
    b.put("description", desc);
    b.put("steps", steps);
    return b;
  }

  private long createPipelineAsHigh(Long outputDatasetId) throws Exception {
    MockHttpServletResponse r =
        send(
            post("/api/v1/pipelines"),
            highToken,
            pipelineBody("v1", List.of(sqlStep("s1", outputDatasetId))));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    long id = json(r).get("id").asLong();
    pipelines.add(id);
    return id;
  }

  private JsonNode step(long pipelineId, String token) throws Exception {
    return json(send(get("/api/v1/pipelines/" + pipelineId), token, null)).get("steps").get(0);
  }

  @Test
  void pipelineDetail_hidesOutputNameOnly_idKept() throws Exception {
    long p = createPipelineAsHigh(hiddenId);
    JsonNode lowStep = step(p, lowToken);
    assertThat(lowStep.get("outputDatasetId").asLong()).isEqualTo(hiddenId);
    assertThat(lowStep.get("outputDatasetName").isNull()).isTrue();
    // 양성 대조: 볼 수 있는 사용자에게는 이름이 보인다.
    assertThat(step(p, highToken).get("outputDatasetName").asText())
        .isEqualTo("sf_" + m + "_hidden");
  }

  @Test
  void pipelineSave_newHiddenOutput_rejectedSameAsMissing_butResentExistingPasses()
      throws Exception {
    long p = createPipelineAsHigh(hiddenId);

    // 왕복 보존: low 가 받은 그대로(숨김 출력 id) 다른 필드만 바꿔 저장 → 통과, 참조 유지.
    MockHttpServletResponse resave =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("v2 by low", List.of(sqlStep("s1-renamed", hiddenId))));
    assertThat(resave.getStatus()).as(resave.getContentAsString()).isEqualTo(204);
    assertThat(step(p, highToken).get("outputDatasetId").asLong()).isEqualTo(hiddenId);

    // 새로 지정하는 숨김 출력과 없는 출력은 같은 거부.
    MockHttpServletResponse hidden =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("v3", List.of(sqlStep("s1", hidden2Id))));
    MockHttpServletResponse missing =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("v3", List.of(sqlStep("s1", MISSING_ID))));
    assertThat(hidden.getStatus()).isEqualTo(403);
    assertThat(json(hidden).get("code").asText()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertSameRejection(missing, hidden);
    // 거부된 저장은 참조를 바꾸지 않는다.
    assertThat(step(p, highToken).get("outputDatasetId").asLong()).isEqualTo(hiddenId);

    // 생성 경로도 같다 — 그리고 양성 대조: 볼 수 있는 출력·자격 있는 편집자는 통과.
    MockHttpServletResponse createHidden =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("c", List.of(sqlStep("s1", hidden2Id))));
    assertThat(createHidden.getStatus()).isEqualTo(403);
    MockHttpServletResponse highNew =
        send(
            put("/api/v1/pipelines/" + p),
            highToken,
            pipelineBody("v4", List.of(sqlStep("s1", hidden2Id))));
    assertThat(highNew.getStatus()).as(highNew.getContentAsString()).isEqualTo(204);
  }

  private static Map<String, Object> sqlStepWithInputs(String name, List<Long> inputs) {
    Map<String, Object> s = sqlStep(name, null);
    s.put("inputDatasetIds", inputs);
    return s;
  }

  /**
   * 리뷰 M1: 다른 파이프라인의 러너 TEMP 라도 숨김이면 새 출력 지정은 없는 id 와 같은 거부다. 예전에는 TEMP 면 판정 없이 통과(204)해 TEMP 한정 존재
   * 오라클이 남았다.
   */
  @Test
  void pipelineSave_hiddenTempOfOtherPipelineAsNewOutput_sameAsMissing() throws Exception {
    long hiddenTemp = dataset(m + "_tmp", "민감");
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("update dataset set origin_type = 'TEMP' where id = ?", hiddenTemp));
    MockHttpServletResponse temp =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("t1", List.of(sqlStep("s1", hiddenTemp))));
    MockHttpServletResponse missing =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("t2", List.of(sqlStep("s1", MISSING_ID))));
    assertThat(temp.getStatus()).as(temp.getContentAsString()).isEqualTo(403);
    assertSameRejection(missing, temp, hiddenTemp);
  }

  /** 같은 규칙을 PYTHON 스텝(CR2 출력 판정 경로)에도 — 다른 파이프라인의 숨김 TEMP 를 새 출력으로 지정하면 없는 id 와 같은 거부. */
  @Test
  void pythonStep_hiddenTempOfOtherPipelineAsOutput_sameAsMissing() throws Exception {
    long hiddenTemp = dataset(m + "_tmp_py", "민감");
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("update dataset set origin_type = 'TEMP' where id = ?", hiddenTemp));
    MockHttpServletResponse temp =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("py1", List.of(pythonStep(hiddenTemp))));
    MockHttpServletResponse missing =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("py2", List.of(pythonStep(MISSING_ID))));
    assertThat(temp.getStatus()).as(temp.getContentAsString()).isEqualTo(403);
    assertSameRejection(missing, temp, hiddenTemp);
  }

  private static Map<String, Object> pythonStep(Long outputDatasetId) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("name", "py");
    s.put("scriptType", "PYTHON");
    s.put("scriptContent", "print(1)");
    s.put("outputDatasetId", outputDatasetId);
    s.put("loadStrategy", "REPLACE");
    return s;
  }

  /**
   * WD-21: SQL·PYTHON·API_CALL 입력 id 도 새로 추가한 것만 판정한다 — 숨김·없음 같은 403(예전: 없음은 FK 오류, 숨김은 저장 성공), 기존
   * 입력 재전송은 통과.
   */
  @Test
  void pipelineSave_newHiddenInput_rejectedSameAsMissing_butResentExistingPasses()
      throws Exception {
    MockHttpServletResponse created =
        send(
            post("/api/v1/pipelines"),
            highToken,
            pipelineBody("in1", List.of(sqlStepWithInputs("s1", List.of(hiddenId, visibleId)))));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    long p = json(created).get("id").asLong();
    pipelines.add(p);

    MockHttpServletResponse resend =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("in2", List.of(sqlStepWithInputs("s1", List.of(hiddenId, visibleId)))));
    assertThat(resend.getStatus()).as(resend.getContentAsString()).isEqualTo(204);
    assertThat(step(p, highToken).get("inputDatasetIds").toString())
        .contains(String.valueOf(hiddenId));

    MockHttpServletResponse hidden =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("in3", List.of(sqlStepWithInputs("s1", List.of(hiddenId, hidden2Id)))));
    MockHttpServletResponse missing =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody("in3", List.of(sqlStepWithInputs("s1", List.of(hiddenId, MISSING_ID)))));
    assertThat(hidden.getStatus()).isEqualTo(403);
    assertThat(json(hidden).get("code").asText()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertSameRejection(missing, hidden);
    // 양성 대조: 볼 수 있는 새 입력은 통과
    long visible2 = dataset(m + "_visible2", "공개");
    MockHttpServletResponse addVisible =
        send(
            put("/api/v1/pipelines/" + p),
            lowToken,
            pipelineBody(
                "in4", List.of(sqlStepWithInputs("s1", List.of(hiddenId, visibleId, visible2)))));
    assertThat(addVisible.getStatus()).as(addVisible.getContentAsString()).isEqualTo(204);
  }

  /**
   * WD-21 생성 경로(POST): 신규 파이프라인은 "기존 입력"이 없으므로 모든 입력 id 가 새로 지정한 것이다 — 숨김 입력과 없는 입력은 같은 403(존재
   * 은닉)이고, 거부된 생성은 파이프라인을 남기지 않는다. 양성 대조로 볼 수 있는 입력만이면 생성된다.
   */
  @Test
  void pipelineCreate_hiddenInput_rejectedSameAsMissing() throws Exception {
    MockHttpServletResponse hidden =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("cin", List.of(sqlStepWithInputs("s1", List.of(visibleId, hidden2Id)))));
    MockHttpServletResponse missing =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("cin", List.of(sqlStepWithInputs("s1", List.of(visibleId, MISSING_ID)))));
    assertThat(hidden.getStatus()).as(hidden.getContentAsString()).isEqualTo(403);
    assertThat(json(hidden).get("code").asText()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertSameRejection(missing, hidden);
    // 거부된 생성은 파이프라인 행을 남기지 않는다(같은 이름으로 조회해 0건).
    long leftover =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchOne("select count(*) from pipeline where name = ?", "RN " + m + " cin")
                    .get(0, Long.class));
    assertThat(leftover).isZero();

    MockHttpServletResponse ok =
        send(
            post("/api/v1/pipelines"),
            lowToken,
            pipelineBody("cin-ok", List.of(sqlStepWithInputs("s1", List.of(visibleId)))));
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(201);
    pipelines.add(json(ok).get("id").asLong());
  }

  // ---------------------------------------------------------------- saved query (C1·C2)

  private Map<String, Object> queryBody(String name, Long datasetId, Boolean shared) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("name", name);
    b.put("sqlText", "SELECT 1");
    b.put("datasetId", datasetId);
    if (shared != null) {
      b.put("isShared", shared);
    }
    return b;
  }

  /** 소유자가 자격을 잃은 상태를 흉내 낸다 — 숨김 데이터셋을 연결한 low 소유 쿼리를 직접 심는다. */
  private long seedQuery(long owner, long datasetId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchOne(
                    "insert into saved_query (name, sql_text, dataset_id, is_shared, created_by)"
                        + " values (?, 'SELECT 1', ?, true, ?) returning id",
                    m + "_q",
                    datasetId,
                    owner)
                .get(0, Long.class));
  }

  private JsonNode listItem(String token, long queryId) throws Exception {
    JsonNode content =
        json(send(get("/api/v1/analytics/queries?search=" + m + "&size=50"), token, null))
            .get("content");
    for (JsonNode n : content) {
      if (n.get("id").asLong() == queryId) {
        return n;
      }
    }
    throw new AssertionError("query " + queryId + " not in list: " + content);
  }

  @Test
  void savedQuery_listDetailClone_hideDatasetNameOnly() throws Exception {
    long q = seedQuery(high, hiddenId); // 공유 쿼리
    JsonNode lowItem = listItem(lowToken, q);
    assertThat(lowItem.get("datasetId").asLong()).isEqualTo(hiddenId);
    assertThat(lowItem.get("datasetName").isNull()).isTrue();
    assertThat(listItem(highToken, q).get("datasetName").asText()).isEqualTo("sf_" + m + "_hidden");

    JsonNode lowDetail = json(send(get("/api/v1/analytics/queries/" + q), lowToken, null));
    assertThat(lowDetail.get("datasetId").asLong()).isEqualTo(hiddenId);
    assertThat(lowDetail.get("datasetName").isNull()).isTrue();
    assertThat(
            json(send(get("/api/v1/analytics/queries/" + q), highToken, null))
                .get("datasetName")
                .asText())
        .isEqualTo("sf_" + m + "_hidden");

    JsonNode lowClone =
        json(send(post("/api/v1/analytics/queries/" + q + "/clone"), lowToken, null));
    assertThat(lowClone.get("datasetName").isNull()).isTrue();
  }

  @Test
  void savedQuery_createOrChangeToHidden_sameAsMissing_resendExistingPasses() throws Exception {
    MockHttpServletResponse hidden =
        send(post("/api/v1/analytics/queries"), lowToken, queryBody(m + "_c1", hidden2Id, false));
    MockHttpServletResponse missing =
        send(post("/api/v1/analytics/queries"), lowToken, queryBody(m + "_c2", MISSING_ID, false));
    assertThat(hidden.getStatus()).isEqualTo(404);
    assertSameRejection(missing, hidden);
    // 양성 대조: 볼 수 있는 데이터셋 연결은 생성된다.
    assertThat(
            send(
                    post("/api/v1/analytics/queries"),
                    lowToken,
                    queryBody(m + "_c3", visibleId, false))
                .getStatus())
        .isEqualTo(201);

    // 자격을 잃은 소유자의 재저장 — 같은 datasetId 를 되돌려 보내면 통과, 다른 숨김 id 로 바꾸면 404.
    long q = seedQuery(low, hiddenId);
    MockHttpServletResponse resend =
        send(put("/api/v1/analytics/queries/" + q), lowToken, queryBody(m + "_q2", hiddenId, null));
    assertThat(resend.getStatus()).as(resend.getContentAsString()).isEqualTo(200);
    assertThat(json(resend).get("datasetId").asLong()).isEqualTo(hiddenId);
    MockHttpServletResponse change =
        send(
            put("/api/v1/analytics/queries/" + q), lowToken, queryBody(m + "_q3", hidden2Id, null));
    assertThat(change.getStatus()).isEqualTo(404);
  }

  // ---------------------------------------------------------------- trigger (D1)

  private Map<String, Object> triggerConfig(List<Long> ids, int polling) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("datasetIds", ids);
    c.put("pollingIntervalSeconds", polling);
    c.put("debounceSeconds", 0);
    return c;
  }

  private MockHttpServletResponse createTrigger(long p, String token, List<Long> ids)
      throws Exception {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("name", "rn trigger " + System.nanoTime());
    b.put("triggerType", "DATASET_CHANGE");
    b.put("config", triggerConfig(ids, 60));
    return send(post("/api/v1/pipelines/" + p + "/triggers"), token, b);
  }

  @Test
  void datasetChangeTrigger_newHiddenWatch_rejectedSameAsMissing_resendExistingPasses()
      throws Exception {
    long p = createPipelineAsHigh(null);

    MockHttpServletResponse hidden = createTrigger(p, lowToken, List.of(hidden2Id));
    MockHttpServletResponse missing = createTrigger(p, lowToken, List.of(MISSING_ID));
    assertThat(hidden.getStatus()).isEqualTo(403);
    assertThat(json(hidden).get("code").asText()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertSameRejection(missing, hidden);
    assertThat(createTrigger(p, lowToken, List.of(visibleId)).getStatus()).isEqualTo(201);

    // high 가 숨김 데이터셋 감시 트리거를 만들고, low 가 같은 감시 목록으로 주기만 바꿔 저장 → 통과. 숨김 id 를 추가하면 거부.
    MockHttpServletResponse created = createTrigger(p, highToken, List.of(hiddenId));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    long t = json(created).get("id").asLong();
    Map<String, Object> resend = new LinkedHashMap<>();
    resend.put("name", "rn trigger resend");
    resend.put("config", triggerConfig(List.of(hiddenId), 120));
    MockHttpServletResponse ok =
        send(put("/api/v1/pipelines/" + p + "/triggers/" + t), lowToken, resend);
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(204);
    Map<String, Object> grow = new LinkedHashMap<>();
    grow.put("name", "rn trigger grow");
    grow.put("config", triggerConfig(List.of(hiddenId, hidden2Id), 120));
    assertThat(send(put("/api/v1/pipelines/" + p + "/triggers/" + t), lowToken, grow).getStatus())
        .isEqualTo(403);
  }

  // ---------------------------------------------------------------- graph ingest stale (F1)

  @Test
  void graphIngestStale_excludesHiddenDatasetIds() throws Exception {
    long ontologyId =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.insertInto(table(name("ontology")))
                    .set(field(name("domain"), String.class), "RN_STALE_" + m)
                    .set(field(name("schema_version"), Integer.class), 3)
                    .returning(field(name("id"), Long.class))
                    .fetchOne()
                    .get(field(name("id"), Long.class)));
    ontologies.add(ontologyId);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          for (long d : List.of(hiddenId, visibleId)) {
            dsl.execute(
                "insert into dataset_ontology (dataset_id, ontology_id) values (?, ?)",
                d,
                ontologyId);
            dsl.execute(
                "insert into dataset_graph_ingest (dataset_id, schema_version_at_ingest,"
                    + " chunk_count, node_count, edge_count, extraction_failures, status)"
                    + " values (?, 1, 1, 1, 1, 0, 'SUCCESS')",
                d);
          }
        });

    List<Long> lowIds = staleIds(lowToken);
    List<Long> highIds = staleIds(highToken);
    assertThat(lowIds).contains(visibleId).doesNotContain(hiddenId);
    assertThat(highIds).contains(hiddenId, visibleId);
  }

  private List<Long> staleIds(String token) throws Exception {
    List<Long> out = new ArrayList<>();
    json(send(get("/api/v1/graph-ingests/stale"), token, null))
        .forEach(n -> out.add(n.get("datasetId").asLong()));
    return out;
  }
}
