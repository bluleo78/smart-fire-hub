package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.auth.service.RefreshTokenHasher;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 콘솔 전역 계정 비활성화/재활성화(#784).
 *
 * <p>구성: 운영자(SUPER_ADMIN, 테넌트 없음), 대상(새 테넌트 T 의 USER 멤버), 다른 운영자.
 *
 * <p>TenantContext 를 비우는 이유: IntegrationTestBase 가 테넌트 1 을 세워 두는데, 플랫폼 필터는 TenantContext 를
 * 세우지 않고 finally 에서만 지운다. 비우지 않으면 첫 요청이 GUC=1 로 돌아 감사 로그 tenant_id 가 1 이 된다(운영은
 * NULL) — 운영과 다른 조건에서 초록이 되는 것을 막는다.
 */
@AutoConfigureMockMvc
class PlatformAccountControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private AuthService authService;
  @Autowired private PermissionRepository permissionRepository;
  @Autowired private TenantProvisioningService provisioningService;

  private String marker;
  private long tenantId;
  private long operator;
  private String operatorToken;
  private long target;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    marker = "wd2acct" + System.nanoTime();
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, marker);
    provisioningService.provisionDefaults(tenantId);
    operator = TenantRlsTestSupport.insertUserWithPassword(dsl, marker + "-ops", "{noop}x");
    users.add(operator);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, operator);
    operatorToken = jwtTokenProvider.generatePlatformAccessToken(operator, marker + "-ops");
    String username = marker + "-target@example.com";
    target =
        TestUsers.createMember(
                dsl, fixtureTransactionTemplate, passwordEncoder, username, username,
                "Password123", marker + "-target", tenantId)
            .id();
    users.add(target);
    TenantContext.clear(); // 운영 조건 재현(위 클래스 주석)
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    users.forEach(
        id -> steps.add(() -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, id, tenantId)));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  private boolean active(long userId) {
    return dsl.fetchValue("select is_active from \"user\" where id = ?", userId)
        .equals(Boolean.TRUE);
  }

  private String saveRefreshToken(long userId) {
    String raw = jwtTokenProvider.generateRefreshToken(userId, null);
    refreshTokenRepository.save(
        userId, RefreshTokenHasher.hash(raw), LocalDateTime.now().plusDays(7), UUID.randomUUID());
    return raw;
  }

  private int unrevokedTokens(long userId) {
    return dsl.fetchCount(
        dsl.selectOne().from("refresh_token").where("user_id = ? and revoked = false", userId));
  }

  private void deactivate(long userId) throws Exception {
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", userId)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isNoContent());
  }

  @Test
  void deactivate_setsInactive_revokesAllRefreshTokens_andAuditsWithNullTenant() throws Exception {
    saveRefreshToken(target);
    saveRefreshToken(target);
    assertThat(unrevokedTokens(target)).isEqualTo(2);

    deactivate(target);

    assertThat(active(target)).isFalse();
    assertThat(unrevokedTokens(target)).isZero();
    // 감사 로그: 운영자 평면이라 tenant_id NULL. NULL-GUC 컨텍스트에서 읽어야 보인다(RLS IS NOT DISTINCT FROM).
    Supplier<List<String>> read =
        () ->
            dsl.fetch(
                    "select action_type from audit_log"
                        + " where resource_id = ? and user_id = ? and tenant_id is null",
                    String.valueOf(target),
                    operator)
                .getValues(0, String.class);
    assertThat(TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, null, read))
        .containsExactly("ACCOUNT_DEACTIVATE");
  }

  /** Review Focus 3: 재활성화가 옛 refresh 세션을 되살리지 않는다(재사용 탐지로 거부 — 비활성 사유가 아니다). */
  @Test
  void reactivate_doesNotResurrectRevokedRefreshTokens() throws Exception {
    String oldRefresh = saveRefreshToken(target);
    deactivate(target);
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/activate", target)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isNoContent());

    assertThat(active(target)).isTrue();
    assertThatThrownBy(() -> authService.refresh(oldRefresh))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessageContaining("이미 사용된 토큰");
  }

  /** Review Focus 2: 이미 발급된 access token 이라도 테넌트 권한이 즉시 0 이 된다(권한 조회가 user 활성 조인). */
  @Test
  void deactivate_zeroesTenantPermissionsImmediately() throws Exception {
    // 전제 보장: USER 역할 권한 구성에 기대지 않도록 ADMIN 을 붙여 "이전" 권한이 반드시 비어 있지 않게 한다.
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, target, tenantId, "ADMIN");
    Supplier<Integer> perms =
        () -> permissionRepository.findPermissionCodesByUserId(target).size();
    assertThat(
            TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, tenantId, perms))
        .isPositive();

    deactivate(target);

    assertThat(
            TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, tenantId, perms))
        .isZero();
  }

  @Test
  void deactivate_self_rejected400() throws Exception {
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", operator)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("자기 자신의 계정은 비활성화할 수 없습니다"));
    assertThat(active(operator)).isTrue();
  }

  @Test
  void deactivate_otherOperator_rejected409() throws Exception {
    long otherOps = TenantRlsTestSupport.insertUserWithPassword(dsl, marker + "-ops2", "{noop}x");
    users.add(otherOps);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, otherOps);

    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", otherOps)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("운영자 계정은 비활성화할 수 없습니다"));
    assertThat(active(otherOps)).isTrue();
  }

  @Test
  void deactivate_unknownUser_404() throws Exception {
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", Long.MAX_VALUE)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void deactivate_alreadyInactive_isNoOp_noSecondAudit() throws Exception {
    deactivate(target);
    deactivate(target);
    Supplier<Integer> count =
        () ->
            dsl.fetchCount(
                dsl.selectOne()
                    .from("audit_log")
                    .where(
                        "resource_id = ? and action_type = 'ACCOUNT_DEACTIVATE'",
                        String.valueOf(target)));
    assertThat(TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, null, count))
        .isEqualTo(1);
  }

  /** Review Focus 4: 계정 검색은 비활성도 찾고(재활성화하려면 필요), Owner 검색은 계속 비활성을 뺀다. */
  @Test
  void accountSearch_includesInactive_ownerSearchStillExcludes() throws Exception {
    deactivate(target);

    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", marker + "-target")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(target))
        .andExpect(jsonPath("$[0].username").value(marker + "-target@example.com"))
        .andExpect(jsonPath("$[0].active").value(false))
        .andExpect(jsonPath("$[0].operator").value(false))
        .andExpect(jsonPath("$[0].password").doesNotExist());

    mockMvc
        .perform(
            get("/api/platform/users")
                .param("q", marker + "-target")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void accountSearch_flagsOperator_andRejectsShortQuery() throws Exception {
    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", marker + "-ops")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(operator))
        .andExpect(jsonPath("$[0].operator").value(true));
    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", "a")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  /**
   * 실제 HTTP 경로: 비활성화 전에 발급된 access token 으로 권한 필요 테넌트 API 를 부르면, 비활성화 직후 같은 토큰이 바로
   * 403 이 된다(JWT 필터 → 권한 조회의 user 활성 조인). 다이얼로그의 "즉시 거부" 문구가 기대는 핵심 경로다.
   */
  @Test
  void deactivate_makesAlreadyIssuedTokenForbiddenOnTenantApiImmediately() throws Exception {
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, target, tenantId, "ADMIN");
    String tenantToken =
        jwtTokenProvider.generateAccessToken(target, marker + "-target@example.com", tenantId);
    mockMvc
        .perform(get("/api/v1/datasets").header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isOk());

    deactivate(target);

    mockMvc
        .perform(get("/api/v1/datasets").header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
  }

  /** 평면 격리: 테넌트 토큰은 계정 API 에 닿지 않는다(PlatformPlaneFilter). */
  @Test
  void tenantToken_cannotReachAccountApi() throws Exception {
    String tenantToken =
        jwtTokenProvider.generateAccessToken(target, marker + "-target@example.com", tenantId);
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", target)
                .header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
    assertThat(active(target)).isTrue();
  }
}
