package com.smartfirehub.dataset.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.service.ApiImportService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetFavoriteService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
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

/**
 * DatasetController 추가 엔드포인트 커버리지 보강 테스트.
 * 컬럼 관리, 데이터 조회/수정/삭제, 태그, 즐겨찾기, 상태 변경 등을 커버한다.
 */
@SuppressWarnings("null")
@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DatasetControllerExtendedTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DatasetService datasetService;
  @MockitoBean private DatasetDataService datasetDataService;
  @MockitoBean private DatasetFavoriteService datasetFavoriteService;
  @MockitoBean private DatasetTagService datasetTagService;
  @MockitoBean private ApiImportService apiImportService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(
            Set.of(
                "dataset:read",
                "dataset:write",
                "dataset:delete",
                "data:read",
                "data:import",
                "data:delete",
                "pipeline:write"));
  }

  private DatasetDetailResponse sampleDetail() {
    return new DatasetDetailResponse(
        1L, "DS", "ds_table", null, null, "CUSTOM", "user", List.of(), 0L,
        LocalDateTime.now(), LocalDateTime.now(), "user", false, List.of(),
        "ACTIVE", null, null, null, List.of(), null);
  }

  private DatasetColumnResponse sampleColumnResponse() {
    return new DatasetColumnResponse(10L, "new_col", "New Col", "TEXT", null, true, false, null, 1, false);
  }

  // ── 컬럼 관리 ────────────────────────────────────────────────────────────────

  @Test
  void addColumn_returnsCreated() throws Exception {
    AddColumnRequest req = new AddColumnRequest("new_col", "New Col", "TEXT", null, true, false, null);
    when(datasetService.addColumn(eq(1L), any())).thenReturn(sampleColumnResponse());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/columns")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.columnName").value("new_col"));
  }

  @Test
  void updateColumn_returnsNoContent() throws Exception {
    UpdateColumnRequest req = new UpdateColumnRequest(null, "New Display", null, null, null, null, null);
    doNothing().when(datasetService).updateColumn(anyLong(), anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/columns/10")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteColumn_returnsNoContent() throws Exception {
    doNothing().when(datasetService).deleteColumn(anyLong(), anyLong());

    mockMvc
        .perform(
            delete("/api/v1/datasets/1/columns/10")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void reorderColumns_returnsNoContent() throws Exception {
    ReorderColumnsRequest req = new ReorderColumnsRequest(List.of(2L, 1L, 3L));
    doNothing().when(datasetService).reorderColumns(anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/columns/reorder")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  // ── 데이터 조작 ───────────────────────────────────────────────────────────────

  @Test
  void getDatasetStats_returnsStatsList() throws Exception {
    ColumnStatsResponse stat = new ColumnStatsResponse("col1", "TEXT", 100L, 5L, 0.05, 10L, "a", "z", null, List.of(), false);
    when(datasetDataService.getDatasetStats(1L)).thenReturn(List.of(stat));

    mockMvc
        .perform(get("/api/v1/datasets/1/stats").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].columnName").value("col1"));
  }

  @Test
  void deleteDataRows_validIds_returnsOk() throws Exception {
    DataDeleteRequest req = new DataDeleteRequest(List.of(1L, 2L, 3L));
    when(datasetDataService.deleteDataRows(anyLong(), any())).thenReturn(new DataDeleteResponse(3));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/delete")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(3));
  }

  @Test
  void deleteDataRows_emptyIds_returnsBadRequest() throws Exception {
    DataDeleteRequest req = new DataDeleteRequest(List.of());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/delete")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void truncateData_returnsOk() throws Exception {
    when(datasetDataService.truncateDatasetData(1L)).thenReturn(new DataDeleteResponse(50));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/truncate")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(50));
  }

  @Test
  void getRowCount_returnsCount() throws Exception {
    when(datasetDataService.getRowCount(1L)).thenReturn(new RowCountResponse(42L));

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data/count").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(42));
  }

  @Test
  void getData_returnsDataQueryResponse() throws Exception {
    DataQueryResponse resp =
        new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getData_withNearbyFilter_passesFilter() throws Exception {
    DataQueryResponse resp =
        new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data")
                .param("spatialColumn", "location")
                .param("nearbyLon", "127.0")
                .param("nearbyLat", "37.5")
                .param("nearbyRadius", "1000")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getData_withBboxFilter_passesFilter() throws Exception {
    DataQueryResponse resp =
        new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data")
                .param("bboxMinLon", "126.0")
                .param("bboxMinLat", "37.0")
                .param("bboxMaxLon", "128.0")
                .param("bboxMaxLat", "38.0")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void replaceData_returnsCreated() throws Exception {
    BatchRowDataRequest req =
        new BatchRowDataRequest(List.of(Map.of("name", "Alice")));
    when(datasetDataService.replaceDatasetData(anyLong(), any()))
        .thenReturn(new BatchRowDataResponse(1));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/replace")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.insertedCount").value(1));
  }

  // ── 즐겨찾기 ─────────────────────────────────────────────────────────────────

  @Test
  void toggleFavorite_returnsOk() throws Exception {
    when(datasetFavoriteService.toggleFavorite(1L, 1L)).thenReturn(new FavoriteToggleResponse(true));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/favorite").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favorited").value(true));
  }

  // ── 태그 ──────────────────────────────────────────────────────────────────────

  @Test
  void getAllTags_returnsList() throws Exception {
    when(datasetTagService.getAllDistinctTags()).thenReturn(List.of("tag1", "tag2"));

    mockMvc
        .perform(get("/api/v1/datasets/tags").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("tag1"));
  }

  @Test
  void addTag_validName_returnsCreated() throws Exception {
    AddTagRequest req = new AddTagRequest("sales");
    doNothing().when(datasetTagService).addTag(anyLong(), anyString(), anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated());
  }

  @Test
  void addTag_invalidName_returnsBadRequest() throws Exception {
    AddTagRequest req = new AddTagRequest("invalid name with spaces!");

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addTag_blankName_returnsBadRequest() throws Exception {
    AddTagRequest req = new AddTagRequest("");

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addTag_duplicateTag_returnsConflict() throws Exception {
    AddTagRequest req = new AddTagRequest("sales");
    org.mockito.Mockito.doThrow(new IllegalStateException("Tag already exists"))
        .when(datasetTagService).addTag(anyLong(), anyString(), anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void deleteTag_returnsNoContent() throws Exception {
    doNothing().when(datasetTagService).deleteTag(anyLong(), anyString());

    mockMvc
        .perform(
            delete("/api/v1/datasets/1/tags/sales").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  // ── 상태 변경 ─────────────────────────────────────────────────────────────────

  @Test
  void updateStatus_returnsOk() throws Exception {
    UpdateStatusRequest req = new UpdateStatusRequest("ARCHIVED", "No longer needed");
    when(datasetService.updateStatus(anyLong(), any(), anyLong())).thenReturn(sampleDetail());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/status")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  // ── 설명 전파 ─────────────────────────────────────────────────────────────────

  @Test
  void propagateDescriptions_returnsNoContent() throws Exception {
    doNothing().when(datasetDataService).propagateDescriptions(anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/propagate-descriptions")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  // ── SQL 쿼리 ──────────────────────────────────────────────────────────────────

  @Test
  void executeQuery_returnsOk() throws Exception {
    SqlQueryRequest req = new SqlQueryRequest("SELECT 1", 100);
    SqlQueryResponse resp = new SqlQueryResponse("SELECT", List.of(), List.of(), 0, 0L, null);
    when(datasetDataService.executeQuery(anyLong(), any(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/query")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  @Test
  void getQueryHistory_returnsPageResponse() throws Exception {
    PageResponse<QueryHistoryResponse> page =
        new PageResponse<>(List.of(), 0, 20, 0, 0);
    when(datasetDataService.getQueryHistory(anyLong(), anyInt(), anyInt())).thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/queries").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  // ── 행 수동 입력 ──────────────────────────────────────────────────────────────

  @Test
  void addRow_returnsCreated() throws Exception {
    RowDataRequest req = new RowDataRequest(Map.of("name", "Alice"));
    RowDataResponse resp = new RowDataResponse(1L, Map.of("name", "Alice"), LocalDateTime.now());
    when(datasetDataService.addRow(anyLong(), any())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/rows")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1));
  }

  @Test
  void addRowsBatch_returnsCreated() throws Exception {
    BatchRowDataRequest req = new BatchRowDataRequest(List.of(Map.of("name", "Bob")));
    when(datasetDataService.addRowsBatch(anyLong(), any())).thenReturn(new BatchRowDataResponse(1));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/rows/batch")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.insertedCount").value(1));
  }

  @Test
  void updateRow_returnsNoContent() throws Exception {
    RowDataRequest req = new RowDataRequest(Map.of("name", "Carol"));
    doNothing().when(datasetDataService).updateRow(anyLong(), anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/data/rows/5")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void getRow_returnsRowData() throws Exception {
    RowDataResponse resp = new RowDataResponse(5L, Map.of("name", "Dave"), LocalDateTime.now());
    when(datasetDataService.getRow(anyLong(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data/rows/5").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  // ── API 임포트 / 클론 ──────────────────────────────────────────────────────────

  @Test
  void createApiImport_returnsCreated() throws Exception {
    ApiImportRequest req = new ApiImportRequest("api-pipeline", null, null, null, "APPEND", true, null);
    ApiImportResponse resp = new ApiImportResponse(10L, 20L, 30L);
    when(apiImportService.createApiImport(anyLong(), any(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/api-import")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pipelineId").value(10));
  }

  @Test
  void cloneDataset_returnsCreated() throws Exception {
    CloneDatasetRequest req = new CloneDatasetRequest("Clone DS", "clone_ds", null, false, false);
    when(datasetService.cloneDataset(anyLong(), any(), anyLong())).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/clone")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated());
  }
}
