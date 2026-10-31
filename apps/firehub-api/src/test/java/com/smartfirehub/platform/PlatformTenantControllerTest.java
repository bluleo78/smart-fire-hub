package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OpenedSseStreams;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TiberoDriverAssumptions;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 테넌트 생명주기 API.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 생성은 자기 트랜잭션에서 커밋돼야 하고, 검증 조회는 그 밖에서 GUC 를 세워 RLS 를
 * 통과해야 한다.
 */
@AutoConfigureMockMvc
class PlatformTenantControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SseEmitterRegistry sseEmitterRegistry;
  @Autowired private PlatformTenantService platformTenantService;

  private TransactionTemplate tx;
  private String token;
  private Long ownerUserId;
  private long operatorId;
  private Long createdTenant;
  // 테스트가 실제 레지스트리(싱글턴)에 연 알림 연결 — 다른 테스트의 브로드캐스트에 섞이지 않게 정리한다(WD-190)
  private OpenedSseStreams streams;

  @BeforeEach
  void setUpPlatformSession() {
    tx = new TransactionTemplate(transactionManager);
    streams = new OpenedSseStreams(sseEmitterRegistry);
    ownerUserId = createUser(false);
    operatorId = createUser(true);
    token = jwtTokenProvider.generatePlatformAccessToken(operatorId, "ops");
    // 운영 조건: 운영자 요청 스레드에는 테넌트 컨텍스트가 없다. JwtAuthenticationFilter 는 요청 끝(finally)에서만
    // 지우므로, 테스트 베이스가 세운 테넌트 1 이 남으면 감사 행이 tenant_id=1 로 새어 들어간다(WD-12).
    TenantContext.clear();
  }

  /** 만든 테넌트를 지운다 — 공유 test DB 라 자기 것만 지운다. */
  @AfterEach
  void cleanUp() {
    streams.removeAll();
    if (createdTenant != null) {
      // 구독 테스트가 실제 엔드포인트로 연 연결도 레지스트리에서 뺀다(WD-190)
      sseEmitterRegistry.closeTenant(createdTenant);
      // WD-12 감사 행(tenant NULL)은 테넌트 cascade 에 걸리지 않는다 — 컨텍스트 없이 resource_id 로 지운다.
      long id = createdTenant;
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          null,
          () -> {
            dsl.execute(
                "delete from audit_log where resource = 'tenant' and resource_id = ?",
                String.valueOf(id));
          });
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, createdTenant);
      createdTenant = null;
    }
  }

  /**
   * 생성이 롤·권한매핑·내장 양식·기본 카테고리를 모두 갖춘 테넌트를 만든다.
   *
   * <p>카테고리는 여기서만 실전 검증된다 — Task 1 의 테스트는 테넌트 행을 별도로 커밋한 뒤 프로비저닝을 부르지만, 실제 생성 경로는 "같은 트랜잭션에서 insert
   * 직후 SECURITY DEFINER 함수 호출"이라 스냅샷 가시성이 다르다.
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
              dsl.fetchOne(
                      "select count(*) from role_permission where tenant_id = ?", createdTenant)
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

  /** 비활성 계정을 Owner 로 지정하면 400 이고 테넌트도 만들어지지 않는다(WD-316) — 아무도 들어갈 수 없는 테넌트가 생기지 않게. */
  @Test
  void createTenant_rejectsDeactivatedOwner() throws Exception {
    String slug = "wd316-inactive-owner-" + System.nanoTime();
    dsl.execute("update \"user\" set is_active = false where id = ?", ownerUserId);

    MockHttpServletResponse response = createTenant(slug, ownerUserId);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("비활성화된 계정은 초기 Owner 로 지정할 수 없습니다");
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

  /**
   * WD-190: 워크스페이스를 정지하면 그 워크스페이스로 연 알림 연결이 사람과 무관하게 모두 닫힌다. 같은 사람의 다른 워크스페이스 연결은 그대로고, 다시 활성화할 때는
   * 아무것도 닫지 않는다.
   */
  @Test
  void suspend_closesAllStreamsOfThatTenant_activateDoesNot() throws Exception {
    String slug = "wd190-sse-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);
    long otherUser = createUser(false);
    SseEmitter ownerHere = streams.open(ownerUserId, createdTenant);
    SseEmitter otherHere = streams.open(otherUser, createdTenant);
    SseEmitter ownerElsewhere = streams.open(ownerUserId, DEFAULT_TEST_TENANT_ID);

    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/suspend")))
        .andExpect(status().isNoContent());

    assertThat(OpenedSseStreams.isClosed(ownerHere)).as("정지된 워크스페이스 연결").isTrue();
    assertThat(OpenedSseStreams.isClosed(otherHere)).as("다른 사람의 같은 워크스페이스 연결도 닫힌다").isTrue();
    assertThat(OpenedSseStreams.isClosed(ownerElsewhere)).as("다른 워크스페이스 연결은 유지").isFalse();

    SseEmitter beforeActivate = streams.open(ownerUserId, createdTenant);
    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/activate")))
        .andExpect(status().isNoContent());
    assertThat(OpenedSseStreams.isClosed(beforeActivate)).as("재활성은 닫지 않는다").isFalse();
  }

  /** WD-190: 정지가 롤백되면(여전히 ACTIVE) 연결을 닫지 않는다 — 닫기는 커밋 뒤에만 일어난다. */
  @Test
  void suspend_rolledBack_keepsStreamsOpen() throws Exception {
    String slug = "wd190-rollback-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);
    SseEmitter ownerHere = streams.open(ownerUserId, createdTenant);
    long tenant = createdTenant;
    long operator = ownerUserId; // 감사 행위자 — 롤백되므로 누구든 무방하다

    tx.executeWithoutResult(
        status -> {
          platformTenantService.suspend(tenant, operator);
          status.setRollbackOnly();
        });

    assertThat(statusOf(createdTenant)).as("롤백돼 여전히 ACTIVE").isEqualTo("ACTIVE");
    assertThat(OpenedSseStreams.isClosed(ownerHere)).as("롤백이면 연결 유지").isFalse();
  }

  /**
   * WD-190: 정지된 워크스페이스의 기존 토큰으로는 알림 구독이 403 이다 — 권한 판정은 워크스페이스 상태를 보지 않아, 연결을 닫아도 곧바로 200 으로 다시
   * 붙었다. 활성 워크스페이스는 200(회귀).
   */
  @Test
  void notificationSubscribe_okWhileActive_forbiddenAfterSuspend() throws Exception {
    String slug = "wd190-sub-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);
    String tenantToken = jwtTokenProvider.generateAccessToken(ownerUserId, slug, createdTenant);

    mockMvc
        .perform(
            get("/api/v1/notifications/stream")
                .header("Authorization", "Bearer " + tenantToken)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk());

    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/suspend")))
        .andExpect(status().isNoContent());
    TenantContext.clear(); // 필터는 요청 끝에만 지운다 — 다음 요청이 운영 조건과 같게

    mockMvc
        .perform(
            get("/api/v1/notifications/stream")
                .header("Authorization", "Bearer " + tenantToken)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isForbidden());
  }

  /** 생성이 커밋되고(롤백 안 됨) 플랫폼 감사 행이 tenant NULL 로 하나 남으며, 운영자 감사 API 에서 slug 로 찾힌다. */
  @Test
  void createTenant_commitsAndRecordsNullTenantAuditRow() throws Exception {
    String slug = "wd12-create-" + System.nanoTime();

    assertThat(createTenant(slug, ownerUserId).getStatus()).isEqualTo(201);
    createdTenant = tenantIdBySlug(slug);

    assertThat(auditCount("TENANT_CREATE", createdTenant)).isEqualTo(1L);
    mockMvc
        .perform(
            authed(get("/api/platform/audit-logs"))
                .param("actionType", "TENANT_CREATE")
                .param("target", slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].resourceId").value(String.valueOf(createdTenant)))
        .andExpect(jsonPath("$.content[0].metadata.plane").value("platform"))
        .andExpect(jsonPath("$.content[0].metadata.tenantSlug").value(slug));
  }

  /** 정지·활성화는 상태가 실제로 바뀔 때만 감사한다 — 같은 요청 재전송이 로그를 부풀리지 않는다. */
  @Test
  void suspendActivate_recordAuditOnlyOnChange() throws Exception {
    String slug = "wd12-status-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(authed(post("/api/platform/tenants/" + createdTenant + "/suspend")))
          .andExpect(status().isNoContent());
    }
    mockMvc
        .perform(authed(post("/api/platform/tenants/" + createdTenant + "/activate")))
        .andExpect(status().isNoContent());

    assertThat(auditCount("TENANT_SUSPEND", createdTenant)).isEqualTo(1L);
    assertThat(auditCount("TENANT_ACTIVATE", createdTenant)).isEqualTo(1L);
  }

  /**
   * 동시 정지·재개는 실제로 바꾼 요청만 감사에 남는다(WD-322).
   *
   * <p>앞 요청이 정지를 바꾼 뒤 커밋 전에 멈춘 사이 뒤 요청이 들어오면, 뒤 요청의 사전 조회는 아직 「활성」을 본다. 뒤 요청이 앞 요청의 행 잠금에서
   * 기다렸는지(secondBlocked)까지 단언해 경합 구간을 실제로 지났음을 보장한다 — 조건부 갱신을 지우면 감사가 2건이 되어 실패한다.
   */
  @Test
  void concurrentSuspendAndActivate_auditOnlyTheRequestThatChanged() throws Exception {
    String slug = "wd322-race-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);
    long id = createdTenant;

    var suspend =
        PausedTransactionRace.run(
            tx,
            null,
            () -> platformTenantService.suspend(id, operatorId),
            () -> {
              platformTenantService.suspend(id, operatorId);
              return null;
            });
    assertThat(suspend.secondBlocked()).as("뒤 요청이 앞 요청의 행 잠금에서 기다렸다").isTrue();
    assertThat(suspend.secondError()).as("뒤 요청은 무변화로 성공(204 와 같다)").isNull();
    assertThat(statusOf(id)).isEqualTo("SUSPENDED");
    assertThat(auditCount("TENANT_SUSPEND", id)).isEqualTo(1L);

    var activate =
        PausedTransactionRace.run(
            tx,
            null,
            () -> platformTenantService.activate(id, operatorId),
            () -> {
              platformTenantService.activate(id, operatorId);
              return null;
            });
    assertThat(activate.secondBlocked()).isTrue();
    assertThat(activate.secondError()).isNull();
    assertThat(statusOf(id)).isEqualTo("ACTIVE");
    assertThat(auditCount("TENANT_ACTIVATE", id)).isEqualTo(1L);
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
   * <p>설계서 §4 는 크로스테넌트 도메인 조회를 제공하지 않기로 결정했다 — 운영자 화면이 데이터셋 목록을 받기 시작하면 그 결정이 조용히 무너진다.
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

  /**
   * 생성일에 저장 TZ 오프셋이 붙고 실제 순간과 맞는다(WD-11).
   *
   * <p>tenant.created_at 은 감사 action_time 과 같은 {@code TIMESTAMP DEFAULT NOW()}(세션 TZ = JVM TZ
   * 벽시계)다. 오프셋 없이 내보내면 운영(UTC 저장)에서 admin 이 날짜를 잘라 KST 운영자에게 하루가 어긋난다. 오프셋이 없으면 parse 가 예외로 실패한다.
   */
  @Test
  void tenantDetail_createdAtCarriesStorageOffset() throws Exception {
    String slug = "wd11-created-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    String body =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    OffsetDateTime createdAt =
        OffsetDateTime.parse(objectMapper.readTree(body).get("createdAt").asText());
    assertThat(Duration.between(createdAt.toInstant(), Instant.now()).abs())
        .isLessThan(Duration.ofMinutes(2));
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

  /** 운영자가 비활성화한 계정은 멤버십이 ACTIVE 여도 accountActive=false 로 보인다(WD-313). */
  @Test
  void members_reportsGloballyDeactivatedAccount() throws Exception {
    String slug = "wd313-members-" + System.nanoTime();
    createTenant(slug, ownerUserId);
    createdTenant = tenantIdBySlug(slug);

    String active =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant + "/members")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(active).contains("\"status\":\"ACTIVE\"", "\"accountActive\":true");

    dsl.execute("update \"user\" set is_active = false where id = ?", ownerUserId);

    String deactivated =
        mockMvc
            .perform(authed(get("/api/platform/tenants/" + createdTenant + "/members")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(deactivated)
        .contains("\"userId\":" + ownerUserId, "\"status\":\"ACTIVE\"", "\"accountActive\":false");
  }

  /** 플랫폼 롤이 없는 사용자의 토큰은 권한이 비어 403 이다. */
  @Test
  void withoutPlatformPermissions_isForbidden() throws Exception {
    String weak = jwtTokenProvider.generatePlatformAccessToken(createUser(false), "nobody");

    mockMvc
        .perform(get("/api/platform/tenants").header("Authorization", "Bearer " + weak))
        .andExpect(status().isForbidden());
  }

  // ── WD-73: 테넌트 저장소 선택 ───────────────────────────────────────────────

  /** dataStore 없는 기존 요청은 그대로 201 이고 응답에 스크립트 키도, 저장소 행도 없다(PostgreSQL 회귀 방지). */
  @Test
  void create_withoutDataStore_unchanged_noStoreRow() throws Exception {
    String slug = "wd73-plain-" + System.nanoTime();

    MockHttpServletResponse response = createTenant(slug, ownerUserId);

    assertThat(response.getStatus()).isEqualTo(201);
    createdTenant = tenantIdBySlug(slug);
    var json = objectMapper.readTree(response.getContentAsString());
    assertThat(json.has("dataStoreSetupScript")).isFalse();
    assertThat(json.get("slug").asText()).isEqualTo(slug);
    assertThat(storeRowCount(createdTenant)).isZero();
  }

  /** SCRIPT 모드: 최상위 필드는 그대로(언랩) + 스크립트 1회, PENDING 행과 계정 12개, 감사에 비밀 없음. */
  @Test
  void create_tiberoScriptMode_returnsScriptOnce_pendingRow() throws Exception {
    String slug = "wd73-script-" + System.nanoTime();

    MockHttpServletResponse response =
        createTenantWithBody(
            slug,
            "\"dataStore\":{\"backend\":\"TIBERO\",\"host\":\"db.example.com\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"mode\":\"SCRIPT\"}");

    assertThat(response.getStatus()).isEqualTo(201);
    createdTenant = tenantIdBySlug(slug);
    var json = objectMapper.readTree(response.getContentAsString());
    assertThat(json.get("id").asLong()).isEqualTo(createdTenant);
    assertThat(json.get("slug").asText()).isEqualTo(slug);
    assertThat(json.has("memberCount")).isTrue();
    String script = json.get("dataStoreSetupScript").asText();
    assertThat(script).contains("CREATE USER \"GENIA_T" + createdTenant + "\"");

    var row =
        dsl.fetchOne(
            "select status, provision_mode, schema_name from tenant_data_store where tenant_id = ?",
            createdTenant);
    assertThat(row.get("status", String.class)).isEqualTo("PENDING");
    assertThat(row.get("provision_mode", String.class)).isEqualTo("SCRIPT");
    assertThat(row.get("schema_name", String.class)).isEqualTo("GENIA_T" + createdTenant);
    assertThat(
            dsl.fetchOne(
                    "select count(*) from tenant_data_store_account where tenant_id = ?",
                    createdTenant)
                .get(0, Integer.class))
        .isEqualTo(12);

    // 저장된 값은 평문이 아니다.
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("IDENTIFIED BY \"([A-Za-z0-9]+)\"").matcher(script);
    List<String> passwords = new java.util.ArrayList<>();
    while (m.find()) {
      passwords.add(m.group(1));
    }
    assertThat(passwords).hasSize(12);
    List<String> stored =
        dsl.fetch(
                "select password_enc from tenant_data_store_account where tenant_id = ?",
                createdTenant)
            .map(r -> r.get(0, String.class));
    for (String pw : passwords) {
      assertThat(stored).doesNotContain(pw);
    }

    // 감사: 백엔드는 남고 비밀번호는 어디에도 없다.
    String metadata =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            null,
            () ->
                dsl.fetchOne(
                        "select metadata::text from audit_log where action_type = 'TENANT_CREATE'"
                            + " and resource = 'tenant' and resource_id = ?",
                        String.valueOf(createdTenant))
                    .get(0, String.class));
    assertThat(metadata).contains("\"dataStoreBackend\"").contains("TIBERO");
    for (String pw : passwords) {
      assertThat(metadata).doesNotContain(pw);
    }
  }

  /** DBA 자동 + 드라이버 없음: 422 와 고정 코드, 테넌트는 롤백돼 남지 않고 응답에 DBA 비밀번호가 없다. */
  @Test
  void create_tiberoDbaAuto_driverMissing_422_andNoTenantCreated() throws Exception {
    TiberoDriverAssumptions.assumeDriverAbsent();
    String slug = "wd73-dba-" + System.nanoTime();

    MockHttpServletResponse response =
        createTenantWithBody(
            slug,
            "\"dataStore\":{\"backend\":\"TIBERO\",\"host\":\"db.example.com\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"mode\":\"DBA_AUTO\","
                + "\"dba\":{\"username\":\"sys\",\"password\":\"TopSecretDbaPw1\"}}");

    assertThat(response.getStatus()).isEqualTo(422);
    String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(objectMapper.readTree(body).get("code").asText())
        .isEqualTo("DATA_STORE_PROVISIONING_FAILED");
    assertThat(body).contains("드라이버").doesNotContain("TopSecretDbaPw1");
    assertThat(tenantIdBySlugOrNull(slug)).isNull();
  }

  /** host 에 JDBC 파라미터 주입 문자가 있으면 400. */
  @Test
  void create_invalidHost_400() throws Exception {
    String slug = "wd73-badhost-" + System.nanoTime();

    MockHttpServletResponse response =
        createTenantWithBody(
            slug,
            "\"dataStore\":{\"backend\":\"TIBERO\",\"host\":\"db;sslmode=off\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"mode\":\"SCRIPT\"}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).doesNotContain("sslmode");
    assertThat(tenantIdBySlugOrNull(slug)).isNull();
  }

  /**
   * tablespace 형식 검사는 DTO 의 Bean Validation({@code TABLESPACE_REGEX})이 맡는다 — 서비스 validate 는 형식을 다시
   * 보지 않으므로 컨트롤러에서 400 으로 걸러져야 한다.
   */
  @Test
  void create_invalidTablespace_400() throws Exception {
    String slug = "wd73-badts-" + System.nanoTime();

    MockHttpServletResponse response =
        createTenantWithBody(
            slug,
            "\"dataStore\":{\"backend\":\"TIBERO\",\"host\":\"db.example.com\",\"port\":8629,"
                + "\"database\":\"TIBERO\",\"tablespace\":\"USR\\\" QUOTA\",\"mode\":\"SCRIPT\"}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(tenantIdBySlugOrNull(slug)).isNull();
  }

  /** MYSQL 은 항상 사용 불가 — 400 과 고정 코드, 테넌트 미생성. */
  @Test
  void create_mysql_400_backendUnavailable() throws Exception {
    String slug = "wd73-mysql-" + System.nanoTime();

    MockHttpServletResponse response =
        createTenantWithBody(
            slug,
            "\"dataStore\":{\"backend\":\"MYSQL\",\"host\":\"db.example.com\",\"port\":3306,"
                + "\"database\":\"x\",\"mode\":\"SCRIPT\"}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("DATA_STORE_BACKEND_UNAVAILABLE");
    assertThat(tenantIdBySlugOrNull(slug)).isNull();
  }

  private long storeRowCount(long tenantId) {
    return dsl.fetchOne("select count(*) from tenant_data_store where tenant_id = ?", tenantId)
        .get(0, Long.class);
  }

  /** dataStore JSON 조각을 덧붙여 생성 요청을 보낸다. */
  private MockHttpServletResponse createTenantWithBody(String slug, String extraJsonMember)
      throws Exception {
    return mockMvc
        .perform(
            authed(post("/api/platform/tenants"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"%s\",\"name\":\"%s\",\"ownerUserId\":%d,%s}"
                        .formatted(slug, slug, ownerUserId, extraJsonMember)))
        .andReturn()
        .getResponse();
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

  /** tenant NULL 감사 행 수 — tenant_id=1 로 샌 행은 세지 않으므로 컨텍스트 누수를 잡는다. */
  private Long auditCount(String action, long tenantId) {
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
