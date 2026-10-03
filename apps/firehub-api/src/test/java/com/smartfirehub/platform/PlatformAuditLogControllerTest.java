package com.smartfirehub.platform;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.audit.time.AuditTimes;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 운영자 콘솔 플랫폼 감사 로그 조회(WD-4).
 *
 * <p>공유 Testcontainers DB 에는 다른 테스트가 남긴 NULL 테넌트 LOGIN 행이 있다 — 전역 개수를 단언하지 않고 매 테스트 고유 마커({@code
 * actor} 필터 = username)로 좁힌다. 픽스처 행은 {@code action_time} 을 명시한다(같은 트랜잭션은 NOW() 가 같다).
 *
 * <p>TenantContext 를 비우는 이유는 {@link PlatformAccountControllerTest} 와 같다 — 운영 조건(GUC 없음)을 재현한다.
 */
@AutoConfigureMockMvc
class PlatformAuditLogControllerTest extends IntegrationTestBase {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private AuditLogRepository auditLogRepository;

  private String marker;
  private long tenantId;
  private long operator;
  private String operatorToken;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    marker = "wd4audit" + System.nanoTime();
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, marker);
    provisioningService.provisionDefaults(tenantId);
    operator = TenantRlsTestSupport.insertUserWithPassword(dsl, marker + "-ops", "{noop}x");
    users.add(operator);
    TenantRlsTestSupport.grantPlatformSuperAdmin(dsl, operator);
    operatorToken = jwtTokenProvider.generatePlatformAccessToken(operator, marker + "-ops");
    TenantContext.clear();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    // TestUsers.cleanup 이 user_id 기준으로 NULL 테넌트·테넌트 T 의 audit_log 를 함께 지운다.
    users.forEach(
        id -> steps.add(() -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, id, tenantId)));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  /** KST 벽시계 시각을 저장 TZ 벽시계로 — 손 픽스처를 실제 저장 규칙과 같은 값으로 심는다(WD-11). */
  private static LocalDateTime kstToStorage(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST)
        .withZoneSameInstant(AuditTimes.storageZone())
        .toLocalDateTime();
  }

  /** 감사 행 픽스처. tenant=null 이면 NULL 테넌트(플랫폼), 아니면 그 테넌트 GUC 안에서 넣어 DEFAULT 가 채운다. */
  private void insertAudit(Long tenant, String action, String targetUsername, LocalDateTime at) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenant,
        () ->
            dsl.execute(
                "insert into audit_log (user_id, username, action_type, resource, resource_id,"
                    + " description, action_time, result, metadata)"
                    + " values (?, ?, ?, 'user', '999', 'fixture', ?, 'SUCCESS',"
                    + " jsonb_build_object('targetUsername', ?::text))",
                operator,
                marker + "-ops",
                action,
                at,
                targetUsername));
  }

  private org.springframework.test.web.servlet.ResultActions list(String... params)
      throws Exception {
    var req = get("/api/platform/audit-logs").header("Authorization", "Bearer " + operatorToken);
    req.param("actor", marker);
    for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
    return mockMvc.perform(req);
  }

  @Test
  void platformAuditLogs_excludeTenantRows() throws Exception {
    insertAudit(
        null, "ACCOUNT_DEACTIVATE", "kim@example.com", LocalDateTime.of(2026, 9, 30, 10, 0));
    insertAudit(
        tenantId,
        "ACCOUNT_DEACTIVATE",
        "tenant-row@example.com",
        LocalDateTime.of(2026, 9, 30, 11, 0));

    list()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].actionType").value("ACCOUNT_DEACTIVATE"))
        .andExpect(jsonPath("$.content[0].metadata.targetUsername").value("kim@example.com"))
        .andExpect(
            jsonPath(
                "$.content[*].metadata.targetUsername",
                Matchers.not(Matchers.hasItem("tenant-row@example.com"))));
  }

  /**
   * Review Focus 1 — GUC 누수 방어. 테넌트 T 의 GUC 가 남은 트랜잭션에서 findPlatform 을 부르면 RLS 는 T 행만, 쿼리의
   * tenant_id IS NULL 은 NULL 행만 고르므로 0 이어야 한다. 위 HTTP 테스트는 GUC 가 없어 RLS 만으로도 초록이 되므로 이 테스트가 쿼리 조건의
   * 유일한 증거다(변이 확인: isNull() 을 지우면 1 이 된다).
   */
  @Test
  void findPlatform_withLeakedTenantGuc_returnsZero() {
    insertAudit(
        null, "ACCOUNT_DEACTIVATE", "kim@example.com", LocalDateTime.of(2026, 9, 30, 10, 0));
    insertAudit(
        tenantId,
        "ACCOUNT_DEACTIVATE",
        "tenant-row@example.com",
        LocalDateTime.of(2026, 9, 30, 11, 0));

    Long leaked =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            tenantId,
            () ->
                auditLogRepository
                    .findPlatform(marker, null, null, null, null, 0, 20)
                    .totalElements());
    assertThat(leaked).isZero();
  }

  @Test
  void realDeactivation_appearsWithOperatorAsActor() throws Exception {
    String username = marker + "-target@example.com";
    long target =
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
    TenantContext.clear();
    mockMvc
        .perform(
            post("/api/platform/accounts/{id}/deactivate", target)
                .header("Authorization", "Bearer " + operatorToken))
        .andExpect(status().isNoContent());

    list("target", username, "actionType", "ACCOUNT_DEACTIVATE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].username").value(marker + "-ops"))
        .andExpect(jsonPath("$.content[0].resourceId").value(String.valueOf(target)));

    // WD-11: 실제 기록 행을 같은 순간 창의 서로 다른 오프셋 표기로 찾는다(오프셋을 버리면 한쪽이 0행)
    Instant now = Instant.now();
    for (ZoneOffset off : List.of(ZoneOffset.ofHours(9), ZoneOffset.ofHours(-5))) {
      list(
              "target", username,
              "from", now.minus(2, MINUTES).atOffset(off).toString(),
              "to", now.plus(2, MINUTES).atOffset(off).toString())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1));
    }
  }

  /** KST 운영자의 하루(09-29~09-30) = [09-28T15:00Z, 09-30T15:00Z). admin 이 이 두 순간을 보낸다(WD-11). */
  @Test
  void dateRange_isOperatorLocalDay_givenAsInstants() throws Exception {
    insertAudit(null, "ACCOUNT_REACTIVATE", "in@example.com", kstToStorage(2026, 9, 30, 23, 30));
    insertAudit(null, "ACCOUNT_REACTIVATE", "out@example.com", kstToStorage(2026, 10, 1, 0, 30));
    insertAudit(
        null, "ACCOUNT_REACTIVATE", "before@example.com", kstToStorage(2026, 9, 28, 23, 59));

    String expectedTime =
        ZonedDateTime.of(2026, 9, 30, 23, 30, 0, 0, KST)
            .withZoneSameInstant(AuditTimes.storageZone())
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    list("from", "2026-09-28T15:00:00.000Z", "to", "2026-09-30T15:00:00.000Z")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].metadata.targetUsername").value("in@example.com"))
        // 응답은 저장 TZ 오프셋을 붙인다 — 같은 순간(KST 23:30)이어야 한다
        .andExpect(jsonPath("$.content[0].actionTime").value(expectedTime));
  }

  @Test
  void filters_actionAndTarget_andOrderNewestFirst() throws Exception {
    insertAudit(null, "ACCOUNT_DEACTIVATE", "kim@example.com", LocalDateTime.of(2026, 9, 30, 9, 0));
    insertAudit(
        null, "ACCOUNT_REACTIVATE", "kim@example.com", LocalDateTime.of(2026, 9, 30, 10, 0));
    insertAudit(
        null, "ACCOUNT_DEACTIVATE", "lee@example.com", LocalDateTime.of(2026, 9, 30, 11, 0));

    list("target", "KIM@")
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].actionType").value("ACCOUNT_REACTIVATE"))
        .andExpect(jsonPath("$.content[1].actionType").value("ACCOUNT_DEACTIVATE"));
    list("actionType", "ACCOUNT_DEACTIVATE").andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void pagination_pageAndSize() throws Exception {
    for (int i = 0; i < 3; i++) {
      insertAudit(
          null,
          "ACCOUNT_DEACTIVATE",
          "p" + i + "@example.com",
          LocalDateTime.of(2026, 9, 30, 10, i));
    }
    list("page", "1", "size", "2")
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].metadata.targetUsername").value("p0@example.com"));
  }

  @Test
  void invalidParams_rejected400() throws Exception {
    list("size", "0").andExpect(status().isBadRequest());
    list("size", "101").andExpect(status().isBadRequest());
    list("page", "-1").andExpect(status().isBadRequest());
    list("from", "2026-10-01T15:00:00Z", "to", "2026-09-30T15:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("시작일이 종료일보다 늦습니다"));
    // 구 계약(날짜만)·오프셋 없는 값은 거부 — 저장 TZ 를 추측하지 않는다
    list("from", "2026-09-30").andExpect(status().isBadRequest());
    list("from", "2026-09-30T00:00:00").andExpect(status().isBadRequest());
    list("from", "2026-13-01T00:00:00Z").andExpect(status().isBadRequest());
  }

  @Test
  void tenantToken_forbidden_andNoToken_unauthorized() throws Exception {
    String tenantToken = jwtTokenProvider.generateAccessToken(operator, marker + "-ops", tenantId);
    mockMvc
        .perform(get("/api/platform/audit-logs").header("Authorization", "Bearer " + tenantToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/platform/audit-logs")).andExpect(status().isUnauthorized());
  }
}
