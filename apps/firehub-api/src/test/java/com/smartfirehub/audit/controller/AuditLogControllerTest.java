package com.smartfirehub.audit.controller;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.audit.dto.AuditLogResponse;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.GlobalExceptionHandler;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.validation.SearchQueryLimits;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(AuditLogController.class)
// WD-1111: 파라미터 검증 실패가 errors 맵 JSON 400 으로 번역되는지 보려고 전역 예외 처리기를 함께 올린다.
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class AuditLogControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AuditLogService auditLogService;

  /** 컨트롤러가 요청 사용자의 열람 자격을 서비스로 넘기는지(WD-289) 보려고 고정 자격을 돌려준다. */
  @MockitoBean private ClearanceResolver clearanceResolver;

  private static final Clearance VIEWER = new Clearance(1L, 1L, 1, Set.of(), false, Set.of());

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
    when(clearanceResolver.current()).thenReturn(VIEWER);
  }

  @Test
  void getAuditLogs_withPermission_returnsList() throws Exception {
    mockAuthentication("audit:read");
    AuditLogResponse log =
        new AuditLogResponse(
            1L,
            1L,
            "admin",
            "CREATE",
            "dataset",
            "5",
            "데이터셋 생성",
            LocalDateTime.of(2026, 2, 15, 10, 0, 0),
            "127.0.0.1",
            "Mozilla/5.0",
            "SUCCESS",
            null,
            null);
    PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(log), 0, 20, 1, 1);
    when(auditLogService.getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 20))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].username").value("admin"))
        .andExpect(jsonPath("$.content[0].actionType").value("CREATE"))
        .andExpect(jsonPath("$.content[0].resource").value("dataset"))
        .andExpect(jsonPath("$.content[0].result").value("SUCCESS"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  /**
   * WD-293: page·size 는 다른 목록과 같이 page ≥ 0, 1 ≤ size ≤ 100 으로 보정해 서비스에 넘긴다 — 상한이 없으면 감사 로그 전체를 한
   * 응답으로 돌려주고, size=0 은 totalPages 가 int 최댓값, 음수는 엉뚱한 「저장할 수 없는 값」 400 이었다.
   */
  @Test
  void getAuditLogs_clampsPageAndSize() throws Exception {
    mockAuthentication("audit:read");
    PageResponse<AuditLogResponse> empty = new PageResponse<>(List.of(), 0, 100, 0, 0);
    when(auditLogService.getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 100))
        .thenReturn(empty);
    when(auditLogService.getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 1))
        .thenReturn(new PageResponse<>(List.of(), 0, 1, 0, 0));

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .param("size", "100000")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .param("page", "-1")
                .param("size", "0")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(1));
    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .param("size", "-1")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    verify(auditLogService).getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 100);
    verify(auditLogService, times(2))
        .getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 1);
  }

  @Test
  void getAuditLogs_withSearchFilter_passesParams() throws Exception {
    mockAuthentication("audit:read");
    PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
    when(auditLogService.getAuditLogs(
            VIEWER, "admin", null, "CREATE", "dataset", "SUCCESS", null, null, 0, 20))
        .thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("search", "admin")
                .param("actionType", "CREATE")
                .param("resource", "dataset")
                .param("result", "SUCCESS"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void getAuditLogs_withPagination_passesPageParams() throws Exception {
    mockAuthentication("audit:read");
    PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(), 2, 10, 50, 5);
    when(auditLogService.getAuditLogs(VIEWER, null, null, null, null, null, null, null, 2, 10))
        .thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("page", "2")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.totalElements").value(50))
        .andExpect(jsonPath("$.totalPages").value(5));
  }

  @Test
  void getAuditLogs_withMetadata_returnsRawJson() throws Exception {
    mockAuthentication("audit:read");
    AuditLogResponse log =
        new AuditLogResponse(
            1L,
            1L,
            "admin",
            "IMPORT",
            "dataset",
            "3",
            "CSV 임포트",
            LocalDateTime.of(2026, 2, 15, 10, 0, 0),
            "127.0.0.1",
            null,
            "SUCCESS",
            null,
            "{\"rows\":100}");
    PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(log), 0, 20, 1, 1);
    when(auditLogService.getAuditLogs(VIEWER, null, null, null, null, null, null, null, 0, 20))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].metadata.rows").value(100));
  }

  /** userId 쿼리 파라미터 (#89) — 사용자 ID로 정확 일치 필터링이 서비스에 전달되는지 검증. */
  @Test
  void getAuditLogs_withUserIdFilter_passesUserIdParam() throws Exception {
    mockAuthentication("audit:read");
    PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
    when(auditLogService.getAuditLogs(VIEWER, null, 42L, null, null, null, null, null, 0, 20))
        .thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("userId", "42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  /** WD-1111: 상한(1,000자) 딱 그 길이의 검색어는 통과해 서비스에 그대로 전달된다. */
  @Test
  void searchAtMaxLength_returns200() throws Exception {
    mockAuthentication("audit:read");
    String search = "가".repeat(SearchQueryLimits.MAX_QUERY_LENGTH);
    when(auditLogService.getAuditLogs(VIEWER, search, null, null, null, null, null, null, 0, 20))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("search", search))
        .andExpect(status().isOk());
    verify(auditLogService).getAuditLogs(VIEWER, search, null, null, null, null, null, null, 0, 20);
  }

  /** WD-1111: 1,001자 검색어는 서비스(DB) 까지 가지 않고 필드 오류 JSON 400 으로 거절된다. */
  @Test
  void searchOverMaxLength_returns400WithFieldError() throws Exception {
    mockAuthentication("audit:read");

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("search", "a".repeat(SearchQueryLimits.MAX_QUERY_LENGTH + 1)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.errors.search").value(SearchQueryLimits.QUERY_TOO_LONG));
    verifyNoInteractions(auditLogService);
  }

  /** WD-1111: 빈 검색어는 기존대로 통과한다(상한 검증이 빈 값을 막지 않는다). */
  @Test
  void blankSearch_stillOk() throws Exception {
    mockAuthentication("audit:read");
    when(auditLogService.getAuditLogs(VIEWER, "", null, null, null, null, null, null, 0, 20))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer valid-token")
                .param("search", ""))
        .andExpect(status().isOk());
  }

  @Test
  void getAuditLogs_unauthenticated_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());
  }

  @Test
  void getAuditLogs_withoutPermission_returnsForbidden() throws Exception {
    mockAuthentication("user:read");

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }
}
