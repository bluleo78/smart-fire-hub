package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.charset.StandardCharsets;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 데이터셋 저장소 API(WD-73 Task 5): 백엔드 목록·연결 테스트·조회·검증·스크립트 재발급.
 *
 * <p>새 Spring 컨텍스트를 만들지 않도록 {@code PlatformTenantControllerTest} 와 같은 설정(추가 프로퍼티·MockitoBean 없음)을
 * 쓴다. Tibero JDBC 드라이버는 테스트 클래스패스에 없으므로 외부 접속은 항상 "드라이버 없음" 으로 즉시 실패한다.
 */
@AutoConfigureMockMvc
class PlatformTenantDataStoreControllerTest extends IntegrationTestBase {

  private static final String SCRIPT_STORE =
      "\"dataStore\":{\"backend\":\"TIBERO\",\"host\":\"db.example.com\",\"port\":8629,"
          + "\"database\":\"TIBERO\",\"mode\":\"SCRIPT\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  private TransactionTemplate tx;
  private String token;
  private Long ownerUserId;
  private Long createdTenant;

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(transactionManager);
    ownerUserId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd73-ds-owner-" + System.nanoTime(), "{noop}x");
    long operator =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd73-ds-op-" + System.nanoTime(), "{noop}x");
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, operator);
    token = jwtTokenProvider.generatePlatformAccessToken(operator, "ops");
    // 운영자 요청 스레드에는 테넌트 컨텍스트가 없다(PlatformTenantControllerTest 와 같은 이유).
    TenantContext.clear();
  }

  @AfterEach
  void cleanUp() {
    if (createdTenant != null) {
      long id = createdTenant;
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          null,
          () ->
              dsl.execute(
                  "delete from audit_log where resource = 'tenant' and resource_id = ?",
                  String.valueOf(id)));
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, createdTenant);
      createdTenant = null;
    }
  }

  /** 테스트 프로필은 Tibero 플래그가 켜져 있다 — TIBERO 사용 가능, MYSQL 은 항상 불가. 순서도 고정. */
  @Test
  void backends_reflectsFeatureFlag() throws Exception {
    MockHttpServletResponse res =
        mockMvc
            .perform(authed(get("/api/platform/tenants/data-store/backends")))
            .andReturn()
            .getResponse();

    assertThat(res.getStatus()).isEqualTo(200);
    JsonNode json = objectMapper.readTree(res.getContentAsString());
    assertThat(json).hasSize(3);
    assertThat(json.get(0).get("backend").asText()).isEqualTo("POSTGRESQL");
    assertThat(json.get(0).get("available").asBoolean()).isTrue();
    assertThat(json.get(1).get("backend").asText()).isEqualTo("TIBERO");
    assertThat(json.get(1).get("available").asBoolean()).isTrue();
    assertThat(json.get(2).get("backend").asText()).isEqualTo("MYSQL");
    assertThat(json.get(2).get("available").asBoolean()).isFalse();
  }

  /**
   * 리터럴 경로 {@code /data-store} 는 {@code /{tenantId}} 와 겹친다. 숫자가 아닌 id 로 해석돼도 500 이 아니라 4xx 여야 하고,
   * 하위 리터럴 {@code /data-store/backends} 는 그대로 200 목록이어야 한다.
   */
  @Test
  void dataStoreLiteralPath_doesNotCollideWithTenantIdRoute() throws Exception {
    MockHttpServletResponse bare =
        mockMvc.perform(authed(get("/api/platform/tenants/data-store"))).andReturn().getResponse();
    assertThat(bare.getStatus()).isBetween(400, 499);

    MockHttpServletResponse backends =
        mockMvc
            .perform(authed(get("/api/platform/tenants/data-store/backends")))
            .andReturn()
            .getResponse();
    assertThat(backends.getStatus()).isEqualTo(200);
    assertThat(objectMapper.readTree(backends.getContentAsString()).isArray()).isTrue();
  }

  /** 드라이버가 없어도 500 이 아니라 200 + ok=false 와 한국어 사유, 비밀번호는 응답에 없다. */
  @Test
  void test_driverMissing_200_okFalseWithReason() throws Exception {
    MockHttpServletResponse res =
        postJson(
            "/api/platform/tenants/data-store/test",
            "{\"backend\":\"TIBERO\",\"host\":\"db.example.com\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"credentials\":{\"username\":\"sys\","
                + "\"password\":\"TopSecretDbaPw1\"}}");

    assertThat(res.getStatus()).isEqualTo(200);
    String body = res.getContentAsString(StandardCharsets.UTF_8);
    JsonNode json = objectMapper.readTree(body);
    assertThat(json.get("ok").asBoolean()).isFalse();
    assertThat(json.get("message").asText()).contains("Tibero JDBC 드라이버가 설치되지 않았습니다");
    assertThat(body).doesNotContain("TopSecretDbaPw1");
  }

  /** host 에 JDBC 파라미터 주입 문자가 있으면 400 이고 비밀번호가 응답에 새지 않는다. */
  @Test
  void test_invalidHost_400() throws Exception {
    MockHttpServletResponse res =
        postJson(
            "/api/platform/tenants/data-store/test",
            "{\"backend\":\"TIBERO\",\"host\":\"db;sslmode=off\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"credentials\":{\"username\":\"sys\","
                + "\"password\":\"TopSecretDbaPw1\"}}");

    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(res.getContentAsString(StandardCharsets.UTF_8))
        .doesNotContain("TopSecretDbaPw1")
        .doesNotContain("sslmode");
  }

  /** TIBERO 가 아닌 백엔드는 JDBC URL 을 만들 수 없어 400. */
  @Test
  void test_mysqlBackend_400() throws Exception {
    MockHttpServletResponse res =
        postJson(
            "/api/platform/tenants/data-store/test",
            "{\"backend\":\"MYSQL\",\"host\":\"db.example.com\",\"port\":3306,"
                + "\"database\":\"x\",\"credentials\":{\"username\":\"root\","
                + "\"password\":\"pw\"}}");

    assertThat(res.getStatus()).isEqualTo(400);
  }

  /** SCRIPT 생성 직후: PENDING, 계정 이름 12개, 응답 JSON 어디에도 password 가 들어간 키도 저장된 암호문 값도 없다. */
  @Test
  void dataStore_detail_afterScriptCreate_pendingWith12Usernames_noPasswordFields()
      throws Exception {
    createScriptTenant();

    MockHttpServletResponse res =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant + "/data-store")))
            .andReturn()
            .getResponse();

    assertThat(res.getStatus()).isEqualTo(200);
    JsonNode json = objectMapper.readTree(res.getContentAsString());
    assertThat(json.get("backend").asText()).isEqualTo("TIBERO");
    assertThat(json.get("status").asText()).isEqualTo("PENDING");
    assertThat(json.get("mode").asText()).isEqualTo("SCRIPT");
    assertThat(json.get("schemaName").asText()).isEqualTo("GENIA_T" + createdTenant);
    assertThat(json.get("tablespace").asText()).isEqualTo("USR");
    assertThat(json.get("accountUsernames")).hasSize(12);
    assertThat(json.get("accountUsernames").get(0).asText()).isEqualTo("GENIA_T" + createdTenant);
    json.fieldNames().forEachRemaining(n -> assertThat(n.toLowerCase()).doesNotContain("password"));
    // 키 이름만이 아니라 본문 전체에 저장된 암호문이 하나도 없어야 한다.
    String body = res.getContentAsString(StandardCharsets.UTF_8);
    java.util.List<String> ciphertexts = storedPasswords();
    assertThat(ciphertexts).hasSize(12);
    for (String enc : ciphertexts) {
      assertThat(body).doesNotContain(enc);
    }
  }

  /** 저장소 행이 없는 PostgreSQL 테넌트는 기본 PostgreSQL 응답, 없는 테넌트의 조회·검증·재발급은 404. */
  @Test
  void dataStore_detail_plainTenantDefaults_unknownTenant404() throws Exception {
    String slug = "wd73-ds-plain-" + System.nanoTime();
    assertThat(createTenant(slug, "").getStatus()).isEqualTo(201);
    createdTenant = tenantId(slug);

    JsonNode json =
        objectMapper.readTree(
            mockMvc
                .perform(authed(get("/api/platform/tenants/" + createdTenant + "/data-store")))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(json.get("backend").asText()).isEqualTo("POSTGRESQL");
    assertThat(json.get("status").isNull()).isTrue();
    assertThat(json.get("accountUsernames")).isEmpty();

    assertThat(
            mockMvc
                .perform(authed(get("/api/platform/tenants/99999999/data-store")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            mockMvc
                .perform(authed(post("/api/platform/tenants/99999999/data-store/verify")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            mockMvc
                .perform(authed(post("/api/platform/tenants/99999999/data-store/script")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  /** 재발급: 새 비밀번호가 든 스크립트가 오고 저장된 암호문이 모두 바뀌며 감사에 비밀이 없다. */
  @Test
  void script_reissue_afterScriptCreate_returnsNewScript() throws Exception {
    String firstScript = createScriptTenant();
    java.util.List<String> before = storedPasswords();

    MockHttpServletResponse res =
        mockMvc
            .perform(authed(post("/api/platform/tenants/" + createdTenant + "/data-store/script")))
            .andReturn()
            .getResponse();

    assertThat(res.getStatus()).isEqualTo(200);
    String script = objectMapper.readTree(res.getContentAsString()).get("script").asText();
    assertThat(script).contains("CREATE USER \"GENIA_T" + createdTenant + "\"");
    assertThat(script).isNotEqualTo(firstScript);
    java.util.List<String> after = storedPasswords();
    assertThat(after).hasSize(12).doesNotContainAnyElementsOf(before);

    String metadata =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            () ->
                dsl.fetchOne(
                        "select metadata::text from audit_log where action_type ="
                            + " 'TENANT_DATA_STORE_SCRIPT_REISSUE' and resource_id = ?",
                        String.valueOf(createdTenant))
                    .get(0, String.class));
    assertThat(metadata).doesNotContain("IDENTIFIED", "password");
  }

  /** READY 로 바뀐 저장소는 재발급이 409 다. */
  @Test
  void script_reissue_whenReady_409() throws Exception {
    createScriptTenant();
    dsl.execute("update tenant_data_store set status = 'READY' where tenant_id = ?", createdTenant);

    MockHttpServletResponse res =
        mockMvc
            .perform(authed(post("/api/platform/tenants/" + createdTenant + "/data-store/script")))
            .andReturn()
            .getResponse();

    assertThat(res.getStatus()).isEqualTo(409);
    assertThat(objectMapper.readTree(res.getContentAsString()).get("code").asText())
        .isEqualTo("DATA_STORE_NOT_PENDING");
  }

  /** 외부 저장소가 없는 테넌트의 verify·script 는 400. */
  @Test
  void verify_postgresTenant_400() throws Exception {
    String slug = "wd73-ds-pg-" + System.nanoTime();
    createTenant(slug, "");
    createdTenant = tenantId(slug);

    assertThat(
            mockMvc
                .perform(
                    authed(post("/api/platform/tenants/" + createdTenant + "/data-store/verify")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            mockMvc
                .perform(
                    authed(post("/api/platform/tenants/" + createdTenant + "/data-store/script")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  /** 드라이버가 없으면 12개 계정이 모두 실패로 보고되고 PENDING 이 유지되며 감사는 FAILED 로 남는다. */
  @Test
  void verify_scriptTenant_driverMissing_allFail_staysPending() throws Exception {
    createScriptTenant();

    MockHttpServletResponse res =
        mockMvc
            .perform(authed(post("/api/platform/tenants/" + createdTenant + "/data-store/verify")))
            .andReturn()
            .getResponse();

    assertThat(res.getStatus()).isEqualTo(200);
    JsonNode json = objectMapper.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    assertThat(json.get("status").asText()).isEqualTo("PENDING");
    assertThat(json.get("result").asText()).isEqualTo("FAILED");
    assertThat(json.get("checks")).hasSize(12);
    json.get("checks").forEach(c -> assertThat(c.get("ok").asBoolean()).isFalse());
    String metadata =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            () ->
                dsl.fetchOne(
                        "select metadata::text from audit_log where action_type ="
                            + " 'TENANT_DATA_STORE_VERIFY' and resource_id = ?",
                        String.valueOf(createdTenant))
                    .get(0, String.class));
    assertThat(metadata).contains("FAILED");
  }

  /**
   * 조회 권한(platform:tenant:read)만 가진 운영자는 저장소 조회만 되고 백엔드 목록·연결 테스트·검증·재발급은 403 이다 (이 넷은
   * platform:tenant:create 가 필요하다).
   */
  @Test
  void readOnlyOperator_canViewButNotMutateDataStore() throws Exception {
    createScriptTenant();
    String roleName = "WD73_READONLY_" + System.nanoTime();
    long limited =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "wd73-ds-ro-" + System.nanoTime(), "{noop}x");
    dsl.execute("insert into platform_role (name, description) values (?, 'wd73 test')", roleName);
    dsl.execute(
        "insert into platform_role_permission (platform_role_id, permission_id)"
            + " select r.id, p.id from platform_role r, permission p"
            + " where r.name = ? and p.code = 'platform:tenant:read'",
        roleName);
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id)"
            + " select ?, id from platform_role where name = ?",
        limited,
        roleName);
    String limitedToken = jwtTokenProvider.generatePlatformAccessToken(limited, "ro");
    try {
      String base = "/api/platform/tenants";
      String id = String.valueOf(createdTenant);
      assertThat(status(get(base + "/data-store/backends"), limitedToken)).isEqualTo(403);
      assertThat(status(post(base + "/data-store/test"), limitedToken)).isEqualTo(403);
      assertThat(status(post(base + "/" + id + "/data-store/verify"), limitedToken)).isEqualTo(403);
      assertThat(status(post(base + "/" + id + "/data-store/script"), limitedToken)).isEqualTo(403);
      assertThat(status(get(base + "/" + id + "/data-store"), limitedToken)).isEqualTo(200);
    } finally {
      // platform_user_role 은 롤 삭제로 함께 지워진다(ON DELETE CASCADE).
      dsl.execute("delete from platform_role where name = ?", roleName);
    }
  }

  private int status(MockHttpServletRequestBuilder builder, String bearer) throws Exception {
    return mockMvc
        .perform(
            builder
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** 테넌트 평면 토큰은 플랫폼 저장소 API 에 접근하지 못한다(401/403). */
  @Test
  void endpoints_requirePlatformToken() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(ownerUserId, "tenant-user", 1L);
    String[] gets = {
      "/api/platform/tenants/data-store/backends", "/api/platform/tenants/1/data-store"
    };
    for (String url : gets) {
      assertDenied(get(url).header("Authorization", "Bearer " + tenantToken));
      assertThat(mockMvc.perform(get(url)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    String[] posts = {
      "/api/platform/tenants/data-store/test",
      "/api/platform/tenants/1/data-store/verify",
      "/api/platform/tenants/1/data-store/script"
    };
    for (String url : posts) {
      assertDenied(
          post(url)
              .header("Authorization", "Bearer " + tenantToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"));
    }
  }

  private void assertDenied(MockHttpServletRequestBuilder request) throws Exception {
    int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
    assertThat(status).isIn(401, 403);
  }

  // ── 헬퍼 ────────────────────────────────────────────────────────────────────

  /** 연결 테스트처럼 JSON 본문을 가진 POST 를 운영자 토큰으로 보낸다. */
  private MockHttpServletResponse postJson(String url, String json) throws Exception {
    return mockMvc
        .perform(authed(post(url)).contentType(MediaType.APPLICATION_JSON).content(json))
        .andReturn()
        .getResponse();
  }

  /** SCRIPT 모드 Tibero 테넌트를 만들고 생성 응답의 스크립트를 돌려준다. */
  private String createScriptTenant() throws Exception {
    String slug = "wd73-ds-" + System.nanoTime();
    MockHttpServletResponse res = createTenant(slug, "," + SCRIPT_STORE);
    assertThat(res.getStatus()).isEqualTo(201);
    createdTenant = tenantId(slug);
    return objectMapper.readTree(res.getContentAsString()).get("dataStoreSetupScript").asText();
  }

  private MockHttpServletResponse createTenant(String slug, String extraMember) throws Exception {
    return mockMvc
        .perform(
            authed(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/platform/tenants"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"%s\",\"name\":\"%s\",\"ownerUserId\":%d%s}"
                        .formatted(slug, slug, ownerUserId, extraMember)))
        .andReturn()
        .getResponse();
  }

  private java.util.List<String> storedPasswords() {
    return dsl.fetch(
            "select password_enc from tenant_data_store_account where tenant_id = ?", createdTenant)
        .map(r -> r.get(0, String.class));
  }

  private Long tenantId(String slug) {
    return dsl.fetchOne("select id from tenant where slug = ?", slug).get(0, Long.class);
  }

  private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token);
  }
}
