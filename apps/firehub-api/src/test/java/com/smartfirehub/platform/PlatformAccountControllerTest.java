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
 * <p>TenantContext 를 비우는 이유: IntegrationTestBase 가 테넌트 1 을 세워 두는데, 플랫폼 필터는 TenantContext 를 세우지 않고
 * finally 에서만 지운다. 비우지 않으면 첫 요청이 GUC=1 로 돌아 감사 로그 tenant_id 가 1 이 된다(운영은 NULL) — 운영과 다른 조건에서 초록이 되는
 * 것을 막는다.
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
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "Password123",
                marker + "-target",
                tenantId)
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
    Supplier<Integer> perms = () -> permissionRepository.findPermissionCodesByUserId(target).size();
    assertThat(
            TenantRlsTestSupport.runInTenantTransaction(
                fixtureTransactionTemplate, tenantId, perms))
        .isPositive();

    deactivate(target);

    assertThat(
            TenantRlsTestSupport.runInTenantTransaction(
                fixtureTransactionTemplate, tenantId, perms))
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
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(target))
        .andExpect(jsonPath("$.content[0].username").value(marker + "-target@example.com"))
        .andExpect(jsonPath("$.content[0].active").value(false))
        .andExpect(jsonPath("$.content[0].operator").value(false))
        .andExpect(jsonPath("$.content[0].membershipCount").value(1))
        .andExpect(jsonPath("$.content[0].password").doesNotExist());

    mockMvc
        .perform(
            get("/api/platform/users")
                .param("q", marker + "-target")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** WD-47: 검색 하한이 1자로 내려갔다(페이지 목록이라 절단 문제가 없다). 상한(100자)은 그대로 400. */
  @Test
  void accountSearch_flagsOperator_acceptsOneChar_rejectsTooLong() throws Exception {
    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", marker + "-ops")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(operator))
        .andExpect(jsonPath("$.content[0].operator").value(true));
    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", "a")
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/platform/accounts")
                .param("q", "a".repeat(101))
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------------------------
  // WD-47 전체 목록 + 페이지네이션
  // ---------------------------------------------------------------------------------------------

  private org.springframework.test.web.servlet.ResultActions listAccounts(String... params)
      throws Exception {
    var request = get("/api/platform/accounts").header("Authorization", "Bearer " + operatorToken);
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request);
  }

  /** created_at 을 먼 미래로 고정한 계정 — 공유 test DB 에서도 "최신순 첫 페이지" 를 결정적으로 차지한다. */
  private long userCreatedAt(String suffix, String createdAt) {
    long id = TenantRlsTestSupport.insertUserWithPassword(dsl, marker + suffix, "{noop}x");
    users.add(id);
    dsl.execute("update \"user\" set created_at = ?::timestamp where id = ?", createdAt, id);
    return id;
  }

  /**
   * q 없음 → 전체 목록, 생성 최신순(created_at desc, id desc), 페이지·전체 건수. createdAt 은 저장 TZ 오프셋이 붙는다(WD-11).
   *
   * <p>같은 시각 두 행(u2a·u2b)으로 id desc tie-breaker 도 본다.
   */
  @Test
  void list_withoutQuery_newestFirst_pagedWithTotal() throws Exception {
    long u1 = userCreatedAt("-l1", "2999-01-01 00:00:01");
    long u2a = userCreatedAt("-l2a", "2999-01-01 00:00:02");
    long u2b = userCreatedAt("-l2b", "2999-01-01 00:00:02");
    long u3 = userCreatedAt("-l3", "2999-01-01 00:00:03");
    long total = dsl.fetchCount(dsl.selectOne().from("\"user\""));

    listAccounts("size", "2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalElements").value(total))
        .andExpect(jsonPath("$.totalPages").value((int) ((total + 1) / 2)))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value(u3))
        .andExpect(jsonPath("$.content[1].id").value(Math.max(u2a, u2b)))
        .andExpect(
            jsonPath("$.content[0].createdAt")
                .value(
                    org.hamcrest.Matchers.matchesPattern(
                        "2999-01-01T00:00:03(Z|[+-]\\d{2}:\\d{2})")));
    listAccounts("page", "1", "size", "2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.content[0].id").value(Math.min(u2a, u2b)))
        .andExpect(jsonPath("$.content[1].id").value(u1));
    // 기본 size 는 20
    listAccounts().andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));
  }

  /** size 상한(100)·하한(1), page 음수는 400 — 감사 로그 목록과 같은 규칙. */
  @Test
  void list_rejectsOutOfRangePaging() throws Exception {
    listAccounts("size", "100").andExpect(status().isOk());
    listAccounts("size", "101").andExpect(status().isBadRequest());
    listAccounts("size", "0").andExpect(status().isBadRequest());
    listAccounts("page", "-1").andExpect(status().isBadRequest());
  }

  /**
   * membershipCount: 다중 소속(ACTIVE 1 + 정지 1 = 2), 0(미소속). 정지 멤버십도 소속으로 센다.
   *
   * <p>membership 은 전역 테이블(RLS 미적용)이라 GUC 없는 운영자 경로에서도 세어진다 — 그 전제를 먼저 단언한다. 누가 RLS 를 켜면 이 테스트가
   * "조용히 0" 대신 전제 위반으로 먼저 깨진다.
   */
  @Test
  void list_membershipCount_countsActiveAndSuspended_andZero() throws Exception {
    assertThat(
            dsl.fetchValue(
                "select relrowsecurity from pg_class where oid = 'public.membership'::regclass"))
        .isEqualTo(Boolean.FALSE);
    long second = TenantRlsTestSupport.createActiveTenant(dsl, marker + "b");
    try {
      dsl.execute(
          "insert into membership (user_id, tenant_id, role, status) values (?, ?, 'MEMBER',"
              + " 'SUSPENDED')",
          target,
          second);
      long lonely = TenantRlsTestSupport.insertUserWithPassword(dsl, marker + "-lonely", "{noop}x");
      users.add(lonely);

      listAccounts("q", marker + "-target")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(target))
          .andExpect(jsonPath("$.content[0].membershipCount").value(2));
      listAccounts("q", marker + "-lonely")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(lonely))
          .andExpect(jsonPath("$.content[0].membershipCount").value(0));
    } finally {
      dsl.execute("delete from membership where tenant_id = ?", second);
      TenantRlsTestSupport.deleteTenants(dsl, second);
    }
  }

  /**
   * 검색 필터 회귀: 부분일치 필터 + totalElements 가 필터 결과 수 + 정렬(이메일 정확일치 우선 → 이메일 → …).
   *
   * <p>정확일치 우선이 실제로 작동함을 보이려고 other 의 이메일을 "a" 로 시작하게 바꾼다 — 이메일 오름차순만으로는 other 가 먼저 와야 하는데, 정확일치
   * 순위가 exact 를 앞으로 올린다. 생성 최신순이면 other(나중 생성) 가 먼저라 그것과도 구별된다.
   */
  @Test
  void list_withQuery_filtersAndKeepsSearchOrder() throws Exception {
    long exact = userCreatedAt("-f", "2000-01-01 00:00:00"); // 이메일 marker-f@example.com
    long other = userCreatedAt("-g", "2001-01-01 00:00:00");
    String q = marker + "-f@example.com";
    dsl.execute("update \"user\" set email = ? where id = ?", "a" + q, other);

    listAccounts("q", q)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value(exact))
        .andExpect(jsonPath("$.content[1].id").value(other));
    // 아이디(username) 로도 찾는다 — other 의 username 은 marker-g.
    listAccounts("q", marker + "-g")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(other));
  }

  /**
   * 실제 HTTP 경로: 비활성화 전에 발급된 access token 으로 권한 필요 테넌트 API 를 부르면, 비활성화 직후 같은 토큰이 바로 403 이 된다(JWT 필터
   * → 권한 조회의 user 활성 조인). 다이얼로그의 "즉시 거부" 문구가 기대는 핵심 경로다.
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

  // ---------------------------------------------------------------------------------------------
  // WD-46 계정 생성
  // ---------------------------------------------------------------------------------------------

  /** 계정 생성 요청 본문. 값은 JSON 문자열에 그대로 넣는다(테스트 값에 따옴표·역슬래시가 없다). */
  private static String createBody(String email, String name, String password) {
    return "{\"email\":\""
        + email
        + "\",\"name\":\""
        + name
        + "\",\"temporaryPassword\":\""
        + password
        + "\"}";
  }

  private org.springframework.test.web.servlet.ResultActions createAccount(
      String body, String token) throws Exception {
    return mockMvc.perform(
        post("/api/platform/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(body));
  }

  private Long userIdByUsername(String username) {
    return dsl.fetchOptional("select id from \"user\" where username = ?", username)
        .map(r -> r.get(0, Long.class))
        .orElse(null);
  }

  private int membershipCount(long userId) {
    return dsl.fetchCount(dsl.selectOne().from("membership").where("user_id = ?", userId));
  }

  /**
   * 성공: 201 + 응답 필드, username=소문자 이메일, 변경 강제 표식, 멤버십 0, 운영자 평면 감사(tenant NULL)와 대상 username.
   *
   * <p>멤버십 0 단언이 공허하지 않음을 먼저 보인다 — 같은 조회가 테넌트 멤버(target)에게는 1 이상을 돌려준다.
   */
  @Test
  void create_returns201_mustChangePassword_noMembership_andAudits() throws Exception {
    String email = marker + "-New@Example.com";
    String normalized = email.toLowerCase(java.util.Locale.ROOT);

    createAccount(createBody(email, " 새사용자 ", "Temp1234ab"), operatorToken)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(normalized))
        .andExpect(jsonPath("$.email").value(normalized))
        .andExpect(jsonPath("$.name").value("새사용자"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.operator").value(false))
        .andExpect(jsonPath("$.membershipCount").value(0))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.password").doesNotExist());

    Long created = userIdByUsername(normalized);
    assertThat(created).isNotNull();
    users.add(created);
    assertThat(
            dsl.fetchValue("select must_change_password from \"user\" where id = ?", created)
                .equals(Boolean.TRUE))
        .isTrue();
    String hash = dsl.fetchValue("select password from \"user\" where id = ?", created).toString();
    assertThat(passwordEncoder.matches("Temp1234ab", hash)).isTrue();

    assertThat(membershipCount(target)).isPositive(); // 같은 조회가 멤버를 볼 수 있다(공허 단언 방지)
    assertThat(membershipCount(created)).isZero();

    Supplier<List<String>> read =
        () ->
            dsl.fetch(
                    "select action_type || '|' || (metadata->>'targetUsername') || '|'"
                        + " || (metadata->>'plane') from audit_log"
                        + " where resource = 'user' and resource_id = ? and user_id = ? and tenant_id is null",
                    String.valueOf(created),
                    operator)
                .getValues(0, String.class);
    assertThat(TenantRlsTestSupport.runInTenantTransaction(fixtureTransactionTemplate, null, read))
        .containsExactly("ACCOUNT_CREATE|" + normalized + "|platform");
  }

  /** 만든 계정은 실제 로그인 경로로 들어갈 수 있고, 응답이 비밀번호 변경을 강제한다(소속 0 → 테넌트 미선택 토큰). */
  @Test
  void create_thenLogin_returnsMustChangePassword() throws Exception {
    String email = marker + "-login@example.com";
    createAccount(createBody(email, "로그인", "Temp1234ab"), operatorToken)
        .andExpect(status().isCreated());
    users.add(userIdByUsername(email));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + email + "\",\"password\":\"Temp1234ab\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(true))
        .andExpect(jsonPath("$.memberships.length()").value(0));
  }

  /** 중복 이메일(=아이디): 409 ACCOUNT_ALREADY_EXISTS, 기존 계정의 비밀번호·이름은 그대로다. */
  @Test
  void create_duplicateEmail_409_existingUntouched() throws Exception {
    String username = marker + "-target@example.com";
    String hashBefore =
        dsl.fetchValue("select password from \"user\" where id = ?", target).toString();

    createAccount(createBody(username, "가로채기", "Other1234ab"), operatorToken)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

    assertThat(dsl.fetchValue("select password from \"user\" where id = ?", target).toString())
        .isEqualTo(hashBefore);
    assertThat(dsl.fetchValue("select name from \"user\" where id = ?", target))
        .isEqualTo(marker + "-target");
  }

  /** 대소문자만 다른 아이디도 같은 계정으로 본다 — 409. 새 행이 생기지 않는다. */
  @Test
  void create_caseDifferingDuplicate_409() throws Exception {
    String upper = (marker + "-TARGET@EXAMPLE.COM");
    createAccount(createBody(upper, "대문자", "Temp1234ab"), operatorToken)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    assertThat(
            dsl.fetchCount(
                dsl.selectOne()
                    .from("\"user\"")
                    .where("lower(username) = ?", upper.toLowerCase(java.util.Locale.ROOT))))
        .isEqualTo(1);
  }

  /** 아이디는 다르지만 다른 계정의 이메일과 (대소문자 무시) 같으면 409 — 같은 사람의 두 번째 계정을 막는다. */
  @Test
  void create_emailUsedByOtherUsername_409() throws Exception {
    String email = marker + "-mail@example.com";
    long other =
        TenantRlsTestSupport.insertUserWithPassword(dsl, marker + "-legacyname", "{noop}x");
    users.add(other);
    dsl.execute("update \"user\" set email = ? where id = ?", email.toUpperCase(), other);

    createAccount(createBody(email, "중복메일", "Temp1234ab"), operatorToken)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
    assertThat(userIdByUsername(email)).isNull();
  }

  /** Bean Validation: 이메일 형식·비밀번호 정책 위반은 400 + 필드별 오류, 계정은 생기지 않는다. */
  @Test
  void create_invalid_400_withFieldErrors() throws Exception {
    createAccount(createBody("not-an-email", "", "short"), operatorToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists())
        .andExpect(jsonPath("$.errors.name").exists())
        .andExpect(jsonPath("$.errors.temporaryPassword").exists());
    createAccount(createBody(marker + "-weak@example.com", "약함", "alllowercase1"), operatorToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.temporaryPassword").exists());
    assertThat(userIdByUsername(marker + "-weak@example.com")).isNull();
  }

  /** 평면 격리: 테넌트 토큰(ADMIN 이라도)으로는 계정을 만들 수 없다. */
  @Test
  void create_tenantToken_rejected() throws Exception {
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, target, tenantId, "ADMIN");
    String tenantToken =
        jwtTokenProvider.generateAccessToken(target, marker + "-target@example.com", tenantId);
    String email = marker + "-tenant@example.com";
    createAccount(createBody(email, "테넌트", "Temp1234ab"), tenantToken)
        .andExpect(status().isForbidden());
    assertThat(userIdByUsername(email)).isNull();
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
