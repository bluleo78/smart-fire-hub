package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-598 — 등급 추가·수정 본문에 allowlistRequired·adminBypass·auditAccess 가 빠지면 400 으로 거부한다.
 *
 * <p>예전에는 세 필드가 원시 boolean 이라 빠진 필드가 조용히 false 로 저장돼, 허용 목록 강제·관리자 우회·열람 감사가 경고 없이 꺼졌다. 기본 테넌트의 등급을
 * 건드리지 않도록 격리 테넌트에서 그 테넌트 ADMIN 으로 HTTP 경로(@Valid 배선 포함)를 검증한다.
 */
@AutoConfigureMockMvc
class SecurityLevelControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private TenantProvisioningService provisioning;

  private long tenantId;
  private long actor;

  @BeforeEach
  void setUp() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "slctl" + System.nanoTime());
    provisioning.provisionDefaults(tenantId);
    String u = "slctl" + System.nanoTime() + "@example.com";
    actor =
        TestUsers.createMember(
                dsl, fixtureTransactionTemplate, encoder, u, u, "Password123", "a", tenantId)
            .id();
    // security:settings 권한이 있어야 등급 추가·수정 엔드포인트에 닿는다.
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, actor, tenantId, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, actor, tenantId),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(actor, "slctl", tenantId);
  }

  private long levelId(String name) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(SECURITY_LEVEL.ID)
                .from(SECURITY_LEVEL)
                .where(SECURITY_LEVEL.NAME.eq(name))
                .fetchSingle(SECURITY_LEVEL.ID));
  }

  /** 등급의 세 플래그를 DB 에서 직접 켜거나 끈다 — 「빠진 필드가 기존 값을 바꾸지 않는다」의 출발점. */
  private void setFlags(long id, boolean allowlist, boolean bypass, boolean audit) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.update(SECURITY_LEVEL)
                .set(SECURITY_LEVEL.ALLOWLIST_REQUIRED, allowlist)
                .set(SECURITY_LEVEL.ADMIN_BYPASS, bypass)
                .set(SECURITY_LEVEL.AUDIT_ACCESS, audit)
                .where(SECURITY_LEVEL.ID.eq(id))
                .execute());
  }

  private Record3<Boolean, Boolean, Boolean> flags(long id) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(
                    SECURITY_LEVEL.ALLOWLIST_REQUIRED,
                    SECURITY_LEVEL.ADMIN_BYPASS,
                    SECURITY_LEVEL.AUDIT_ACCESS)
                .from(SECURITY_LEVEL)
                .where(SECURITY_LEVEL.ID.eq(id))
                .fetchSingle());
  }

  private int levelCount() {
    return inTenantFixture(tenantId, () -> dsl.fetchCount(SECURITY_LEVEL));
  }

  /** 정책 3종 + 이름만 담은 본문 앞부분 — 뒤에 플래그 필드를 원하는 만큼 붙인다. */
  private static String body(String name, String flagsJson) {
    String base =
        "\"name\":\""
            + name
            + "\",\"exportPolicy\":\"PERMISSION\",\"aiPolicy\":\"SELF_HOSTED_ONLY\","
            + "\"sharePolicy\":\"ALLOW\"";
    return "{" + base + (flagsJson.isEmpty() ? "" : "," + flagsJson) + "}";
  }

  @Test
  void update_missingAllThreeFlags_returns400AndKeepsStoredValues() throws Exception {
    long id = levelId("민감");
    setFlags(id, true, true, true);

    mockMvc
        .perform(
            put("/api/v1/security-levels/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("민감", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.allowlistRequired").exists())
        .andExpect(jsonPath("$.errors.adminBypass").exists())
        .andExpect(jsonPath("$.errors.auditAccess").exists());

    var after = flags(id);
    assertThat(after.value1()).isTrue();
    assertThat(after.value2()).isTrue();
    assertThat(after.value3()).isTrue();
  }

  @Test
  void update_missingOnlyAuditAccess_returns400() throws Exception {
    long id = levelId("민감");
    setFlags(id, true, true, true);

    mockMvc
        .perform(
            put("/api/v1/security-levels/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("민감", "\"allowlistRequired\":true,\"adminBypass\":true")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.auditAccess").exists())
        .andExpect(jsonPath("$.errors.allowlistRequired").doesNotExist());

    assertThat(flags(id).value3()).isTrue();
  }

  @Test
  void update_explicitNullFlag_returns400() throws Exception {
    long id = levelId("민감");
    setFlags(id, true, true, true);

    mockMvc
        .perform(
            put("/api/v1/security-levels/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        "민감",
                        "\"allowlistRequired\":null,\"adminBypass\":true,\"auditAccess\":true")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.allowlistRequired").exists());

    assertThat(flags(id).value1()).isTrue();
  }

  @Test
  void create_missingFlags_returns400AndCreatesNothing() throws Exception {
    int before = levelCount();

    mockMvc
        .perform(
            post("/api/v1/security-levels")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("신규" + System.nanoTime() % 100000, "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.allowlistRequired").exists())
        .andExpect(jsonPath("$.errors.adminBypass").exists())
        .andExpect(jsonPath("$.errors.auditAccess").exists());

    assertThat(levelCount()).isEqualTo(before);
  }

  @Test
  void update_fullBody_returns200AndPersistsTrueAndFalse() throws Exception {
    long id = levelId("민감");
    setFlags(id, false, true, false);

    mockMvc
        .perform(
            put("/api/v1/security-levels/" + id)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        "민감",
                        "\"allowlistRequired\":true,\"adminBypass\":false,\"auditAccess\":true")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowlistRequired").value(true))
        .andExpect(jsonPath("$.adminBypass").value(false))
        .andExpect(jsonPath("$.auditAccess").value(true));

    var after = flags(id);
    assertThat(after.value1()).isTrue();
    assertThat(after.value2()).isFalse();
    assertThat(after.value3()).isTrue();
  }

  @Test
  void create_fullBody_returns200AndPersistsFlags() throws Exception {
    String name = "신규" + System.nanoTime() % 100000;

    mockMvc
        .perform(
            post("/api/v1/security-levels")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        name,
                        "\"allowlistRequired\":false,\"adminBypass\":true,\"auditAccess\":true")))
        .andExpect(status().isOk());

    var created = flags(levelId(name));
    assertThat(created.value1()).isFalse();
    assertThat(created.value2()).isTrue();
    assertThat(created.value3()).isTrue();
  }
}
