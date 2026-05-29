package com.smartfirehub.analytics.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
    // PR-1 Task 2: 컨트롤러가 datasetIds 파라미터를 받는 오버로드로 변경됨 → null 분기 stub.
    when(executionService.getSchemaInfo((List<Long>) isNull())).thenReturn(schema);

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/schema").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].tableName").value("my_table"));

    // 추가 — datasetIds 미지정 시 서비스의 List<Long> 오버로드에 null 위임 검증
    verify(executionService).getSchemaInfo((java.util.List<Long>) isNull());
  }

  // === GET /api/v1/analytics/queries/schema — datasetIds 필터 (PR-1 Task 2) ===

  /** 단일 datasetId — Spring 의 List<Long> 단일 값 바인딩 검증. */
  @Test
  void getSchema_singleDatasetId_passesListWithOneElement() throws Exception {
    when(executionService.getSchemaInfo(List.of(11L)))
        .thenReturn(new SchemaInfoResponse(List.of()));

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/schema?datasetIds=11")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());

    verify(executionService).getSchemaInfo(List.of(11L));
  }

  /** 콤마 구분 다중 datasetIds — 순서 보존 검증. */
  @Test
  void getSchema_multipleDatasetIds_passesListInOrder() throws Exception {
    when(executionService.getSchemaInfo(List.of(11L, 7L)))
        .thenReturn(new SchemaInfoResponse(List.of()));

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/schema?datasetIds=11,7")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());

    verify(executionService).getSchemaInfo(List.of(11L, 7L));
  }

  /**
   * GET /schema?datasetIds= (빈 문자열) — Spring 의 List<Long> 빈 값 바인딩 동작 fix.
   *
   * <p>PR-1 의 빈 배열 defensive 분기가 HTTP 경로에서 실제 트리거되는지 캡처 어서션으로 확인한다. Spring 6.x 의 {@code
   * ?datasetIds=} 처리 동작(빈 문자열 → null 또는 empty list)을 둘 다 허용해 버전 변동을 흡수한다. 즉, 빈 값은 결국 BC 분기(전체 반환)로
   * 들어가 Zod 가 차단하지 않은 HTTP 우회 경로를 검증한다.
   */
  @Test
  void getSchema_emptyDatasetIdsParam_behaviorDocumented() throws Exception {
    when(executionService.getSchemaInfo((List<Long>) any()))
        .thenReturn(new SchemaInfoResponse(List.of()));

    mockMvc
        .perform(
            get("/api/v1/analytics/queries/schema?datasetIds=")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());

    // 실제 바인딩 결과 캡처 — Spring 버전·PG 버전 차이를 흡수하기 위해 null 또는 empty list 둘 다 허용.
    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<List<Long>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(executionService).getSchemaInfo(captor.capture());
    List<Long> captured = captor.getValue();
    assertThat(captured == null || captured.isEmpty())
        .as("Spring @RequestParam List<Long> 빈 문자열 바인딩: null 또는 empty list")
        .isTrue();
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

  /**
   * #178 보안 회귀 방지: analytics:read 권한만으로 호출되는 저장 쿼리 실행 엔드포인트는 클라이언트가 readOnly=false 를 보내도 무시하고 항상
   * readOnly=true 로 강제해야 한다.
   */
  @Test
  void executeSavedQuery_clientReadOnlyFalse_isForcedToTrue() throws Exception {
    when(savedQueryService.executeById(anyLong(), anyInt(), anyBoolean(), anyLong()))
        .thenReturn(sampleQueryResult());
    AnalyticsQueryRequest request = new AnalyticsQueryRequest(null, 100, false);

    mockMvc
        .perform(
            post("/api/v1/analytics/queries/1/execute")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    verify(savedQueryService).executeById(1L, 100, true, 1L);
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
