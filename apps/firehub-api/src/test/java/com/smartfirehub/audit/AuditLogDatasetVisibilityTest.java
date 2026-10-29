package com.smartfirehub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 감사 로그 목록은 비관리자에게 볼 수 없는 데이터셋의 행을 보여 주지 않는다(WD-289, 사람 결정 A안).
 *
 * <p>결함 보고가 "audit:read 만 가진 사용자가 기밀 데이터셋의 이름·테이블명·접근자를 감사 로그로 본다" 였으므로 실제 HTTP(MockMvc + 실제 JWT)로,
 * 데이터셋 목록과 같은 열람 판정(등급 + 허용 목록)이 감사 로그 목록에도 걸리는지 고정한다.
 *
 * <p>구성(새 테넌트, 비트랜잭션 — 정리는 tearDown): admin(ADMIN), auditor(audit:read, 등급 '내부'),
 * aclAuditor(audit:read, 등급 '기밀' — 등급으로는 기밀 데이터셋을 덮지만 허용 목록 판정을 받는다). 데이터셋은 '내부'·'민감'·'기밀'(허용 목록에
 * 없음)·'기밀'(aclAuditor 역할이 허용 목록에 있음) 네 개, 그리고 지금은 없는(삭제된) 데이터셋 id 의 행과 데이터셋이 아닌 행을 둔다. 행은 설명({@code
 * wd289-…})으로 구분한다.
 */
@AutoConfigureMockMvc
class AuditLogDatasetVisibilityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private ObjectMapper objectMapper;

  private long tenantA;
  private long admin;
  private long auditor;
  private long aclAuditor;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd289-a");
    provisioningService.provisionDefaults(tenantA);

    admin = member("admin");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, tenantA, "ADMIN");
    auditor = member("auditor");
    replaceRoles(auditor, createRole("AUDITOR", "내부", "audit:read"));
    long aclAuditorRole = createRole("ACL_AUDITOR", "기밀", "audit:read");
    aclAuditor = member("acl-auditor");
    replaceRoles(aclAuditor, aclAuditorRole);

    long internalDs = createDatasetRow("wd289_internal_" + System.nanoTime(), "내부");
    long sensitiveDs = createDatasetRow("wd289_sensitive_" + System.nanoTime(), "민감");
    long secretDs = createDatasetRow("wd289_secret_" + System.nanoTime(), "기밀");
    long secretListedDs = createDatasetRow("wd289_secret_listed_" + System.nanoTime(), "기밀");
    grantAllowlistRole(secretListedDs, aclAuditorRole);

    auditRow("dataset", String.valueOf(internalDs), "wd289-internal");
    auditRow("dataset", String.valueOf(sensitiveDs), "wd289-sensitive");
    auditRow("dataset", String.valueOf(secretDs), "wd289-secret");
    auditRow("dataset", String.valueOf(secretListedDs), "wd289-secret-listed");
    // 지금은 없는 데이터셋 id — 삭제된 데이터셋의 지난 기록(등급을 알 수 없다).
    auditRow("dataset", String.valueOf(Long.MAX_VALUE), "wd289-deleted");
    // BIGINT 를 넘는 숫자 id — 캐스트 오류로 목록 전체가 500 이 되면 안 된다(특정할 수 없는 행이라 ADMIN 만).
    auditRow("dataset", "99999999999999999999", "wd289-overflow");
    auditRow("role", "1", "wd289-role");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    // 감사 행·허용 항목·데이터셋은 user·role·security_level 을 참조하므로 가장 먼저 지운다.
    steps.add(
        () -> TenantRlsTestSupport.deleteOwnAuditLogRows(dsl, fixtureTransactionTemplate, tenantA));
    steps.add(
        () ->
            inTenantFixture(
                tenantA,
                () -> {
                  dsl.execute("delete from dataset_access_grant where tenant_id = ?", tenantA);
                  dsl.execute("delete from dataset where tenant_id = ?", tenantA);
                }));
    users.forEach(
        id -> steps.add(() -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, id, tenantA)));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantA));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  /** 결함 재현: '내부' 감사자는 '민감'·'기밀' 데이터셋 행과 삭제된 데이터셋 행을 보지 못하고, 볼 수 있는 행·데이터셋이 아닌 행은 본다. */
  @Test
  void auditor_seesOnlyRowsOfDatasetsTheyCanView() throws Exception {
    assertThat(descriptions(auditor, "")).containsExactlyInAnyOrder("wd289-internal", "wd289-role");
  }

  /** 등급이 덮어도 허용 목록이 필요한 데이터셋은 목록에 오른 경우에만 보인다(데이터셋 목록과 같은 판정). */
  @Test
  void auditor_withHighClearance_seesAllowlistedSecretOnly() throws Exception {
    assertThat(descriptions(aclAuditor, ""))
        .containsExactlyInAnyOrder(
            "wd289-internal", "wd289-sensitive", "wd289-secret-listed", "wd289-role");
  }

  /** 검색·리소스 필터로 좁혀도 같은 판정이 걸린다 — 숨긴 행이 검색으로 새지 않는다. */
  @Test
  void auditor_searchAndResourceFilter_doNotRevealHiddenRows() throws Exception {
    assertThat(descriptions(auditor, "&search=wd289-secret")).isEmpty();
    assertThat(descriptions(auditor, "&resource=dataset")).containsExactly("wd289-internal");
  }

  /** ADMIN 은 삭제된 데이터셋의 행까지 모두 본다. */
  @Test
  void admin_seesAllRows() throws Exception {
    assertThat(descriptions(admin, ""))
        .containsExactlyInAnyOrder(
            "wd289-internal",
            "wd289-sensitive",
            "wd289-secret",
            "wd289-secret-listed",
            "wd289-deleted",
            "wd289-overflow",
            "wd289-role");
  }

  // ── 요청 헬퍼 ─────────────────────────────────────────────────────────────────

  /** 감사 로그 목록(설명이 wd289- 로 시작하는 행만)의 설명 목록. 픽스처가 만드는 다른 감사 행(역할 부여 등)은 거른다. */
  private List<String> descriptions(long caller, String query) throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/v1/admin/audit-logs?size=100" + query)
                    .header(
                        "Authorization",
                        "Bearer " + jwt.generateAccessToken(caller, "u" + caller, tenantA)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> result = new ArrayList<>();
    for (JsonNode row : objectMapper.readTree(body).path("content")) {
      String description = row.path("description").asText();
      if (description.startsWith("wd289-")) {
        result.add(description);
      }
    }
    return result;
  }

  // ── 픽스처 헬퍼(role·user_role·dataset·audit_log 은 RLS 대상이라 A 컨텍스트 안에서) ──────────

  private long member(String prefix) {
    String username =
        "wd289-" + prefix + "-" + Long.toString(System.nanoTime(), 36) + "@example.com";
    long id =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "Password123",
                prefix,
                tenantA)
            .id();
    users.add(id);
    return id;
  }

  /** A 에 지정 등급·권한의 사용자 정의 역할을 만든다. */
  private long createRole(String name, String levelName, String... codes) {
    return inTenantFixture(
        tenantA,
        () -> {
          long id =
              ((Number)
                      dsl.fetchValue(
                          "insert into role (name, is_system, max_security_level_id)"
                              + " values (?, false, (select id from security_level where name = ?))"
                              + " returning id",
                          name,
                          levelName))
                  .longValue();
          for (String code : codes) {
            dsl.execute(
                "insert into role_permission (role_id, permission_id)"
                    + " select ?, id from permission where code = ?",
                id,
                code);
          }
          return id;
        });
  }

  /** 사용자의 A 역할을 지정 역할로만 바꾼다 — USER 를 떼어 열람 범위가 정확히 이 역할로만 정해지게 한다. */
  private void replaceRoles(long userId, long roleId) {
    inTenantFixture(
        tenantA,
        () -> {
          dsl.execute("delete from user_role where user_id = ?", userId);
          dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId);
        });
  }

  /** 데이터셋 메타 행만 만든다 — 열람 판정은 메타만으로 이뤄진다. */
  private long createDatasetRow(String tableName, String levelName) {
    return inTenantFixture(
        tenantA,
        () ->
            ((Number)
                    dsl.fetchValue(
                        "insert into dataset (name, table_name, created_by, storage_type,"
                            + " origin_type, security_level_id) values (?, ?, ?, 'TABLE', 'SOURCE',"
                            + " (select id from security_level where name = ?)) returning id",
                        "sf_" + tableName,
                        tableName,
                        admin,
                        levelName))
                .longValue());
  }

  private void grantAllowlistRole(long datasetId, long roleId) {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "insert into dataset_access_grant (dataset_id, role_id) values (?, ?)",
                datasetId,
                roleId));
  }

  /** A 의 감사 행 하나(접근자는 admin). */
  private void auditRow(String resource, String resourceId, String description) {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "insert into audit_log (user_id, username, action_type, resource, resource_id,"
                    + " description) values (?, 'wd289-admin', 'DATASET_ACCESS', ?, ?, ?)",
                admin,
                resource,
                resourceId,
                description));
  }
}
