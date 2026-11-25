package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.platform.service.PlatformAuditRecorder;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영자 콘솔의 테넌트 소유자 지정 API({@code POST /api/platform/tenants/{tenantId}/owner}, WD-1132).
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 엔드포인트가 자기 트랜잭션에서 커밋해야 하고 범위(테넌트 GUC)를 트랜잭션 시작 전에 잡는
 * 순서까지가 검증 대상이다. 세션·정리 방식은 {@code PlatformTenantMemberAddTest} 를 따른다.
 */
@AutoConfigureMockMvc
class PlatformTenantOwnerAssignTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;
  // 감사 기록 실패를 흉내 내는 데 쓴다(소유자 변경과 감사가 한 트랜잭션인지 — WD-1132 리뷰)
  @MockitoSpyBean private PlatformAuditRecorder auditRecorder;

  private TransactionTemplate tx;
  private String token;
  private long operatorId;
  private long ownerUserId;
  // 테넌트 생성 시 지정한 이름(= slug) — 확인 이름으로 쓴다
  private String tenantName;
  // 이 테스트가 만든 테넌트·계정 — 공유 test DB 라 자기 것만 지운다
  private final List<Long> createdTenants = new ArrayList<>();
  private final List<Long> createdUsers = new ArrayList<>();

  @BeforeEach
  void setUpPlatformSession() {
    tx = new TransactionTemplate(transactionManager);
    ownerUserId = createUser();
    String operatorName = "wd1132-ops-" + System.nanoTime();
    operatorId = TenantRlsTestSupport.insertUserWithPassword(dsl, operatorName, "{noop}x");
    createdUsers.add(operatorId);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, operatorId);
    token = jwtTokenProvider.generatePlatformAccessToken(operatorId, operatorName);
    // 운영 조건: 운영자 요청 스레드에는 테넌트 컨텍스트가 없다 — 감사 행이 tenant_id=1 로 새지 않게 비운다.
    TenantContext.clear();
  }

  @AfterEach
  void cleanUp() {
    for (Long tenantId : createdTenants) {
      // 플랫폼 감사 행(tenant NULL)은 테넌트 cascade 에 걸리지 않는다 — resource_id 로 직접 지운다.
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

  /** 기존 소유자는 ADMIN 으로 내려가고 대상이 유일한 OWNER 가 되며, 플랫폼 감사(테넌트 없음)에 OWNER_TRANSFER 가 남는다. */
  @Test
  void assignOwner_swapsOwner_andRecordsPlatformAudit() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("ADMIN");
    assertThat(membershipRole(target, tenantId)).isEqualTo("OWNER");
    assertThat(roleNames(target, tenantId)).contains("ADMIN");
    assertThat(roleNames(ownerUserId, tenantId)).contains("ADMIN");
    Record audit = platformAudit(tenantId);
    assertThat(audit).isNotNull();
    assertThat(audit.get("operator_id", Long.class)).isEqualTo(operatorId);
    JsonNode meta = objectMapper.readTree(audit.get("metadata", String.class));
    assertThat(meta.get("plane").asText()).isEqualTo("platform");
    assertThat(meta.get("fromUserIds").get(0).asLong()).isEqualTo(ownerUserId);
    assertThat(meta.get("fromUserIds")).hasSize(1);
    assertThat(meta.get("toUserId").asLong()).isEqualTo(target);
    assertThat(meta.get("grantedAdmin").asBoolean()).isTrue();
  }

  /** ADMIN 을 새로 받은 사용자마다 플랫폼 ROLE_ASSIGN 감사가 남는다(대상 + ADMIN 역할이 없던 옛 소유자). */
  @Test
  void assignOwner_recordsRoleAssignForGrantedAdmins() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    // 옛 소유자의 ADMIN 역할을 떼어, 이전이 둘 모두에게 ADMIN 을 새로 주게 한다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and tenant_id = ?",
                ownerUserId,
                tenantId));

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isEqualTo(204);
    List<Record> rows =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            () ->
                dsl.fetch(
                    "select user_id as operator_id, metadata::text as metadata from audit_log"
                        + " where action_type = 'ROLE_ASSIGN' and resource = 'tenant'"
                        + " and resource_id = ? and tenant_id is null",
                    String.valueOf(tenantId)));
    assertThat(rows).hasSize(2);
    List<Long> granted = new ArrayList<>();
    for (Record row : rows) {
      assertThat(row.get("operator_id", Long.class)).isEqualTo(operatorId);
      JsonNode meta = objectMapper.readTree(row.get("metadata", String.class));
      assertThat(meta.get("plane").asText()).isEqualTo("platform");
      assertThat(meta.get("added").get(0).asText()).isEqualTo("ADMIN");
      granted.add(meta.get("userId").asLong());
    }
    assertThat(granted).containsExactlyInAnyOrder(target, ownerUserId);
  }

  /**
   * 콘솔 지정도 그 워크스페이스의 감사 화면에 남는다(WD-1158) — 테넌트 범위 OWNER_TRANSFER 1건(metadata.via=console)과 ADMIN 을
   * 받은 사용자마다 ROLE_ASSIGN. 다른 테넌트 범위에서는 보이지 않는다.
   */
  @Test
  void assignOwner_writesWorkspaceScopedAudit() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    long otherTenant = createTenant();
    // 옛 소유자의 ADMIN 을 떼어 대상·옛 소유자 둘 다 ADMIN 을 새로 받게 한다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and tenant_id = ?",
                ownerUserId,
                tenantId));

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName(tenantId));

    assertThat(response.getStatus()).isEqualTo(204);
    List<Record> transfers = scopedAudit(tenantId, "OWNER_TRANSFER");
    assertThat(transfers).hasSize(1);
    JsonNode meta = objectMapper.readTree(transfers.get(0).get("metadata", String.class));
    assertThat(meta.get("via").asText()).isEqualTo("console");
    assertThat(meta.get("toUserId").asLong()).isEqualTo(target);
    List<Record> roleRows = scopedAudit(tenantId, "ROLE_ASSIGN");
    assertThat(roleRows).hasSize(2);
    List<String> granted = new ArrayList<>();
    for (Record row : roleRows) {
      granted.add(row.get("resource_id", String.class));
      // 공용 감사 도우미가 쓴 행 모양 — 콘솔 경로만 via=console 을 싣고, ADMIN 추가만 있다(WD-1158)
      JsonNode roleMeta = objectMapper.readTree(row.get("metadata", String.class));
      assertThat(roleMeta.get("via").asText()).isEqualTo("console");
      assertThat(roleMeta.get("added").toString()).isEqualTo("[\"ADMIN\"]");
      assertThat(roleMeta.get("removed").size()).isZero();
      assertThat(roleMeta.get("before").toString()).doesNotContain("ADMIN");
      assertThat(roleMeta.get("after").toString()).contains("ADMIN");
    }
    assertThat(granted)
        .containsExactlyInAnyOrder(String.valueOf(target), String.valueOf(ownerUserId));
    // 다른 테넌트 범위에서는 0건
    assertThat(scopedAudit(otherTenant, "OWNER_TRANSFER")).isEmpty();
    assertThat(scopedAudit(otherTenant, "ROLE_ASSIGN")).isEmpty();
  }

  /**
   * 플랫폼 OWNER_TRANSFER 행 details 에 새 소유자 username(toUsername)과 옛 소유자 username 목록(fromUsernames)이
   * 실린다(WD-1158).
   */
  @Test
  void assignOwner_platformAuditIncludesUsernames() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    String targetName =
        dsl.fetchOne("select username from \"user\" where id = ?", target).get(0, String.class);
    String ownerName =
        dsl.fetchOne("select username from \"user\" where id = ?", ownerUserId)
            .get(0, String.class);

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName(tenantId));

    assertThat(response.getStatus()).isEqualTo(204);
    JsonNode meta = objectMapper.readTree(platformAudit(tenantId).get("metadata", String.class));
    assertThat(meta.get("toUsername").asText()).isEqualTo(targetName);
    assertThat(meta.get("fromUsernames")).hasSize(1);
    assertThat(meta.get("fromUsernames").get(0).asText()).isEqualTo(ownerName);
  }

  /** 소유자가 없는 테넌트(복구 상황)에서는 대상만 OWNER 가 되고 fromUserIds 는 빈 목록이다. */
  @Test
  void assignOwner_noExistingOwner_promotesOnly() throws Exception {
    long tenantId = createTenant();
    dsl.execute(
        "update membership set role = 'ADMIN' where user_id = ? and tenant_id = ?",
        ownerUserId,
        tenantId);
    long target = addMember(tenantId, "MEMBER");

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(membershipRole(target, tenantId)).isEqualTo("OWNER");
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("ADMIN");
    JsonNode meta = objectMapper.readTree(platformAudit(tenantId).get("metadata", String.class));
    assertThat(meta.get("fromUserIds").isArray()).isTrue();
    assertThat(meta.get("fromUserIds")).isEmpty();
  }

  /** 감사 기록이 실패하면 소유자 변경도 함께 롤백된다 — 소유자만 바뀌고 감사가 없는 상태를 남기지 않는다(WD-1132 리뷰). */
  @Test
  void assignOwner_auditFailure_rollsBackOwnerChange() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    // 첫 감사(OWNER_TRANSFER)는 통과하고 ROLE_ASSIGN 에서 실패 — 이미 쓴 감사 행까지 함께 되돌아가는지 본다.
    doThrow(new IllegalStateException("감사 기록 실패(시험)"))
        .when(auditRecorder)
        .record(anyLong(), eq("ROLE_ASSIGN"), any(), any(), any(), any());

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isGreaterThanOrEqualTo(400);
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("OWNER");
    assertThat(membershipRole(target, tenantId)).isEqualTo("MEMBER");
    assertThat(platformAudit(tenantId)).isNull();
  }

  /** 이미 유일한 소유자인 사람을 지정하면 409 이고 감사는 남지 않는다. */
  @Test
  void assignOwner_alreadyOwner_409() throws Exception {
    long tenantId = createTenant();

    MockHttpServletResponse response = assignOwner(tenantId, ownerUserId, tenantName);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(platformAudit(tenantId)).isNull();
  }

  /** 이 테넌트 멤버가 아닌 계정은 404 이고 소유자는 그대로다. */
  @Test
  void assignOwner_nonMember_404() throws Exception {
    long tenantId = createTenant();
    long stranger = createUser();

    MockHttpServletResponse response = assignOwner(tenantId, stranger, tenantName);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("OWNER");
    assertThat(platformAudit(tenantId)).isNull();
  }

  /** 확인 이름이 다르면(대소문자 포함) 400 이고 아무것도 바뀌지 않는다. */
  @Test
  void assignOwner_wrongConfirmName_400() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName.toUpperCase());

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("OWNER");
    assertThat(membershipRole(target, tenantId)).isEqualTo("MEMBER");
    assertThat(platformAudit(tenantId)).isNull();
  }

  /** 정지된 테넌트도 허용한다 — 소유자와 연락이 안 될 때 복구 수단이라 막으면 안 된다. */
  @Test
  void assignOwner_suspendedTenant_allowed() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    MockHttpServletResponse suspend =
        mockMvc
            .perform(authed(post("/api/platform/tenants/" + tenantId + "/suspend")))
            .andReturn()
            .getResponse();
    // 정지가 실제로 먹었는지 먼저 확인한다 — 아니면 「정지 상태에서도 허용」을 검증하는 게 아니게 된다.
    assertThat(suspend.getStatus()).as("테넌트 정지").isIn(200, 204);
    assertThat(
            dsl.fetchOne("select status from tenant where id = ?", tenantId).get(0, String.class))
        .isEqualTo("SUSPENDED");

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(membershipRole(target, tenantId)).isEqualTo("OWNER");
  }

  /**
   * 정지된 테넌트에서도 ADMIN 부여가 정상이다(WD-1158) — 활성 판정이 테넌트 상태를 보면 정지 테넌트에서는 새 소유자도 활성 옛 소유자도 ADMIN 을 받지
   * 못한다.
   */
  @Test
  void assignOwner_suspendedTenant_stillGrantsAdmin() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and tenant_id = ?",
                ownerUserId,
                tenantId));
    MockHttpServletResponse suspend =
        mockMvc
            .perform(authed(post("/api/platform/tenants/" + tenantId + "/suspend")))
            .andReturn()
            .getResponse();
    assertThat(suspend.getStatus()).as("테넌트 정지").isIn(200, 204);

    MockHttpServletResponse response = assignOwner(tenantId, target, tenantName);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(roleNames(target, tenantId)).contains("ADMIN");
    assertThat(roleNames(ownerUserId, tenantId)).contains("ADMIN");
  }

  /** 없는 테넌트는 404. */
  @Test
  void assignOwner_unknownTenant_404() throws Exception {
    MockHttpServletResponse response = assignOwner(99_999_999L, ownerUserId, "x");

    assertThat(response.getStatus()).isEqualTo(404);
  }

  /** 필수 값(userId·confirmName)이 없으면 400. */
  @Test
  void assignOwner_missingFields_400() throws Exception {
    long tenantId = createTenant();

    MockHttpServletResponse response =
        mockMvc
            .perform(
                authed(post("/api/platform/tenants/" + tenantId + "/owner"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  /** 플랫폼 롤이 없는 플랫폼 토큰은 403, 테넌트(일반) 토큰은 접근 불가이며 아무것도 바뀌지 않는다. */
  @Test
  void assignOwner_requiresPlatformOperator() throws Exception {
    long tenantId = createTenant();
    long target = addMember(tenantId, "MEMBER");
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(), "nobody");
    String tenantToken = jwtTokenProvider.generateAccessToken(ownerUserId, "owner", tenantId);

    MockHttpServletResponse weakResponse = assignOwnerWith(weak, tenantId, target, tenantName);
    MockHttpServletResponse tenantResponse =
        assignOwnerWith(tenantToken, tenantId, target, tenantName);

    assertThat(weakResponse.getStatus()).isEqualTo(403);
    assertThat(tenantResponse.getStatus()).isIn(401, 403);
    assertThat(membershipRole(ownerUserId, tenantId)).isEqualTo("OWNER");
    assertThat(platformAudit(tenantId)).isNull();
  }

  // ── 헬퍼 ────────────────────────────────────────────────────────────────────

  /** 플랫폼 API 로 테넌트를 만든다 — 실제 프로비저닝(ADMIN 역할 시드)을 거쳐야 역할 부여 경로가 실전과 같다. */
  private long createTenant() throws Exception {
    String slug = "wd1132-" + System.nanoTime();
    tenantName = slug;
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

  /** 새 계정을 만들어 주어진 라벨의 ACTIVE 멤버로 붙인다(역할 없음 — ADMIN 부여를 검증하려고). */
  private long addMember(long tenantId, String role) {
    long userId = createUser();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, tenantId, role);
    return userId;
  }

  private MockHttpServletResponse assignOwner(long tenantId, Long userId, String confirmName)
      throws Exception {
    return assignOwnerWith(token, tenantId, userId, confirmName);
  }

  private MockHttpServletResponse assignOwnerWith(
      String bearer, long tenantId, Long userId, String confirmName) throws Exception {
    return mockMvc
        .perform(
            post("/api/platform/tenants/" + tenantId + "/owner")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("userId", userId, "confirmName", confirmName))))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token);
  }

  private String membershipRole(long userId, long tenantId) {
    Record row =
        dsl.fetchOne(
            "select role from membership where user_id = ? and tenant_id = ?", userId, tenantId);
    return row == null ? null : row.get(0, String.class);
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

  /**
   * 테넌트 {@code scope} 범위(RLS)에서 보이는 콘솔 지정 감사 행(metadata.via=console). tenant_id 조건 없이 RLS 가 거른다.
   */
  private List<Record> scopedAudit(long scope, String action) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        scope,
        () ->
            dsl.fetch(
                "select resource_id, metadata::text as metadata from audit_log"
                    + " where action_type = ? and metadata->>'via' = 'console'",
                action));
  }

  private String tenantName(long tenantId) {
    return dsl.fetchOne("select name from tenant where id = ?", tenantId).get(0, String.class);
  }

  /** tenant NULL 플랫폼 감사 행(OWNER_TRANSFER). 없으면 null. */
  private Record platformAudit(long tenantId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        null,
        () ->
            dsl.fetchOne(
                "select user_id as operator_id, metadata::text as metadata from audit_log"
                    + " where action_type = 'OWNER_TRANSFER' and resource = 'tenant'"
                    + " and resource_id = ? and tenant_id is null",
                String.valueOf(tenantId)));
  }

  /** 검증용 사용자. 공유 test DB 라 나노초로 유일화한다. */
  private long createUser() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd1132-u-" + System.nanoTime(), "{noop}x");
    createdUsers.add(userId);
    return userId;
  }
}
