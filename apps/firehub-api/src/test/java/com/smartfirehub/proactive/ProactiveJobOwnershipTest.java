package com.smartfirehub.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.service.ProactiveJobAsyncRunner;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.proactive.service.ProactiveOwnerEligibility;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WD-164·WD-165: 남의 스마트 작업이나 없는 작업에 대한 실행·수정·삭제는 조회와 같은 404 로 거절하고, 주인의 작업·실행 이력·예약 실행에 흔적을 남기지
 * 않는다.
 *
 * <p>WD-164: 남의 스마트 작업이나 없는 작업으로 「지금 실행」을 보내면 조회와 같은 404 로 거절하고, 주인의 실행 이력·실행 슬롯에 흔적을 남기지 않는다.
 *
 * <p>수정 전에는 소유자 확인이 비동기 실행 안(실행 행을 만든 뒤)에만 있어 202 가 나갔고, 주인 작업에 「Job을 찾을 수 없습니다」 FAILED 실행이 쌓였다.
 * 비동기 실행기는 목으로 바꿔 "실행으로 넘어갔는가"를 직접 본다 — 주인의 실행은 넘어간다(양성 대조), 남의 실행은 넘어가지 않는다.
 */
@AutoConfigureMockMvc
class ProactiveJobOwnershipTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private ProactiveJobService proactiveJobService;
  @Autowired private ProactiveOwnerEligibility ownerEligibility;
  @MockitoBean private ProactiveJobAsyncRunner asyncRunner;
  // 예약 실행 등록·해제를 직접 본다(WD-165 — 남의 삭제 요청이 주인 작업의 예약 실행을 해제하지 않는지)
  @MockitoBean private ProactiveJobSchedulerService schedulerService;

  private SecurityFixture fx;
  // WD-192 다른 워크스페이스 경계 검증용 스크래치 테넌트
  private final List<Long> scratchTenants = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  // WD-198 리포트 등급 검증용 데이터셋 메타 행
  private final List<Long> datasets = new ArrayList<>();
  @Autowired private com.smartfirehub.proactive.service.ProactiveReportLevelRecorder levelRecorder;

  @Autowired
  private com.smartfirehub.proactive.service.MetricSqlAccessChecker metricSqlAccessChecker;

  private long owner;
  private String ownerToken;
  private String otherToken;
  private long jobId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = user();
    ownerToken = token(owner);
    otherToken = token(user());
    MockHttpServletResponse res =
        mockMvc
            .perform(
                post("/api/v1/proactive/jobs")
                    .header("Authorization", ownerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"주인_작업\",\"prompt\":\"요약\",\"enabled\":false,\"config\":{}}"))
            .andReturn()
            .getResponse();
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    jobId = om.readTree(res.getContentAsString()).path("id").asLong();
  }

  /** 스마트 작업 읽기·쓰기 권한만 가진 사용자(기본 USER 역할은 뗀다). */
  private long user() {
    long uid = fx.createUser("pjx_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "pjx_r_" + System.nanoTime(), fx.levelId("공개"), "proactive:read", "proactive:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "pjx" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    proactiveJobService.releaseRunSlot(jobId);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("proactive_job")))
                .where(field(name("user_id"), Long.class).in(users))
                .execute());
    for (Long t : scratchTenants) {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          t,
          () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, t));
      TenantRlsTestSupport.deleteTenants(dsl, t);
    }
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private int execute(long id, String token) throws Exception {
    return mockMvc
        .perform(post("/api/v1/proactive/jobs/" + id + "/execute").header("Authorization", token))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int executionRows() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                table(name("proactive_job_execution")),
                field(name("job_id"), Long.class).eq(jobId)));
  }

  @Test
  void executeSomeoneElsesOrMissingJob_is404_andLeavesNoTrace() throws Exception {
    // 남의 작업 · 없는 작업 → 조회와 같은 404
    assertThat(execute(jobId, otherToken)).isEqualTo(404);
    assertThat(execute(99_999_999L, otherToken)).isEqualTo(404);
    verify(asyncRunner, never()).executeJob(anyLong(), anyLong());
    assertThat(executionRows()).isZero();

    // 양성 대조: 주인은 곧바로 실행된다 — 남의 시도가 실행 슬롯을 잡아 409 로 막지 않았다는 뜻이기도 하다
    assertThat(execute(jobId, ownerToken)).isEqualTo(202);
    verify(asyncRunner, times(1)).executeJob(eq(jobId), eq(owner));
  }

  private int send(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
    req.header("Authorization", token);
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(req).andReturn().getResponse().getStatus();
  }

  private String ownerJobName() throws Exception {
    return om.readTree(
            mockMvc
                .perform(get("/api/v1/proactive/jobs/" + jobId).header("Authorization", ownerToken))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("name")
        .asText();
  }

  @Test
  void updateOrDeleteSomeoneElsesOrMissingJob_is404_andLeavesOwnerJobIntact() throws Exception {
    String url = "/api/v1/proactive/jobs/" + jobId;
    String rename = "{\"name\":\"덮어쓰기\"}";
    // 남의 작업 · 없는 작업 → 수정·삭제 모두 404 (예전엔 204 무동작)
    assertThat(send(put(url), otherToken, rename)).isEqualTo(404);
    assertThat(send(delete(url), otherToken, null)).isEqualTo(404);
    assertThat(send(put("/api/v1/proactive/jobs/99999999"), otherToken, rename)).isEqualTo(404);
    assertThat(send(delete("/api/v1/proactive/jobs/99999999"), otherToken, null)).isEqualTo(404);
    // 주인 작업은 그대로이고, 남의 삭제 요청이 주인 작업의 예약 실행을 해제하지 않았다
    assertThat(ownerJobName()).isEqualTo("주인_작업");
    verify(schedulerService, never()).unregisterSchedule(jobId);

    // 양성 대조: 주인은 수정·삭제할 수 있다
    assertThat(send(put(url), ownerToken, rename)).isEqualTo(204);
    assertThat(ownerJobName()).isEqualTo("덮어쓰기");
    assertThat(send(delete(url), ownerToken, null)).isEqualTo(204);
    verify(schedulerService, times(1)).unregisterSchedule(jobId);
  }

  // ── WD-192: 쓰기 권한을 잃은 작성자의 정리 + 워크스페이스 관리자(proactive:admin) ─────────────────

  /** 사용자에게 지정 권한만 가진 역할을 새로 붙인다(기존 역할은 그대로). */
  private void grant(long uid, String... codes) {
    long rid = fx.createRole("pjx_g_" + System.nanoTime(), fx.levelId("공개"), codes);
    roles.add(rid);
    fx.assignRole(uid, rid);
  }

  /** 사용자의 역할을 모두 떼고 proactive:read 만 가진 역할을 붙인다 — 「스마트 작업 쓰기 권한 회수」. */
  private void revokeWrite(long uid) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("delete from user_role where user_id = ?", uid));
    grant(uid, "proactive:read");
  }

  /**
   * 스마트 작업 읽기·쓰기 + 관리(proactive:admin) 권한을 가진 관리자. 쓰기까지 주는 이유: 실제 ADMIN 처럼 쓰기가 있어도 남의 작업 열람·편집·실행은
   * 404 여야 한다.
   */
  private long adminUser() {
    long uid = fx.createUser("pjx_admin");
    users.add(uid);
    fx.removeUserRole(uid);
    grant(uid, "proactive:read", "proactive:write", "proactive:admin");
    return uid;
  }

  private JsonNode getJson(String url, String token) throws Exception {
    MockHttpServletResponse res =
        mockMvc.perform(get(url).header("Authorization", token)).andReturn().getResponse();
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    return om.readTree(res.getContentAsString());
  }

  /** 관리자 목록에서 해당 작업 행(없으면 null). */
  private JsonNode adminRow(String adminToken, long id) throws Exception {
    JsonNode list = getJson("/api/v1/proactive/admin/jobs", adminToken);
    return StreamSupport.stream(list.spliterator(), false)
        .filter(n -> n.path("id").asLong() == id)
        .findFirst()
        .orElse(null);
  }

  private long createEnabledJob(String token) throws Exception {
    MockHttpServletResponse res =
        mockMvc
            .perform(
                post("/api/v1/proactive/jobs")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"활성_작업\",\"prompt\":\"비밀 프롬프트\",\"enabled\":true,"
                            + "\"cronExpression\":\"0 0 9 * * *\",\"config\":{}}"))
            .andReturn()
            .getResponse();
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
    JsonNode body = om.readTree(res.getContentAsString());
    // 막 만든 작업 응답에도 자격 판정이 실린다(양성 대조)
    assertThat(body.path("ownerEligible").asBoolean()).isTrue();
    assertThat(body.path("ownerIneligibleReason").isNull()).isTrue();
    return body.path("id").asLong();
  }

  private int auditRows(String action, long resourceId, long actor) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchCount(
                table(name("audit_log")),
                field(name("action_type"), String.class)
                    .eq(action)
                    .and(field(name("resource"), String.class).eq("proactive_job"))
                    .and(field(name("resource_id"), String.class).eq(String.valueOf(resourceId)))
                    .and(field(name("user_id"), Long.class).eq(actor))));
  }

  private Boolean jobEnabled(long tenantId, long id) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenantId,
        () ->
            dsl.select(field(name("enabled"), Boolean.class))
                .from(table(name("proactive_job")))
                .where(field(name("id"), Long.class).eq(id))
                .fetchOne(field(name("enabled"), Boolean.class)));
  }

  @Test
  void ownerWhoLostWrite_canDisableAndDelete_butNotEditEnableOrRun() throws Exception {
    long activeJob = createEnabledJob(ownerToken);
    String url = "/api/v1/proactive/jobs/" + activeJob;

    revokeWrite(owner);

    // 목록·상세에 「실행 자격 없음」이 실린다 — 예전엔 「활성」만 보여 정상처럼 보였다
    JsonNode detail = getJson(url, ownerToken);
    assertThat(detail.path("enabled").asBoolean()).isTrue();
    assertThat(detail.path("ownerEligible").asBoolean()).isFalse();
    assertThat(detail.path("ownerIneligibleReason").asText())
        .isEqualTo(ProactiveOwnerEligibility.CODE_OWNER_NO_PERMISSION);
    JsonNode list = getJson("/api/v1/proactive/jobs", ownerToken);
    assertThat(list.size()).isGreaterThanOrEqualTo(2);
    list.forEach(
        job -> {
          assertThat(job.path("ownerEligible").asBoolean()).isFalse();
          assertThat(job.path("ownerIneligibleReason").asText()).isEqualTo("OWNER_NO_PERMISSION");
        });

    // 편집·켜기·지금 실행은 여전히 쓰기 권한이 필요하다
    assertThat(send(put(url), ownerToken, "{\"name\":\"고침\"}")).isEqualTo(403);
    assertThat(send(put(url), ownerToken, "{\"enabled\":true}")).isEqualTo(403);
    assertThat(execute(activeJob, ownerToken)).isEqualTo(403);
    verify(asyncRunner, never()).executeJob(anyLong(), anyLong());

    // 끄기는 읽기 권한만으로 된다 — 예약 실행도 함께 해제된다
    assertThat(send(post(url + "/disable"), ownerToken, null)).isEqualTo(204);
    assertThat(getJson(url, ownerToken).path("enabled").asBoolean()).isFalse();
    verify(schedulerService, times(1)).unregisterSchedule(activeJob);

    // 남의 작업 끄기는 404 — 끄기 전용 경로도 작성자 한정이다
    assertThat(send(post("/api/v1/proactive/jobs/" + jobId + "/disable"), otherToken, null))
        .isEqualTo(404);
    assertThat(send(post("/api/v1/proactive/jobs/99999999/disable"), ownerToken, null))
        .isEqualTo(404);

    // 삭제도 읽기 권한만으로 된다
    assertThat(send(delete(url), ownerToken, null)).isEqualTo(204);
    assertThat(
            mockMvc
                .perform(get(url).header("Authorization", ownerToken))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void adminWorkspaceList_showsMinimalFields_andEligibilityMatchesRunTimeRule() throws Exception {
    long admin = adminUser();
    String adminToken = token(admin);

    JsonNode row = adminRow(adminToken, jobId);
    assertThat(row).as("관리자 목록에 남의 작업이 보여야 한다").isNotNull();
    // 정확한 키 집합 — 「프롬프트가 없다」만 보면 다른 내용 필드가 새도 통과한다
    Set<String> keys = new HashSet<>();
    row.fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "id",
            "name",
            "ownerName",
            "enabled",
            "ownerEligible",
            "ownerIneligibleReason",
            "nextExecuteAt",
            "timezone",
            "lastExecutionStatus");
    assertThat(row.path("name").asText()).isEqualTo("주인_작업");
    assertThat(row.path("ownerName").asText()).isEqualTo("pjx_u");
    assertThat(row.path("ownerEligible").asBoolean()).isTrue();
    assertThat(row.path("ownerIneligibleReason").isNull()).isTrue();

    // 배치 판정(관리자 목록)과 실행 직전 단건 판정이 세 상태 모두에서 같다
    assertThat(batchCode(owner)).isEqualTo(singleCode(owner)).isEmpty();
    revokeWrite(owner);
    row = adminRow(adminToken, jobId);
    assertThat(row.path("ownerEligible").asBoolean()).isFalse();
    assertThat(row.path("ownerIneligibleReason").asText()).isEqualTo("OWNER_NO_PERMISSION");
    assertThat(batchCode(owner)).isEqualTo(singleCode(owner)).contains("OWNER_NO_PERMISSION");
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        owner,
        DEFAULT_TEST_TENANT_ID);
    row = adminRow(adminToken, jobId);
    assertThat(row.path("ownerIneligibleReason").asText()).isEqualTo("OWNER_INACTIVE");
    assertThat(batchCode(owner)).isEqualTo(singleCode(owner)).contains("OWNER_INACTIVE");

    // 관리 권한이 없는 사용자는 전체 목록·관리자 끄기·삭제 모두 403
    String adminJobUrl = "/api/v1/proactive/admin/jobs/" + jobId;
    assertThat(send(get("/api/v1/proactive/admin/jobs"), otherToken, null)).isEqualTo(403);
    assertThat(send(post(adminJobUrl + "/disable"), otherToken, null)).isEqualTo(403);
    assertThat(send(delete(adminJobUrl), otherToken, null)).isEqualTo(403);
  }

  /** 실행 직전 단건 판정 — 요청·실행 경로처럼 테넌트 컨텍스트 트랜잭션 안에서 부른다(MockMvc 요청이 끝나면 스레드 컨텍스트가 비워진다). */
  private Optional<String> singleCode(long uid) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> ownerEligibility.findIneligibilityCode(uid));
  }

  private Optional<String> batchCode(long uid) {
    Map<Long, Optional<String>> codes =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> ownerEligibility.findIneligibilityCodes(List.of(uid)));
    return codes.get(uid);
  }

  @Test
  void admin_canDisableAndDeleteOthersJob_withAudit_butCannotViewEditOrRunIt() throws Exception {
    long activeJob = createEnabledJob(ownerToken);
    long admin = adminUser();
    String adminToken = token(admin);
    String ownerUrl = "/api/v1/proactive/jobs/" + activeJob;

    // 관리자는 남의 작업 상세·편집·켜기·지금 실행·작성자용 끄기를 할 수 없다(쓰기 권한이 있어도 404)
    assertThat(send(get(ownerUrl), adminToken, null)).isEqualTo(404);
    assertThat(send(put(ownerUrl), adminToken, "{\"name\":\"관리자_덮어쓰기\"}")).isEqualTo(404);
    assertThat(send(put(ownerUrl), adminToken, "{\"enabled\":true}")).isEqualTo(404);
    assertThat(execute(activeJob, adminToken)).isEqualTo(404);
    assertThat(send(post(ownerUrl + "/disable"), adminToken, null)).isEqualTo(404);
    verify(asyncRunner, never()).executeJob(anyLong(), anyLong());
    assertThat(jobEnabled(DEFAULT_TEST_TENANT_ID, activeJob)).isTrue();

    // 관리자 끄기 — 감사 로그가 남고 예약 실행이 해제된다
    String adminUrl = "/api/v1/proactive/admin/jobs/" + activeJob;
    assertThat(send(post(adminUrl + "/disable"), adminToken, null)).isEqualTo(204);
    assertThat(jobEnabled(DEFAULT_TEST_TENANT_ID, activeJob)).isFalse();
    verify(schedulerService, times(1)).unregisterSchedule(activeJob);
    assertThat(auditRows("PROACTIVE_JOB_ADMIN_DISABLE", activeJob, admin)).isEqualTo(1);
    assertThat(adminRow(adminToken, activeJob).path("enabled").asBoolean()).isFalse();

    // 관리자 삭제 — 감사 로그가 남고 작업이 사라진다
    assertThat(send(delete(adminUrl), adminToken, null)).isEqualTo(204);
    assertThat(auditRows("PROACTIVE_JOB_ADMIN_DELETE", activeJob, admin)).isEqualTo(1);
    assertThat(send(get(ownerUrl), ownerToken, null)).isEqualTo(404);
    assertThat(adminRow(adminToken, activeJob)).isNull();

    // 없는 작업은 404
    assertThat(send(post("/api/v1/proactive/admin/jobs/99999999/disable"), adminToken, null))
        .isEqualTo(404);
    assertThat(send(delete("/api/v1/proactive/admin/jobs/99999999"), adminToken, null))
        .isEqualTo(404);
  }

  @Test
  void admin_cannotTouchOtherWorkspacesJob() throws Exception {
    long admin = adminUser();
    String adminToken = token(admin);
    long otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd192");
    scratchTenants.add(otherTenant);
    Long foreignJob =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            otherTenant,
            () -> TenantRlsTestSupport.insertProactiveJob(dsl, owner, "wd192-foreign"));
    assertThat(jobEnabled(otherTenant, foreignJob)).isTrue();

    String url = "/api/v1/proactive/admin/jobs/" + foreignJob;
    assertThat(send(post(url + "/disable"), adminToken, null)).isEqualTo(404);
    assertThat(send(delete(url), adminToken, null)).isEqualTo(404);
    assertThat(adminRow(adminToken, foreignJob)).isNull();
    // 다른 워크스페이스 작업은 그대로다 — 감사도 남지 않는다
    assertThat(jobEnabled(otherTenant, foreignJob)).isTrue();
    assertThat(auditRows("PROACTIVE_JOB_ADMIN_DISABLE", foreignJob, admin)).isZero();
    verify(schedulerService, never()).unregisterSchedule(foreignJob);
  }

  @Test
  void migration_grantsProactiveAdminToSystemAdminRole() {
    Integer count =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchCount(
                    dsl.selectOne()
                        .from(table(name("role_permission")))
                        .join(table(name("role")))
                        .on(
                            field(name("role", "id"), Long.class)
                                .eq(field(name("role_permission", "role_id"), Long.class)))
                        .join(table(name("permission")))
                        .on(
                            field(name("permission", "id"), Long.class)
                                .eq(field(name("role_permission", "permission_id"), Long.class)))
                        .where(field(name("role", "name"), String.class).eq("ADMIN"))
                        .and(field(name("role", "is_system"), Boolean.class).isTrue())
                        .and(
                            field(name("permission", "code"), String.class)
                                .eq("proactive:admin"))));
    assertThat(count).isEqualTo(1);
  }

  // ── WD-198: 리포트 등급 — 실행 중 읽은 데이터셋의 최고 등급 기록 + 열람·PDF 판정 ─────────────────────────────

  /** 주인 작업에 실행 행을 직접 만든다(상태·완료 시각·리포트 등급 지정). 리포트 본문은 HTML 이 있는 완료 결과. */
  private long insertExecution(String status, java.time.LocalDateTime completedAt, Long levelId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("proactive_job_execution")))
                .set(field(name("job_id"), Long.class), jobId)
                .set(field(name("status"), String.class), status)
                .set(field(name("completed_at"), java.time.LocalDateTime.class), completedAt)
                .set(field(name("security_level_id"), Long.class), levelId)
                .set(
                    field(name("result"), org.jooq.JSONB.class),
                    org.jooq.JSONB.valueOf(
                        "{\"title\":\"리포트\",\"summary\":\"요약 본문\",\"sections\":[],"
                            + "\"htmlContent\":\"<p>본문</p>\"}"))
                .returning(field(name("id"), Long.class))
                .fetchSingle(field(name("id"), Long.class)));
  }

  /** 실행의 리포트 등급 id(없으면 null). */
  private Long reportLevel(long executionId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.select(field(name("security_level_id"), Long.class))
                .from(table(name("proactive_job_execution")))
                .where(field(name("id"), Long.class).eq(executionId))
                .fetchSingle(field(name("security_level_id"), Long.class)));
  }

  /** 주인 역할을 모두 떼고 지정 등급의 역할(스마트 작업·데이터셋 읽기)만 붙인다 — 「작성자 등급 낮추기/올리기」. */
  private void setOwnerLevel(String levelName, String... extraPerms) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("delete from user_role where user_id = ?", owner));
    List<String> perms =
        new ArrayList<>(List.of("proactive:read", "proactive:write", "dataset:read"));
    perms.addAll(List.of(extraPerms));
    long rid =
        fx.createRole(
            "pjx_lv_" + System.nanoTime(), fx.levelId(levelName), perms.toArray(String[]::new));
    roles.add(rid);
    fx.assignRole(owner, rid);
  }

  private long dataset(String levelName) {
    long id = fx.createDatasetRow("wd198_" + System.nanoTime(), fx.levelId(levelName), owner);
    datasets.add(id);
    return id;
  }

  /** ai-agent 가 스마트 작업 실행 중에 부르는 모양의 내부 대행 요청 — 데이터셋 단건 조회(인터셉터의 VIEW 강제를 지난다). */
  private void agentReadsDataset(long onBehalfOf, long datasetId, Long executionId)
      throws Exception {
    MockHttpServletRequestBuilder req =
        get("/api/v1/datasets/" + datasetId)
            .header("Authorization", "Internal test-internal-token")
            .header("X-On-Behalf-Of", String.valueOf(onBehalfOf))
            .header("X-On-Behalf-Of-Tenant", String.valueOf(DEFAULT_TEST_TENANT_ID));
    if (executionId != null) {
      req = req.header("X-Proactive-Execution-Id", String.valueOf(executionId));
    }
    mockMvc.perform(req).andReturn();
  }

  @Test
  void agentReadsDuringRun_recordMaxLevelOnly() throws Exception {
    setOwnerLevel("내부");
    long internal = dataset("내부");
    long open = dataset("공개");
    long exec = insertExecution("RUNNING", null, null);

    // 실행 id 없는 같은 읽기(채팅 등)는 기록하지 않는다
    agentReadsDataset(owner, open, null);
    assertThat(reportLevel(exec)).isNull();

    agentReadsDataset(owner, open, exec);
    assertThat(reportLevel(exec)).isEqualTo(fx.levelId("공개"));
    agentReadsDataset(owner, internal, exec);
    assertThat(reportLevel(exec)).isEqualTo(fx.levelId("내부"));
    // 더 낮은 등급을 나중에 읽어도 최고 등급이 남는다
    agentReadsDataset(owner, open, exec);
    assertThat(reportLevel(exec)).isEqualTo(fx.levelId("내부"));

    // 실행 중 AI 가 이전 리포트 본문(get_execution)을 읽으면 그 리포트의 등급도 넣는다
    setOwnerLevel("민감");
    long previous = insertExecution("COMPLETED", java.time.LocalDateTime.now(), fx.levelId("민감"));
    MockHttpServletResponse prev =
        mockMvc
            .perform(
                get("/api/v1/proactive/jobs/" + jobId + "/executions/" + previous)
                    .header("Authorization", "Internal test-internal-token")
                    .header("X-On-Behalf-Of", String.valueOf(owner))
                    .header("X-On-Behalf-Of-Tenant", String.valueOf(DEFAULT_TEST_TENANT_ID))
                    .header("X-Proactive-Execution-Id", String.valueOf(exec)))
            .andReturn()
            .getResponse();
    assertThat(prev.getStatus()).isEqualTo(200);
    assertThat(reportLevel(exec)).isEqualTo(fx.levelId("민감"));
  }

  @Test
  void forgedExecutionHeader_isIgnored() throws Exception {
    setOwnerLevel("내부");
    long internal = dataset("내부");
    long running = insertExecution("RUNNING", null, null);
    long finished = insertExecution("COMPLETED", java.time.LocalDateTime.now().minusDays(1), null);
    // 남(작성자 아님)이 주인의 진행 중 실행 id 를 실어도 기록되지 않는다
    long other = user();
    // 남도 그 데이터셋을 볼 수 있는 등급이어야 한다 — 볼 수 없으면 기록 이전에 VIEW 거부로 끝나 작성자 조건을 증명하지 못한다(변이 확인)
    long otherRole = fx.createRole("pjx_o_" + System.nanoTime(), fx.levelId("내부"), "dataset:read");
    roles.add(otherRole);
    fx.assignRole(other, otherRole);
    agentReadsDataset(other, internal, running);
    assertThat(reportLevel(running)).isNull();
    // 이미 끝난 실행 id 도 무시된다(요청 시작 전에 끝남)
    agentReadsDataset(owner, internal, finished);
    assertThat(reportLevel(finished)).isNull();
    // 숫자가 아닌 헤더는 무시되고 요청은 그대로 처리된다
    mockMvc
        .perform(
            get("/api/v1/datasets/" + internal)
                .header("Authorization", "Internal test-internal-token")
                .header("X-On-Behalf-Of", String.valueOf(owner))
                .header("X-On-Behalf-Of-Tenant", String.valueOf(DEFAULT_TEST_TENANT_ID))
                .header("X-Proactive-Execution-Id", "abc"))
        .andReturn();
    assertThat(reportLevel(running)).isNull();
    // 양성 대조: 같은 실행 id 를 작성자가 실으면 기록된다
    agentReadsDataset(owner, internal, running);
    assertThat(reportLevel(running)).isEqualTo(fx.levelId("내부"));
  }

  @Test
  void runnerScope_recordsInternalReads_andMetricSqlLevel() {
    setOwnerLevel("내부");
    long internal = dataset("내부");
    String table =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.select(field(name("table_name"), String.class))
                    .from(table(name("dataset")))
                    .where(field(name("id"), Long.class).eq(internal))
                    .fetchSingle(field(name("table_name"), String.class)));
    long exec = insertExecution("RUNNING", null, null);
    Map<String, Object> config =
        Map.of(
            "anomaly",
            Map.of(
                "metrics",
                List.of(
                    Map.of(
                        "id",
                        "m1",
                        "source",
                        "dataset",
                        "query",
                        "SELECT count(*) FROM " + table))));
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 범위 밖이면 아무 일도 없다
    metricSqlAccessChecker.noteMetricLevel(config, "m1", owner);
    assertThat(reportLevel(exec)).isNull();
    // 실행기 범위 안의 이상 탐지 지표 판정은 그 SQL 이 읽는 데이터셋 등급을 남긴다
    levelRecorder.runInExecution(
        exec,
        owner,
        () -> {
          metricSqlAccessChecker.noteMetricLevel(config, "m1", owner);
          return null;
        });
    assertThat(reportLevel(exec)).isEqualTo(fx.levelId("내부"));
  }

  private MockHttpServletResponse ownerGet(String url) throws Exception {
    return mockMvc.perform(get(url).header("Authorization", ownerToken)).andReturn().getResponse();
  }

  @Test
  void reportAboveOwnersCurrentLevel_is403_nullLevelStillOpens() throws Exception {
    long sensitive = fx.levelId("민감");
    long leveled = insertExecution("COMPLETED", java.time.LocalDateTime.now(), sensitive);
    long legacy = insertExecution("COMPLETED", java.time.LocalDateTime.now(), null);
    String base = "/api/v1/proactive/jobs/" + jobId + "/executions/";

    // 작성자 등급이 리포트 등급 이상이면 열린다(양성 대조)
    setOwnerLevel("민감");
    assertThat(ownerGet(base + leveled).getStatus()).isEqualTo(200);
    assertThat(ownerGet(base + leveled + "/html").getStatus()).isEqualTo(200);

    // 작성자 등급을 낮추면 상세·HTML·PDF 모두 403 + 한국어 안내
    setOwnerLevel("공개");
    for (String suffix : List.of("", "/html", "/pdf")) {
      MockHttpServletResponse r = ownerGet(base + leveled + suffix);
      assertThat(r.getStatus()).as(suffix).isEqualTo(403);
      JsonNode body = om.readTree(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(body.path("code").asText()).isEqualTo("REPORT_SECURITY_LEVEL_BLOCKED");
      assertThat(body.path("message").asText()).isEqualTo("현재 보안 등급으로 열 수 없는 리포트입니다");
    }
    // 등급 없는 리포트(이전 리포트)는 지금처럼 열린다
    assertThat(ownerGet(base + legacy).getStatus()).isEqualTo(200);
    assertThat(ownerGet(base + legacy + "/html").getStatus()).isEqualTo(200);

    // 실행 목록·리포트 목록은 배지용 등급을 싣고, 열 수 없는 리포트의 요약은 비운다
    JsonNode list = getJson("/api/v1/proactive/jobs/" + jobId + "/executions", ownerToken);
    boolean sawBadge = false;
    for (JsonNode row : list) {
      if (row.path("id").asLong() == leveled) {
        assertThat(row.path("securityLevel").path("name").asText()).isEqualTo("민감");
        sawBadge = true;
      }
    }
    assertThat(sawBadge).isTrue();
    JsonNode reports = getJson("/api/v1/proactive/reports", ownerToken);
    for (JsonNode row : reports) {
      if (row.path("executionId").asLong() == leveled) {
        assertThat(row.path("summary").isNull()).isTrue();
        assertThat(row.path("securityLevel").path("name").asText()).isEqualTo("민감");
      } else if (row.path("executionId").asLong() == legacy) {
        assertThat(row.path("summary").asText()).isEqualTo("요약 본문");
      }
    }
  }

  @Test
  void reportPdf_appliesLevelExportPolicy() throws Exception {
    String base = "/api/v1/proactive/jobs/" + jobId + "/executions/";
    // 기밀(export DENY): 열람은 되지만 PDF 는 403 POLICY_BLOCKED
    long secret = insertExecution("COMPLETED", java.time.LocalDateTime.now(), fx.levelId("기밀"));
    setOwnerLevel("기밀");
    assertThat(ownerGet(base + secret + "/html").getStatus()).isEqualTo(200);
    MockHttpServletResponse denied = ownerGet(base + secret + "/pdf");
    assertThat(denied.getStatus()).isEqualTo(403);
    JsonNode body = om.readTree(denied.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(body.path("code").asText()).isEqualTo("POLICY_BLOCKED");
    assertThat(body.path("message").asText()).isEqualTo("'기밀' 등급 데이터는 내보낼 수 없습니다.");

    // 민감(export PERMISSION): 제한 데이터 내보내기 권한이 없으면 403, 있으면 판정 통과(403 아님)
    long sensitive = insertExecution("COMPLETED", java.time.LocalDateTime.now(), fx.levelId("민감"));
    setOwnerLevel("민감");
    MockHttpServletResponse needPerm = ownerGet(base + sensitive + "/pdf");
    assertThat(needPerm.getStatus()).isEqualTo(403);
    assertThat(
            om.readTree(needPerm.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("message")
                .asText())
        .isEqualTo("'민감' 등급 데이터를 내보내려면 제한 데이터 내보내기 권한이 필요합니다.");
    setOwnerLevel("민감", "data:export_restricted");
    assertThat(ownerGet(base + sensitive + "/pdf").getStatus()).isNotEqualTo(403);
  }
}
