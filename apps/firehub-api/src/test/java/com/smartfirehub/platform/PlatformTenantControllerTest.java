package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테넌트 생명주기 API.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 생성은 자기 트랜잭션에서 커밋돼야 하고,
 * 검증 조회는 그 밖에서 GUC 를 세워 RLS 를 통과해야 한다.
 */
@AutoConfigureMockMvc
class PlatformTenantControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private String token;
  private Long ownerUserId;
  private Long createdTenant;

  @BeforeEach
  void setUpPlatformSession() {
    tx = new TransactionTemplate(transactionManager);
    ownerUserId = createUser(false);
    token = jwtTokenProvider.generatePlatformAccessToken(createUser(true), "ops");
  }

  /** 만든 테넌트를 지운다 — 공유 test DB 라 자기 것만 지운다. */
  @AfterEach
  void cleanUp() {
    if (createdTenant != null) {
      TenantRlsTestSupport.runInTenantTransaction(
          tx, createdTenant, () -> TenantRlsTestSupport.deleteRbacCascade(dsl, createdTenant));
      dsl.execute("delete from membership where tenant_id = ?", createdTenant);
      TenantRlsTestSupport.deleteTenants(dsl, createdTenant);
      createdTenant = null;
    }
  }

  /**
   * 생성이 롤·권한매핑·내장 양식·기본 카테고리를 모두 갖춘 테넌트를 만든다.
   *
   * <p>카테고리는 여기서만 실전 검증된다 — Task 1 의 테스트는 테넌트 행을 별도로 커밋한 뒤
   * 프로비저닝을 부르지만, 실제 생성 경로는 "같은 트랜잭션에서 insert 직후 SECURITY DEFINER 함수
   * 호출"이라 스냅샷 가시성이 다르다.
   */
  @Test
  void createTenant_provisionsRolesPermissionsTemplatesAndCategories() throws Exception {
    String slug = "p7a-create-" + System.nanoTime();

    MockHttpServletResponse response = createTenant(slug, ownerUserId);

    assertThat(response.getStatus()).isEqualTo(201);
    createdTenant = tenantIdBySlug(slug);

    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        createdTenant,
        () -> {
          List<String> roles =
              dsl.fetch("select name from role where tenant_id = ?", createdTenant)
                  .map(r -> r.get("name", String.class));
          assertThat(roles).contains("ADMIN", "USER");

          Integer rolePermissions =
              dsl.fetchOne("select count(*) from role_permission where tenant_id = ?", createdTenant)
                  .get(0, Integer.class);
          assertThat(rolePermissions).isPositive();

          Integer templates =
              dsl.fetchOne(
                      "select count(*) from report_template where tenant_id = ? and user_id is null",
                      createdTenant)
                  .get(0, Integer.class);
          assertThat(templates).isEqualTo(3);

          List<String> categories =
              dsl.fetch("select name from dataset_category where tenant_id = ?", createdTenant)
                  .map(r -> r.get("name", String.class));
          assertThat(categories).containsExactlyInAnyOrder("행정", "운영", "통계");
          return null;
        });
  }

  /** 초기 Owner 멤버십이 OWNER/ACTIVE 로 생긴다 — 없으면 아무도 그 테넌트에 들어갈 수 없다. */
  @Test
  void createTenant_addsOwnerMembership() throws Exception {
    String slug = "p7a-owner-" + System.nanoTime();

    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    var row =
        dsl.fetchOne(
            "select role, status from membership where tenant_id = ? and user_id = ?",
            createdTenant,
            ownerUserId);
    assertThat(row).isNotNull();
    assertThat(row.get("role", String.class)).isEqualTo("OWNER");
    assertThat(row.get("status", String.class)).isEqualTo("ACTIVE");
  }

  /** 없는 사용자를 Owner 로 지정하면 400 이고 테넌트도 만들어지지 않는다. */
  @Test
  void createTenant_rejectsUnknownOwner() throws Exception {
    String slug = "p7a-badowner-" + System.nanoTime();

    assertThat(createTenant(slug, 99_999_999L).getStatus()).isEqualTo(400);
    assertThat(tenantIdBySlugOrNull(slug)).isNull();
  }

  /** slug 중복은 400 이다(UNIQUE 위반을 500 으로 흘리지 않는다). */
  @Test
  void createTenant_rejectsDuplicateSlug() throws Exception {
    String slug = "p7a-dup-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    assertThat(createTenant(slug, ownerUserId).getStatus()).isEqualTo(400);
  }

  /** 정지 → 활성화가 왕복한다. */
  @Test
  void suspendThenActivate_roundTrips() throws Exception {
    String slug = "p7a-suspend-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/suspend")))
        .andExpect(status().isNoContent());
    assertThat(statusOf(createdTenant)).isEqualTo("SUSPENDED");

    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/activate")))
        .andExpect(status().isNoContent());
    assertThat(statusOf(createdTenant)).isEqualTo("ACTIVE");
  }

  /** 없는 테넌트를 정지하면 404 다. */
  @Test
  void suspend_unknownTenantIs404() throws Exception {
    mockMvc
        .perform(authed(post("/api/platform/tenants/99999999/suspend")))
        .andExpect(status().isNotFound());
  }

  /**
   * 상세 응답에 도메인 데이터가 없다.
   *
   * <p>설계서 §4 는 크로스테넌트 도메인 조회를 제공하지 않기로 결정했다 — 운영자 화면이 데이터셋
   * 목록을 받기 시작하면 그 결정이 조용히 무너진다.
   */
  @Test
  void tenantDetail_containsNoDomainData() throws Exception {
    String slug = "p7a-detail-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    String body =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"slug\":\"" + slug + "\"", "\"memberCount\":1");
    assertThat(body).doesNotContain("dataset", "pipeline", "ontology", "document");
  }

  /** 멤버 목록에 초기 Owner 가 보인다. */
  @Test
  void members_listsOwner() throws Exception {
    String slug = "p7a-members-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    String body =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant + "/members")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"userId\":" + ownerUserId, "\"role\":\"OWNER\"");
  }

  /** 플랫폼 롤이 없는 사용자의 토큰은 권한이 비어 403 이다. */
  @Test
  void withoutPlatformPermissions_isForbidden() throws Exception {
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(false), "nobody");

    mockMvc
        .perform(get("/api/platform/tenants").header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  // ── 헬퍼 ────────────────────────────────────────────────────────────────────

  private MockHttpServletResponse createTenant(String slug, Long ownerId) throws Exception {
    return mockMvc
        .perform(
            authed(post("/api/platform/tenants"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"%s\",\"name\":\"%s\",\"ownerUserId\":%d}"
                        .formatted(slug, slug, ownerId)))
        .andReturn()
        .getResponse();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token);
  }

  private String statusOf(long tenantId) {
    return dsl.fetchOne("select status from tenant where id = ?", tenantId).get(0, String.class);
  }

  private Long tenantIdBySlug(String slug) {
    Long id = tenantIdBySlugOrNull(slug);
    assertThat(id).as("생성한 테넌트를 slug 로 찾아야 한다").isNotNull();
    return id;
  }

  private Long tenantIdBySlugOrNull(String slug) {
    var record = dsl.fetchOne("select id from tenant where slug = ?", slug);
    return record == null ? null : record.get(0, Long.class);
  }

  /** 검증용 사용자. 공유 test DB 라 나노초로 유일화한다. */
  private long createUser(boolean platformRole) {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p7a-tenant-" + System.nanoTime(), "{noop}x");
    if (platformRole) {
      TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, userId);
    }
    return userId;
  }
}
