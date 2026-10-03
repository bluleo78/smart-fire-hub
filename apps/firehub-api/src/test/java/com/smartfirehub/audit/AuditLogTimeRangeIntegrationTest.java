package com.smartfirehub.audit;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.audit.time.AuditTimes;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 워크스페이스 감사 로그 기간 필터(WD-11) — <b>실제 기록 경로</b>로 넣은 행으로 경계를 고정한다.
 *
 * <p>손으로 action_time 을 박은 행은 "저장 TZ = 접속 JVM TZ(pgjdbc 세션 TimeZone)" 불변식을 검증하지 못한다 — 그래서 {@link
 * AuditLogService#log} 로 기록하고(action_time 은 DB DEFAULT NOW()), 같은 순간 창을 서로 14시간 떨어진 오프셋으로 보내 둘 다
 * 잡히는지 본다. 오프셋을 버리는 구현은 JVM TZ 와 무관하게 최소 한 표기에서 0행이 된다. 창은 ±2분 — Docker VM 과 호스트 시계 오차를 흡수한다(초 단위 창
 * 금지).
 */
@AutoConfigureMockMvc
class AuditLogTimeRangeIntegrationTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private AuditLogService auditLogService;
  @Autowired private ObjectMapper objectMapper;

  private String marker;
  private long tenantId;
  private long adminId;
  private String token;

  @BeforeEach
  void setUp() {
    marker = "wd11" + System.nanoTime();
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, marker);
    provisioningService.provisionDefaults(tenantId);
    String username = marker + "@example.com";
    adminId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "Password123",
                marker,
                tenantId)
            .id();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, adminId, tenantId, "ADMIN");
    token = jwtTokenProvider.generateAccessToken(adminId, username, tenantId);
    // 실제 기록 경로: 서비스 → 저장소 INSERT. action_time 은 DB DEFAULT NOW()(세션 TZ 벽시계)가 채운다.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenantId,
        () -> {
          auditLogService.log(
              adminId,
              username,
              "WD11_PROBE",
              "audit_probe",
              null,
              "경계 탐침",
              null,
              null,
              "SUCCESS",
              null,
              null);
        });
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, adminId, tenantId),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
  }

  private ResultActions list(String start, String end) throws Exception {
    var req =
        get("/api/v1/admin/audit-logs")
            .header("Authorization", "Bearer " + token)
            .param("search", marker)
            .param("actionType", "WD11_PROBE");
    if (start != null) req.param("startDate", start);
    if (end != null) req.param("endDate", end);
    return mockMvc.perform(req);
  }

  private static String at(Instant instant, ZoneOffset offset) {
    return instant.atOffset(offset).toString();
  }

  @Test
  void offsetBoundaries_areHonoredRegardlessOfOffset() throws Exception {
    Instant now = Instant.now();
    for (ZoneOffset off : List.of(ZoneOffset.ofHours(9), ZoneOffset.UTC, ZoneOffset.ofHours(-5))) {
      list(at(now.minus(2, MINUTES), off), at(now.plus(2, MINUTES), off))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1));
    }
    // 행 이후·이전 창은 0행 — "항상 1행" 인 공허한 통과를 막는다
    list(at(now.plus(10, MINUTES), ZoneOffset.ofHours(9)), null)
        .andExpect(jsonPath("$.totalElements").value(0));
    list(null, at(now.minus(10, MINUTES), ZoneOffset.ofHours(-5)))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void responseActionTime_carriesOffset_andIsTheRealInstant() throws Exception {
    String body =
        list(null, null)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String actionTime = objectMapper.readTree(body).at("/content/0/actionTime").asText();
    // 오프셋이 없으면 OffsetDateTime.parse 가 예외 — 직렬화기 누락을 잡는다
    OffsetDateTime parsed = OffsetDateTime.parse(actionTime);
    // 불변식 감시: 저장 TZ 가정이 틀리면 수 시간 어긋난다
    assertThat(Duration.between(parsed.toInstant(), Instant.now()).abs())
        .isLessThan(Duration.ofMinutes(2));
  }

  @Test
  void offsetlessBoundary_keepsStorageWallClock_forAiAgent() throws Exception {
    LocalDateTime nowStorage = LocalDateTime.now(AuditTimes.storageZone());
    list(nowStorage.minusMinutes(2).toString(), nowStorage.plusMinutes(2).toString())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void malformedBoundary_is400() throws Exception {
    list("2026-13-01T00:00:00Z", null).andExpect(status().isBadRequest());
  }
}
