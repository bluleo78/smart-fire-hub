package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영자 콘솔의 테넌트 멤버 추가 API({@code POST /api/platform/tenants/{tenantId}/members}, WD-750).
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 엔드포인트가 자기 트랜잭션에서 커밋해야 하고(테넌트 범위를 트랜잭션 시작 전에 잡는 순서까지가
 * 검증 대상), 검증 조회는 그 밖에서 대상 테넌트 GUC 를 세워 RLS 를 통과해야 한다. 플랫폼 세션·정리 방식은 {@code
 * PlatformTenantControllerTest} 를 따른다.
 */
@AutoConfigureMockMvc
class PlatformTenantMemberAddTest extends IntegrationTestBase {

  /** 정책(대·소문자·숫자, 8자 이상)을 만족하는 임시 비밀번호. */
  private static final String TEMP_PASSWORD = "TempPass1x";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  private TransactionTemplate tx;
  private String token;
  private long operatorId;
  private String operatorName;
  private long ownerUserId;
  // 이 테스트가 만든 테넌트·계정 — 공유 test DB 라 자기 것만 지운다
  private final List<Long> createdTenants = new ArrayList<>();
  private final List<Long> createdUsers = new ArrayList<>();

  @BeforeEach
  void setUpPlatformSession() {
    tx = new TransactionTemplate(transactionManager);
    ownerUserId = createUser(false);
    operatorName = "wd750-ops-" + System.nanoTime();
    operatorId = TenantRlsTestSupport.insertUserWithPassword(dsl, operatorName, "{noop}x");
    createdUsers.add(operatorId);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, operatorId);
    token = jwtTokenProvider.generatePlatformAccessToken(operatorId, operatorName);
    // 운영 조건: 운영자 요청 스레드에는 테넌트 컨텍스트가 없다. 테스트 베이스가 세운 테넌트 1 이 남으면 감사 행이 tenant_id=1 로
    // 새어 들어가 「플랫폼 감사(테넌트 없음)」 단언이 의미를 잃는다(PlatformTenantControllerTest 와 같은 이유).
    TenantContext.clear();
  }

  /** 만든 테넌트(역할·멤버십·감사 포함)와 계정을 지운다. 테넌트를 먼저 지워야 멤버십·user_role FK 가 풀린다. */
  @AfterEach
  void cleanUp() {
    for (Long tenantId : createdTenants) {
      // 플랫폼 감사 행(tenant NULL)은 테넌트 cascade 에 걸리지 않는다 — 컨텍스트 없이 resource_id 로 지운다.
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          null,
          () -> {
            dsl.execute(
                "delete from audit_log where resource = 'tenant' and resource_id = ?",
                String.valueOf(tenantId));
          });
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, tenantId);
    }
    createdTenants.clear();
    for (Long userId : createdUsers) {
      TenantRlsTestSupport.deleteMembership(dsl, userId);
      TenantRlsTestSupport.deleteUser(dsl, userId);
    }
    createdUsers.clear();
  }

  /** 없는 계정이면 새로 만들고(변경 강제 표식 on) ACTIVE 멤버로 붙이며, 대상 테넌트에서 USER 역할만 갖는다. */
  @Test
  void addMember_newAccount_createsAccountWithForcedPasswordChange() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("new");

    MockHttpServletResponse response = addMember(tenantId, email, "새 멤버", false);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("created").asBoolean()).isTrue();
    long userId = body.get("userId").asLong();
    createdUsers.add(userId);
    Record user =
        dsl.fetchOne(
            "select username, name, must_change_password from \"user\" where id = ?", userId);
    assertThat(user).isNotNull();
    assertThat(user.get("username", String.class)).isEqualTo(email);
    assertThat(user.get("name", String.class)).isEqualTo("새 멤버");
    assertThat(user.get("must_change_password", Boolean.class)).isTrue();
    assertThat(membershipStatus(userId, tenantId)).isEqualTo("ACTIVE");
    assertThat(roleNames(userId, tenantId)).containsExactly("USER");
  }

  /** admin=true 면 USER 와 ADMIN 을 함께 받는다. */
  @Test
  void addMember_withAdmin_grantsUserAndAdmin() throws Exception {
    long tenantId = createTenant();

    MockHttpServletResponse response = addMember(tenantId, uniqueEmail("admin"), "관리자", true);

    assertThat(response.getStatus()).isEqualTo(201);
    long userId = trackUserId(response);
    assertThat(roleNames(userId, tenantId)).containsExactlyInAnyOrder("USER", "ADMIN");
  }

  /** 기존 계정(비멤버)이면 멤버십만 붙이고 비밀번호·이름은 건드리지 않는다. */
  @Test
  void addMember_existingNonMember_addsMembershipOnly() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("exist");
    long existingId = TenantRlsTestSupport.insertUserWithPassword(dsl, email, "{noop}original");
    createdUsers.add(existingId);
    String nameBefore = nameOf(existingId);

    MockHttpServletResponse response = addMember(tenantId, email, "덮어쓰면 안 되는 이름", false);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("created").asBoolean()).isFalse();
    assertThat(body.get("userId").asLong()).isEqualTo(existingId);
    Record user = dsl.fetchOne("select password, name from \"user\" where id = ?", existingId);
    assertThat(user.get("password", String.class)).isEqualTo("{noop}original");
    assertThat(user.get("name", String.class)).isEqualTo(nameBefore);
    assertThat(membershipStatus(existingId, tenantId)).isEqualTo("ACTIVE");
    assertThat(roleNames(existingId, tenantId)).containsExactly("USER");
  }

  /** 앞뒤 공백·대문자가 섞인 이메일도 기존 소문자 계정과 같은 사람으로 보고 새 계정을 만들지 않는다. */
  @Test
  void addMember_existingAccount_matchesEmailCaseInsensitively() throws Exception {
    long tenantId = createTenant();
    String local = "foo" + System.nanoTime();
    long existingId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, local + "@x.com", "{noop}original");
    createdUsers.add(existingId);
    long usersBefore = userCount();

    MockHttpServletResponse response =
        addMember(tenantId, " " + "Foo" + local.substring(3) + "@X.com ", "이름", false);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("created").asBoolean()).isFalse();
    assertThat(body.get("userId").asLong()).isEqualTo(existingId);
    assertThat(userCount()).isEqualTo(usersBefore);
  }

  /** 이미 ACTIVE 멤버면 409 MEMBER_ALREADY_EXISTS. */
  @Test
  void addMember_alreadyActiveMember_returns409() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("dup");
    long existingId = TenantRlsTestSupport.insertUserWithPassword(dsl, email, "{noop}x");
    createdUsers.add(existingId);
    TenantRlsTestSupport.insertActiveMembership(dsl, existingId, tenantId);

    MockHttpServletResponse response = addMember(tenantId, email, "이름", false);

    assertThat(response.getStatus()).isEqualTo(409);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("MEMBER_ALREADY_EXISTS");
  }

  /** 정지된 멤버면 409 MEMBER_SUSPENDED 와 userId — 화면이 「재활성화」 안내를 내도록 구분한다. */
  @Test
  void addMember_suspendedMember_returns409WithUserId() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("susp");
    long existingId = TenantRlsTestSupport.insertUserWithPassword(dsl, email, "{noop}x");
    createdUsers.add(existingId);
    dsl.execute(
        "insert into membership (user_id, tenant_id, role, status) values (?, ?, 'MEMBER',"
            + " 'SUSPENDED')",
        existingId,
        tenantId);

    MockHttpServletResponse response = addMember(tenantId, email, "이름", false);

    assertThat(response.getStatus()).isEqualTo(409);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("MEMBER_SUSPENDED");
    assertThat(body.get("errors").get("userId").asText()).isEqualTo(String.valueOf(existingId));
  }

  /** 정지된 테넌트에는 409 TENANT_SUSPENDED 이고 계정·멤버십·감사 어느 것도 써지지 않는다. */
  @Test
  void addMember_suspendedTenant_returns409() throws Exception {
    long tenantId = createTenant();
    mockMvc.perform(authed(post("/api/platform/tenants/" + tenantId + "/suspend"))).andReturn();
    String email = uniqueEmail("tsusp");
    long usersBefore = userCount();

    MockHttpServletResponse response = addMember(tenantId, email, "이름", false);

    assertThat(response.getStatus()).isEqualTo(409);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("TENANT_SUSPENDED");
    assertThat(userCount()).isEqualTo(usersBefore);
    assertThat(dsl.fetchOne("select id from \"user\" where username = ?", email)).isNull();
    assertThat(auditCount("MEMBER_ADD", tenantId)).isZero();
    assertThat(auditCount("ROLE_ASSIGN", tenantId)).isZero();
  }

  /** 없는 테넌트는 404. */
  @Test
  void addMember_unknownTenant_returns404() throws Exception {
    MockHttpServletResponse response = addMember(99_999_999L, uniqueEmail("nt"), "이름", false);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  /** 플랫폼 롤이 없는 계정의 플랫폼 토큰은 403 이고 아무것도 써지지 않는다. */
  @Test
  void addMember_withoutPermission_returns403() throws Exception {
    long tenantId = createTenant();
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(false), "nobody");
    String email = uniqueEmail("forbid");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/platform/tenants/" + tenantId + "/members")
                    .header("Authorization", "Bearer " + weak)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(email, "이름", false)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(dsl.fetchOne("select id from \"user\" where username = ?", email)).isNull();
  }

  /** 역할은 대상 테넌트에만 써진다 — 기본 테넌트(1)·다른 테넌트의 user_role 에는 행이 생기지 않는다(범위 누수 방지). */
  @Test
  void addMember_writesRolesIntoTargetTenantOnly() throws Exception {
    long tenantId = createTenant();
    long otherTenantId = createTenant();

    MockHttpServletResponse response = addMember(tenantId, uniqueEmail("scope"), "이름", true);

    assertThat(response.getStatus()).isEqualTo(201);
    long userId = trackUserId(response);
    assertThat(roleNames(userId, tenantId)).containsExactlyInAnyOrder("USER", "ADMIN");
    assertThat(userRoleCount(userId, otherTenantId)).isZero();
    assertThat(userRoleCount(userId, IntegrationTestBase.DEFAULT_TEST_TENANT_ID)).isZero();
    // 멤버십도 대상 테넌트 하나뿐이다
    assertThat(
            dsl.fetchOne("select count(*) from membership where user_id = ?", userId)
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  /** MEMBER_ADD·ROLE_ASSIGN 이 플랫폼 감사(tenant NULL, 행위자=운영자, plane=platform, tenantId 동봉)로 남는다. */
  @Test
  void addMember_recordsPlatformAudit() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("audit");

    MockHttpServletResponse response = addMember(tenantId, email, "이름", true);

    assertThat(response.getStatus()).isEqualTo(201);
    long userId = trackUserId(response);
    for (String action : List.of("MEMBER_ADD", "ROLE_ASSIGN")) {
      Record row =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              null,
              () ->
                  dsl.fetchOne(
                      "select username, metadata->>'plane' as plane,"
                          + " metadata->>'tenantId' as tenant_id_meta,"
                          + " metadata->>'userId' as user_id_meta,"
                          + " metadata->>'username' as username_meta,"
                          + " metadata->'roles' as roles,"
                          + " metadata->'created' as created_meta,"
                          + " metadata->'added' as added"
                          + " from audit_log where action_type = ? and resource = 'tenant'"
                          + " and resource_id = ? and tenant_id is null",
                      action,
                      String.valueOf(tenantId)));
      assertThat(row).as(action + " 플랫폼 감사 행").isNotNull();
      assertThat(row.get("username", String.class)).isEqualTo(operatorName);
      assertThat(row.get("plane", String.class)).isEqualTo("platform");
      assertThat(row.get("tenant_id_meta", String.class)).isEqualTo(String.valueOf(tenantId));
      assertThat(row.get("user_id_meta", String.class)).isEqualTo(String.valueOf(userId));
      assertThat(row.get("username_meta", String.class)).isEqualTo(email);
      if (action.equals("MEMBER_ADD")) {
        // MEMBER_ADD 만 roles·created 를 싣는다
        assertThat(row.get("roles", String.class)).contains("USER", "ADMIN");
        assertThat(row.get("created_meta", String.class)).isEqualTo("true");
        assertThat(row.get("added", String.class)).isNull();
      } else {
        // ROLE_ASSIGN 은 added(부여한 역할) 하나로만 — 같은 목록을 roles 로 한 번 더 싣지 않는다(F3)
        assertThat(row.get("added", String.class)).contains("USER", "ADMIN");
        assertThat(row.get("roles", String.class)).isNull();
        assertThat(row.get("created_meta", String.class)).isNull();
      }
    }
    // 대상 테넌트로 샌 감사 행이 없다 — 범위 안에서 기록하면 tenant_id 가 대상 테넌트가 된다
    Long leaked =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantId,
            () ->
                dsl.fetchOne(
                        "select count(*) from audit_log where tenant_id = ? and action_type in"
                            + " ('MEMBER_ADD','ROLE_ASSIGN')",
                        tenantId)
                    .get(0, Long.class));
    assertThat(leaked).isZero();
  }

  /** 이메일 형식이 아니면 400. */
  @Test
  void addMember_invalidEmail_returns400() throws Exception {
    long tenantId = createTenant();

    MockHttpServletResponse response = addMember(tenantId, "not-an-email", "이름", false);

    assertThat(response.getStatus()).isEqualTo(400);
  }

  /**
   * 기존 계정은 계정 id 로 붙인다 — username 과 email 이 다른 계정(예: username {@code legacy-admin})도 그 계정 자체가 멤버가
   * 되고 새 계정이 생기지 않는다(F1: 예전엔 email 로 username 을 찾다 못 찾아 email 로 새 계정을 만들었다).
   */
  @Test
  void addMember_existingAccountById_whoseUsernameDiffersFromEmail_attachesThatAccount()
      throws Exception {
    long tenantId = createTenant();
    // 공유 test DB 라 username·email(유니크) 에 나노초를 붙인다
    String suffix = String.valueOf(System.nanoTime());
    long existingId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "legacy-admin-" + suffix, "{noop}x");
    createdUsers.add(existingId);
    dsl.execute(
        "update \"user\" set email = ? where id = ?", "la-" + suffix + "@x.com", existingId);
    long usersBefore = userCount();

    MockHttpServletResponse response = addMemberById(tenantId, existingId);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("created").asBoolean()).isFalse();
    assertThat(body.get("userId").asLong()).isEqualTo(existingId);
    assertThat(membershipStatus(existingId, tenantId)).isEqualTo("ACTIVE");
    assertThat(roleNames(existingId, tenantId)).containsExactly("USER");
    assertThat(userCount()).isEqualTo(usersBefore);
  }

  /** 없는 계정 id 는 404 USER_NOT_FOUND 이고 아무것도 써지지 않는다. */
  @Test
  void addMember_byUnknownUserId_returns404() throws Exception {
    long tenantId = createTenant();
    long usersBefore = userCount();

    MockHttpServletResponse response = addMemberById(tenantId, 99_999_999L);

    assertThat(response.getStatus()).isEqualTo(404);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("USER_NOT_FOUND");
    assertThat(body.get("message").asText()).isEqualTo("계정을 찾을 수 없습니다");
    assertThat(userCount()).isEqualTo(usersBefore);
    assertThat(auditCount("MEMBER_ADD", tenantId)).isZero();
  }

  /** userId 가 없으면 새 계정 경로라 임시 비밀번호가 필수다 — 빠지면 400. */
  @Test
  void addMember_newAccountWithoutPassword_returns400() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("nopw");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                authed(post("/api/platform/tenants/" + tenantId + "/members"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            java.util.Map.of("email", email, "name", "이름", "admin", false))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("임시 비밀번호는 필수입니다");
    assertThat(dsl.fetchOne("select id from \"user\" where username = ?", email)).isNull();
  }

  /**
   * 비활성 계정은 userId 로 골라도 409 ACCOUNT_INACTIVE — 들어갈 수 없는 멤버를 만들지 않는다(F2, 테넌트 생성 WD-316 과 같은 판단).
   */
  @Test
  void addMember_inactiveAccount_returns409() throws Exception {
    long tenantId = createTenant();
    long inactiveId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd750-inact-" + System.nanoTime(), "{noop}x");
    createdUsers.add(inactiveId);
    dsl.execute("update \"user\" set is_active = false where id = ?", inactiveId);

    MockHttpServletResponse response = addMemberById(tenantId, inactiveId);

    assertInactiveRejected(response, inactiveId, tenantId);
  }

  /** 이메일 경로에서 찾은 기존 계정이 비활성이어도 409 ACCOUNT_INACTIVE. */
  @Test
  void addMember_inactiveAccountByEmail_returns409() throws Exception {
    long tenantId = createTenant();
    String email = uniqueEmail("inact");
    long inactiveId = TenantRlsTestSupport.insertUserWithPassword(dsl, email, "{noop}x");
    createdUsers.add(inactiveId);
    dsl.execute("update \"user\" set is_active = false where id = ?", inactiveId);

    MockHttpServletResponse response = addMember(tenantId, email, "이름", false);

    assertInactiveRejected(response, inactiveId, tenantId);
  }

  /** WD-779: 비활성 계정이 이미 멤버면 id 경로도 「이미 멤버」 가 우선(웹과 같은 판정 순서). */
  @Test
  void addMember_inactiveButAlreadyMemberById_memberConflictWins() throws Exception {
    long tenantId = createTenant();
    long id =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd779-inactmem-" + System.nanoTime(), "{noop}x");
    createdUsers.add(id);
    assertThat(addMemberById(tenantId, id).getStatus()).isEqualTo(201);
    dsl.execute("update \"user\" set is_active = false where id = ?", id);

    MockHttpServletResponse response = addMemberById(tenantId, id);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("MEMBER_ALREADY_EXISTS");
  }

  // ── 헬퍼 ────────────────────────────────────────────────────────────────────

  /** 플랫폼 API 로 테넌트를 만든다 — 실제 프로비저닝(USER·ADMIN 역할 시드)을 거쳐야 역할 조회 경로가 실전과 같다. */
  private long createTenant() throws Exception {
    String slug = "wd750-" + System.nanoTime();
    MockHttpServletResponse response =
        mockMvc
            .perform(
                authed(post("/api/platform/tenants"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"slug\":\"%s\",\"name\":\"%s\",\"ownerUserId\":%d}"
                            .formatted(slug, slug, ownerUserId)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("테넌트 생성").isEqualTo(201);
    long id = dsl.fetchOne("select id from tenant where slug = ?", slug).get(0, Long.class);
    createdTenants.add(id);
    return id;
  }

  private MockHttpServletResponse addMember(long tenantId, String email, String name, boolean admin)
      throws Exception {
    return mockMvc
        .perform(
            authed(post("/api/platform/tenants/" + tenantId + "/members"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, name, admin)))
        .andReturn()
        .getResponse();
  }

  /** 기존 계정 경로 — userId 와 admin 만 보낸다(콘솔 「기존 계정」 탭과 같은 본문). */
  private MockHttpServletResponse addMemberById(long tenantId, long userId) throws Exception {
    return mockMvc
        .perform(
            authed(post("/api/platform/tenants/" + tenantId + "/members"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        java.util.Map.of("userId", userId, "admin", false))))
        .andReturn()
        .getResponse();
  }

  /** 비활성 계정 거부: 409 ACCOUNT_INACTIVE, 멤버십·감사 없음. */
  private void assertInactiveRejected(MockHttpServletResponse response, long userId, long tenantId)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(409);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");
    assertThat(body.get("message").asText()).isEqualTo("비활성화된 계정은 멤버로 추가할 수 없습니다. 먼저 계정을 활성화하세요");
    assertThat(membershipStatus(userId, tenantId)).isNull();
    assertThat(auditCount("MEMBER_ADD", tenantId)).isZero();
  }

  private String body(String email, String name, boolean admin) throws Exception {
    return objectMapper.writeValueAsString(
        java.util.Map.of(
            "email", email, "name", name, "temporaryPassword", TEMP_PASSWORD, "admin", admin));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token);
  }

  /** 응답의 userId 를 꺼내 정리 목록에 올린다. */
  private long trackUserId(MockHttpServletResponse response) throws Exception {
    long userId = objectMapper.readTree(response.getContentAsString()).get("userId").asLong();
    createdUsers.add(userId);
    return userId;
  }

  /** 대상 테넌트 범위에서 본 사용자의 역할 이름(RLS 테이블이라 그 테넌트 컨텍스트 트랜잭션 안에서 읽는다). */
  private List<String> roleNames(long userId, long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.fetch(
                    "select r.name from user_role ur join role r on r.id = ur.role_id"
                        + " where ur.user_id = ? and ur.tenant_id = ?",
                    userId,
                    tenantId)
                .map(r -> r.get(0, String.class)));
  }

  private long userRoleCount(long userId, long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.fetchOne(
                    "select count(*) from user_role where user_id = ? and tenant_id = ?",
                    userId,
                    tenantId)
                .get(0, Long.class));
  }

  private String membershipStatus(long userId, long tenantId) {
    Record row =
        dsl.fetchOne(
            "select status from membership where user_id = ? and tenant_id = ?", userId, tenantId);
    return row == null ? null : row.get(0, String.class);
  }

  private String nameOf(long userId) {
    return dsl.fetchOne("select name from \"user\" where id = ?", userId).get(0, String.class);
  }

  private long userCount() {
    return dsl.fetchOne("select count(*) from \"user\"").get(0, Long.class);
  }

  /** tenant NULL 플랫폼 감사 행 수. */
  private long auditCount(String action, long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        null,
        () ->
            dsl.fetchOne(
                    "select count(*) from audit_log where action_type = ? and resource = 'tenant'"
                        + " and resource_id = ? and tenant_id is null",
                    action,
                    String.valueOf(tenantId))
                .get(0, Long.class));
  }

  private static String uniqueEmail(String tag) {
    return "wd750-" + tag + "-" + System.nanoTime() + "@x.com";
  }

  /** 검증용 사용자. 공유 test DB 라 나노초로 유일화한다. */
  private long createUser(boolean platformRole) {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "wd750-u-" + System.nanoTime(), "{noop}x");
    createdUsers.add(userId);
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }
}
