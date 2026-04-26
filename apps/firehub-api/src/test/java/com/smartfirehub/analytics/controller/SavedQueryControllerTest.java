package com.smartfirehub.analytics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.*;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** SavedQueryController WebMvc 슬라이스 테스트 — 인증/권한 체계 및 HTTP 응답 코드 검증. */
@SuppressWarnings("null")
@WebMvcTest(SavedQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SavedQueryControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SavedQueryService savedQueryService;
  @MockitoBean private AnalyticsQueryExecutionService executionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("analytics:read", "analytics:write"));
  }

  /** 저장 쿼리 단건 응답 샘플 생성 헬퍼. */
  private SavedQueryResponse sampleQuery() {
    return new SavedQueryResponse(
        1L,
        "테스트 쿼리",
        "설명",
        "SELECT 1",
        null,
        null,
        "reports",
        false,
        "홍길동",
        1L,
        LocalDateTime.now(),
        LocalDateTime.now(),
        0L);
  }

  /** 저장 쿼리 목록 항목 샘플 생성 헬퍼. */
  private SavedQueryListResponse sampleListItem() {
    return new SavedQueryListResponse(
        1L,
        "테스트 쿼리",
        "설명",
        "reports",
        null,
        null,
        false,
        "홍길동",
        LocalDateTime.now(),
        LocalDateTime.now(),
        0L);
  }

  /** 쿼리 실행 응답 샘플 생성 헬퍼. */
  private AnalyticsQueryResponse sampleQueryResult() {
    return new AnalyticsQueryResponse(
        "SELECT", List.of("col1"), List.of(Map.of("col1", "value")), 0, 10L, 1, false, null);
  }

  // ── GET /api/v1/analytics/queries ──────────────────────────────────────────

  @Test
  void listQueries_withPermission_returnsPage() throws Exception {
    PageResponse<SavedQueryListResponse> page =
        new PageResponse<>(List.of(sampleListItem()), 0, 20, 1L, 1);
    when(savedQueryService.list(any(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/analytics/queries").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void listQueries_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/queries")).andExpect(status().isUnauthorized());
  }

  // ── POST /api/v1/analytics/queries ─────────────────────────────────────────

  @Test
  void createQuery_withPermission_returnsCreated() throws Exception {
    CreateSavedQueryRequest request =
        new CreateSavedQueryRequest("테스트 쿼리", "설명", "SELECT 1", null, "reports", false);
    when(savedQueryService.create(any(CreateSavedQueryRequest.class), anyLong()))
        .thenReturn(sampleQuery());

    mockMvc
        .perform(
            post("/api/v1/analytics/queries")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("테스트 쿼리"));
  }

  @Test
  void createQuery_withoutAuth_returnsUnauthorized() throws Exception {
    CreateSavedQueryRequest request =
        new CreateSavedQueryRequest("쿼리", null, "SELECT 1", null, null, false);

    mockMvc
        .perform(
            post("/api/v1/analytics/queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized());
  }

  // ── GET /api/v1/analytics/queries/schema ───────────────────────────────────

  @Test
  void getSchema_withPermission_returnsSchema() throws Exception {
    SchemaInfoResponse schema =
        new SchemaInfoResponse(
            List.of(new SchemaInfoResponse.TableInfo("my_table", "My Dataset", 1L, List.of())));
    when(executionService.getSchemaInfo()).thenReturn(schema);

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/schema").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].tableName").value("my_table"));
  }

  // ── GET /api/v1/analytics/queries/folders ──────────────────────────────────

  @Test
  void getFolders_withPermission_returnsList() throws Exception {
    when(savedQueryService.getFolders(1L)).thenReturn(List.of("reports", "adhoc"));

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/folders").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("reports"))
        .andExpect(jsonPath("$[1]").value("adhoc"));
  }

  // ── POST /api/v1/analytics/queries/execute ─────────────────────────────────

  @Test
  void executeAdHoc_withPermission_returnsResult() throws Exception {
    AnalyticsQueryRequest request = new AnalyticsQueryRequest("SELECT 1", 100, true);
    when(executionService.execute(anyString(), anyInt(), anyBoolean()))
        .thenReturn(sampleQueryResult());

    mockMvc
        .perform(
            post("/api/v1/analytics/queries/execute")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.queryType").value("SELECT"))
        .andExpect(jsonPath("$.columns[0]").value("col1"));
  }

  // ── GET /api/v1/analytics/queries/{id} ─────────────────────────────────────

  @Test
  void getQuery_withPermission_returnsQuery() throws Exception {
    when(savedQueryService.getById(1L, 1L)).thenReturn(sampleQuery());

    mockMvc
        .perform(get("/api/v1/analytics/queries/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.sqlText").value("SELECT 1"));
  }

  // ── PUT /api/v1/analytics/queries/{id} ─────────────────────────────────────

  @Test
  void updateQuery_withPermission_returnsUpdated() throws Exception {
    UpdateSavedQueryRequest request =
        new UpdateSavedQueryRequest("수정된 쿼리", null, "SELECT 2", null, null, null);
    when(savedQueryService.update(anyLong(), any(UpdateSavedQueryRequest.class), anyLong()))
        .thenReturn(sampleQuery());

    mockMvc
        .perform(
            put("/api/v1/analytics/queries/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
  }

  // ── DELETE /api/v1/analytics/queries/{id} ──────────────────────────────────

  @Test
  void deleteQuery_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/analytics/queries/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(savedQueryService).delete(1L, 1L);
  }

  // ── POST /api/v1/analytics/queries/{id}/execute ────────────────────────────

  @Test
  void executeSavedQuery_withPermission_returnsResult() throws Exception {
    when(savedQueryService.executeById(anyLong(), anyInt(), anyBoolean(), anyLong()))
        .thenReturn(sampleQueryResult());

    mockMvc
        .perform(
            post("/api/v1/analytics/queries/1/execute")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalRows").value(1));
  }

  // ── POST /api/v1/analytics/queries/{id}/clone ──────────────────────────────

  @Test
  void cloneQuery_withPermission_returnsCreated() throws Exception {
    when(savedQueryService.clone(anyLong(), anyLong())).thenReturn(sampleQuery());

    mockMvc
        .perform(
            post("/api/v1/analytics/queries/1/clone").header("Authorization", "Bearer test-token"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1));
  }
}
