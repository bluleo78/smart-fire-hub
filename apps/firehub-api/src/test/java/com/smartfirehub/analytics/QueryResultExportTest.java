package com.smartfirehub.analytics;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository;
import com.smartfirehub.analytics.service.AnalyticsQueryRunCleanupService;
import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
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

/**
 * 스펙 §4.4 — 쿼리 결과 내보내기는 실행 기록 id(V136 analytics_query_run)로 서버가 재판정·재실행한다. 클라이언트 rows 직렬화는 없다.
 *
 * <p>테이블에는 실제 행(VALUE)을 넣는다 — 파일 본문에 그 값이 있어야 "서버가 다시 실행했다"는 단언이 공허하지 않다.
 */
@AutoConfigureMockMvc
class QueryResultExportTest extends IntegrationTestBase {

  private static final String VALUE = "qre-row";
  private static final String NOT_FOUND_MESSAGE = "실행 기록을 찾을 수 없습니다. 쿼리를 다시 실행한 뒤 내보내세요.";
  private static final String MULTI_MESSAGE = "쿼리가 참조하는 데이터 중 보안 등급 정책상 내보낼 수 없는 데이터가 있습니다.";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private AnalyticsQueryRunRepository runRepository;
  @Autowired private AnalyticsQueryRunCleanupService cleanupService;

  /** application-test.yml 의 내부 토큰 — ai-agent 의 MCP 대행 호출 재현용. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long creator;
  private long ds;
  private String table;

  /** 스키마까지 붙인 테이블 이름 — 요청 뒤에는 TenantContext 가 비므로 setUp 에서 한 번 계산한다. */
  private String qualified;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("qre_c");
    users.add(creator);
    table = "qre_" + System.nanoTime();
    String schema = DataSchema.current();
    dsl.execute("CREATE TABLE " + schema + "." + table + " (v text)");
    dsl.execute("INSERT INTO " + schema + "." + table + " VALUES ('" + VALUE + "')");
    ds = fx.createDatasetRow(table, fx.levelId("공개"), creator);
    qualified = DataSchema.qualify(table);
  }

  /** 기존 역할을 떼고 지정 등급 자격 + 지정 권한만 가진 사용자. */
  private long userAt(String level, String... perms) {
    long uid = fx.createUser("qre_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("qre_r_" + System.nanoTime(), fx.levelId(level), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "qre", DEFAULT_TEST_TENANT_ID);
  }

  /** 애드혹 실행 — 응답 JSON 을 맵으로. */
  private Map<?, ?> execute(long uid) throws Exception {
    String body =
        om.writeValueAsString(Map.of("sql", "SELECT v FROM " + qualified, "maxRows", 100));
    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/execute")
                    .header("Authorization", token(uid))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(json, Map.class);
  }

  private org.springframework.test.web.servlet.ResultActions exportRun(String runId, long uid)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/analytics/queries/runs/" + runId + "/export")
            .header("Authorization", token(uid))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"format\":\"CSV\"}"));
  }

  private org.springframework.test.web.servlet.ResultActions exportCheck(long uid, String sql)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/analytics/queries/export-check")
            .header("Authorization", token(uid))
            .contentType(MediaType.APPLICATION_JSON)
            .content(om.writeValueAsString(Map.of("sql", sql))));
  }

  private void setLevel(String level) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(ds))
                .execute());
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    for (long u : users) {
      inTenantFixture(
          () -> {
            dsl.execute("DELETE FROM analytics_query_run WHERE user_id = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          });
    }
    fx.deleteDatasetRow(ds);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.current() + "." + table);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  @Test
  void execute_returnsRunId_andExportReExecutesOnServer() throws Exception {
    long u = userAt("공개", "analytics:read", "data:export");
    Map<?, ?> r = execute(u);
    assertThat(r.get("exportAllowed")).isEqualTo(true);
    String runId = (String) r.get("runId");
    assertThat(runId).isNotBlank();
    // StreamingResponseBody 는 비동기 응답이라 asyncDispatch 로 본문을 끝까지 받는다.
    var started = exportRun(runId, u).andExpect(request().asyncStarted()).andReturn();
    String csv =
        mockMvc
            .perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(csv).contains(VALUE);
    // 성공 감사는 이 테넌트로 남는다 — 서비스에 트랜잭션이 없으므로 tenant_id 가 NULL 이면 테넌트 감사 화면에서 빠진다(회귀 고정).
    Long tenant =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT tenant_id FROM audit_log WHERE user_id = ? AND action_type ="
                            + " 'DATA_EXPORT' AND resource = 'query_result' AND resource_id = ?",
                        u,
                        runId)
                    .get(0, Long.class));
    assertThat(tenant).isEqualTo(DEFAULT_TEST_TENANT_ID);
  }

  /** Review Focus 3 — 남의 실행 기록 id 는 없는 id 와 같은 404. */
  @Test
  void othersRunId_isSameAsMissing() throws Exception {
    long owner = userAt("공개", "analytics:read", "data:export");
    long other = userAt("공개", "analytics:read", "data:export");
    String runId = (String) execute(owner).get("runId");
    for (String id : List.of(runId, "00000000-0000-0000-0000-000000000000")) {
      exportRun(id, other)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("QUERY_RUN_NOT_FOUND"))
          .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
    }
    // 소유자 본인은 같은 id 로 내보낼 수 있다 — 위 404 가 id 자체의 문제가 아님을 보인다.
    exportRun(runId, owner).andExpect(status().isOk());
  }

  /** 실행 뒤 등급이 오르면 내보내기 시점 판정으로 막힌다 — 저장된 플래그를 믿지 않는다. */
  @Test
  void export_isRejudgedAtExportTime() throws Exception {
    long u = userAt("기밀", "analytics:read", "data:export", "data:export_restricted");
    Map<?, ?> first = execute(u);
    assertThat(first.get("exportAllowed")).isEqualTo(true);
    String runId = (String) first.get("runId");
    setLevel("기밀");
    fx.grantUser(ds, u);
    exportRun(runId, u)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"))
        .andExpect(jsonPath("$.message").value(MULTI_MESSAGE));
    // 거부는 감사된다(어느 데이터셋이 막았는지는 감사에만 — 응답에는 없다).
    awaitSecurityAudit();
    Integer denials =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                            + " 'DATASET_ACCESS_DENIED' AND resource_id = ?",
                        u,
                        String.valueOf(ds))
                    .get(0, Integer.class));
    assertThat(denials).isEqualTo(1);
    assertThat(execute(u).get("exportAllowed")).isEqualTo(false);
  }

  /** 실행 기록의 created_at 을 61분 전으로 돌린다(보존 1시간 경계 밖). */
  private void ageRun(String runId) {
    inTenantFixture(
        () -> {
          dsl.execute(
              "UPDATE analytics_query_run SET created_at = now() - interval '61 minutes'"
                  + " WHERE id = ?::uuid",
              runId);
        });
  }

  private int runCount(String runId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT count(*) FROM analytics_query_run WHERE id = ?::uuid", runId)
                .get(0, Integer.class));
  }

  /** 보존 1시간 — 만료 기록은 없는 id 와 같은 404 이다. */
  @Test
  void expiredRun_isSameAsMissing() throws Exception {
    long u = userAt("공개", "analytics:read", "data:export");
    String runId = (String) execute(u).get("runId");
    ageRun(runId);
    exportRun(runId, u)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("QUERY_RUN_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
  }

  /**
   * code-review 7 — 주기 정리는 다시 실행하지 않는 사용자의 만료 행도 지운다(예전에는 같은 사용자의 다음 삽입 때만 지워 원문이 무기한 남았다). 보존 기간 안
   * 행은 남는다(대조군).
   *
   * <p>정리 호출 전 TenantContext 를 비운다 — 스레드에 테넌트가 남아 있으면 정리의 테넌트 순회를 지워도 통과하는 공허한 테스트가 된다.
   */
  @Test
  void cleanup_deletesExpiredRunsOfAllUsers_keepsFreshOnes() throws Exception {
    long idle = userAt("공개", "analytics:read", "data:export");
    long active = userAt("공개", "analytics:read", "data:export");
    String expired = (String) execute(idle).get("runId");
    ageRun(expired);
    String fresh = (String) execute(active).get("runId");
    // 다른 사용자의 실행(삽입)은 남의 만료 행을 지우지 않는다 — 정리 전까지 남아 있다.
    assertThat(runCount(expired)).isEqualTo(1);

    TenantContext.clear();
    cleanupService.cleanupExpired();

    assertThat(runCount(expired)).isZero();
    assertThat(runCount(fresh)).isEqualTo(1);
  }

  /** ai-agent 의 MCP 대행 호출(내부 토큰 + 대행 사용자·테넌트) — AI 경로로 표시된다. */
  private Map<?, ?> executeAsAi(long uid) throws Exception {
    String body =
        om.writeValueAsString(Map.of("sql", "SELECT v FROM " + qualified, "maxRows", 100));
    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/execute")
                    .header("Authorization", "Internal " + INTERNAL_TOKEN)
                    .header(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(uid))
                    .header(
                        InternalCallHeaders.ON_BEHALF_OF_TENANT,
                        String.valueOf(DEFAULT_TEST_TENANT_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(json, Map.class);
  }

  /** 이 사용자의 실행 기록 행 수. */
  private int runsOf(long uid) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT count(*) FROM analytics_query_run WHERE user_id = ?", uid)
                .get(0, Integer.class));
  }

  /**
   * code-review 7 — AI 대행 실행은 실행 기록을 남기지 않는다(AI 는 내보내기를 쓰지 않는다). 대조군: 같은 사용자의 웹 실행은 남긴다 — AI 쪽 0건이
   * 인증 실패나 실행 실패가 아님을 보이려고 AI 응답의 결과 행도 확인한다.
   */
  @Test
  void aiOnBehalfExecute_leavesNoRun_butWebExecuteDoes() throws Exception {
    long u = userAt("공개", "analytics:read", "data:export");
    Map<?, ?> ai = executeAsAi(u);
    assertThat(ai.get("error")).isNull();
    assertThat((List<?>) ai.get("rows")).hasSize(1);
    assertThat(ai.get("runId")).isNull();
    assertThat(runsOf(u)).isZero();

    assertThat((String) execute(u).get("runId")).isNotBlank();
    assertThat(runsOf(u)).isEqualTo(1);
  }

  /**
   * code-review 4 — 저장 쿼리 실행(/{id}/execute)도 실행 기록을 남겨, 그 runId 로 결과를 내보낼 수 있다(예전에는 runId 가 없어 저장
   * 쿼리 결과를 내보낼 수 없었다).
   */
  @Test
  void savedQueryExecute_returnsRunId_andExportSucceeds() throws Exception {
    long u = userAt("공개", "analytics:read", "analytics:write", "data:export");
    String created =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries")
                    .header("Authorization", token(u))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of("name", "qre-saved", "sqlText", "SELECT v FROM " + qualified))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long queryId = om.readTree(created).get("id").asLong();

    String json =
        mockMvc
            .perform(
                post("/api/v1/analytics/queries/" + queryId + "/execute")
                    .header("Authorization", token(u)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<?, ?> r = om.readValue(json, Map.class);
    assertThat(r.get("exportAllowed")).isEqualTo(true);
    String runId = (String) r.get("runId");
    assertThat(runId).isNotBlank();

    var started = exportRun(runId, u).andExpect(request().asyncStarted()).andReturn();
    String csv =
        mockMvc
            .perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(csv).contains(VALUE);
  }

  /** 실행 뒤 데이터셋이 조회자 자격 밖(숨김)으로 오르면 내보내기는 열람 거부 403 이고 감사된다 — 등급 이름은 싣지 않는다. */
  @Test
  void export_afterDatasetBecameHidden_isDeniedAndAudited() throws Exception {
    long u = userAt("민감", "analytics:read", "data:export", "data:export_restricted");
    String runId = (String) execute(u).get("runId");
    setLevel("기밀");
    exportRun(runId, u)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("POLICY_BLOCKED")))
        .andExpect(jsonPath("$.errors.levelName").doesNotExist());
    awaitSecurityAudit();
    Integer denials =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                            + " 'DATASET_ACCESS_DENIED' AND metadata ->> 'action' = 'SQL'",
                        u)
                    .get(0, Integer.class));
    assertThat(denials).isEqualTo(1);
  }

  /** export-check 도 data:export 권한까지 본다 — 정책은 허용('공개')이어도 권한이 없으면 false. */
  @Test
  void exportCheck_isFalseWithoutDataExportPermission() throws Exception {
    long u = userAt("공개", "analytics:read");
    exportCheck(u, "SELECT v FROM " + qualified)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
  }

  @Test
  void exportAllowed_isFalseWithoutDataExportPermission() throws Exception {
    long u = userAt("공개", "analytics:read");
    assertThat(execute(u).get("exportAllowed")).isEqualTo(false);
  }

  /** 설계 결정 7 — 허용·정책 위반·숨김·파싱 실패를 같은 200 으로 돌려주고, 허용만 true. */
  @Test
  void exportCheck_isIndistinguishableForHiddenAndBlocked() throws Exception {
    long u = userAt("민감", "analytics:read", "data:export");
    String ok = "SELECT v FROM " + qualified;
    exportCheck(u, ok)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(true));
    // 정책 위반: 볼 수는 있지만 '민감'(PERMISSION) 내보내기 권한(data:export_restricted)이 없다.
    setLevel("민감");
    exportCheck(u, ok)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
    // 숨김: 자격(민감)보다 높은 '기밀'.
    setLevel("기밀");
    exportCheck(u, ok)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
    // 파싱 실패.
    exportCheck(u, "SELEC nonsense")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportAllowed").value(false));
  }

  @Test
  void legacyClientRowsEndpoint_isGone() throws Exception {
    long u = userAt("공개", "analytics:read", "data:export");
    int s =
        mockMvc
            .perform(
                post("/api/v1/query-results/export")
                    .header("Authorization", token(u))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"columnNames\":[\"v\"],\"rows\":[{\"v\":\"x\"}],\"format\":\"CSV\"}"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(s).isEqualTo(404);
  }

  /** 실행 없이 실행 기록만 심는다 — 앞선 실행의 감사 등급 접근 기록과 섞이지 않게 내보내기 경로 하나만 본다. */
  private String seedRun(long uid) {
    return inTenantFixture(() -> runRepository.insert(uid, "SELECT v FROM " + qualified, 100))
        .toString();
  }

  /** 이 사용자의 감사 등급 접근(DATASET_ACCESS) 행 수(비동기 기록이 끝난 뒤). */
  private int accessRows(long uid) {
    awaitSecurityAudit();
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                        + " 'DATASET_ACCESS' AND resource_id = ?",
                    uid,
                    String.valueOf(ds))
                .get(0, Integer.class));
  }

  /**
   * code-review 3 — 내보내기 재판정이 열람은 허용하지만 정책상 내보내기를 막으면(403 POLICY_BLOCKED) 데이터가 나가지 않았으므로 감사 등급
   * 접근(DATASET_ACCESS SUCCESS)이 남지 않는다. 예전에는 허용 판정만으로 남았다.
   */
  @Test
  void exportPolicyDenied_leavesNoAccessSuccess() throws Exception {
    setLevel("기밀");
    long u = userAt("기밀", "analytics:read", "data:export", "data:export_restricted");
    fx.grantUser(ds, u);
    exportRun(seedRun(u), u)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("POLICY_BLOCKED"));
    assertThat(accessRows(u)).isZero();
  }

  /** 대조군 — 내보내기가 실제로 재실행돼 파일이 나가면 감사 등급 접근이 1건 남는다(위 0건이 기록 경로 고장이 아님을 보인다). */
  @Test
  void exportDelivered_leavesOneAccessSuccess() throws Exception {
    setLevel("민감");
    long u = userAt("민감", "analytics:read", "data:export", "data:export_restricted");
    var started = exportRun(seedRun(u), u).andExpect(request().asyncStarted()).andReturn();
    mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    assertThat(accessRows(u)).isEqualTo(1);
  }
}
