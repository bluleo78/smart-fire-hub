package com.smartfirehub.dataset.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchService;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DatasetControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DatasetService datasetService;

  @MockitoBean private DatasetDataService datasetDataService;

  @MockitoBean private DatasetFavoriteService datasetFavoriteService;

  @MockitoBean private DatasetTagService datasetTagService;

  @MockitoBean private ApiImportService apiImportService;

  @MockitoBean private DatasetSearchService datasetSearchService;

  @MockitoBean private DatasetEmbeddingBackfillService datasetEmbeddingBackfillService;

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

  @Test
  void getDatasets_withPermission_returnsPageResponse() throws Exception {
    CategoryResponse category = new CategoryResponse(1L, "Sales", "Sales data");
    DatasetResponse dataset =
        new DatasetResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            category,
            "TABLE", "SOURCE",
            LocalDateTime.now(),
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            null);
    PageResponse<DatasetResponse> page = new PageResponse<>(List.of(dataset), 0, 20, 1, 1);

    when(datasetService.getDatasets(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyBoolean()))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/datasets").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Orders"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void createDataset_withPermission_returnsCreated() throws Exception {
    CreateDatasetRequest request =
        new CreateDatasetRequest("Orders", "orders", "Order data", 1L, "TABLE", "SOURCE", List.of(), null);
    DatasetDetailResponse detail =
        new DatasetDetailResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            new CategoryResponse(1L, "Sales", "Sales data"),
            "TABLE", "SOURCE",
            "testuser",
            List.of(),
            0L,
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser",
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            List.of(),
            null);

    when(datasetService.createDataset(any(CreateDatasetRequest.class), anyLong()))
        .thenReturn(detail);

    mockMvc
        .perform(
            post("/api/v1/datasets")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Orders"))
        .andExpect(jsonPath("$.tableName").value("orders"));
  }

  @Test
  void getDatasetById_withPermission_returnsDetail() throws Exception {
    DatasetDetailResponse detail =
        new DatasetDetailResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            new CategoryResponse(1L, "Sales", "Sales data"),
            "TABLE", "SOURCE",
            "testuser",
            List.of(),
            100L,
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser",
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            List.of(),
            null);

    when(datasetService.getDatasetById(1L, 1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/datasets/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.rowCount").value(100));
  }

  @Test
  void updateDataset_withPermission_returnsNoContent() throws Exception {
    UpdateDatasetRequest request =
        new UpdateDatasetRequest("Orders Updated", "Updated description", 1L);

    mockMvc
        .perform(
            put("/api/v1/datasets/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(datasetService).updateDataset(eq(1L), any(UpdateDatasetRequest.class), eq(1L));
  }

  @Test
  void deleteDataset_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/datasets/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(datasetService).deleteDataset(1L);
  }

  @Test
  void getDatasets_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/datasets")).andExpect(status().isUnauthorized());
  }

  @Test
  void searchDatasets_withPermission_returnsHits() throws Exception {
    // 검색 서비스는 스텁 — 컨트롤러가 요청 body 를 받아 결과 배열을 그대로 반환하는지 검증
    when(datasetSearchService.search(any()))
        .thenReturn(
            List.of(
                new DatasetSearchHit(42L, "화재 출동", "설명", "TABLE", "SOURCE", "fire", "안전", 0.9)));

    mockMvc
        .perform(
            post("/api/v1/datasets/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"화재\",\"mode\":\"HYBRID\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].datasetId").value(42))
        .andExpect(jsonPath("$[0].storageType").value("TABLE"));
  }

  @Test
  void backfillEmbeddings_withPermission_returnsAcceptedWithScheduledCount() throws Exception {
    // dataset:write 권한으로 백필 트리거 시 202 Accepted + {"scheduled": N} 반환, 서비스 호출 검증
    when(datasetEmbeddingBackfillService.backfillAll()).thenReturn(3);

    mockMvc
        .perform(
            post("/api/v1/datasets/embedding/backfill")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.scheduled").value(3));

    verify(datasetEmbeddingBackfillService).backfillAll();
  }

  @Test
  void getReferences_returnsAggregatedCounts() throws Exception {
    DatasetReferencesResponse response =
        new DatasetReferencesResponse(
            1L,
            List.of(new DatasetReferencesResponse.ReferenceItem(10L, "Pipeline A")),
            List.of(new DatasetReferencesResponse.ReferenceItem(20L, "Dashboard B")),
            List.of(),
            2);

    when(datasetService.getReferences(1L)).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/datasets/1/references").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetId").value(1))
        .andExpect(jsonPath("$.pipelines[0].id").value(10))
        .andExpect(jsonPath("$.pipelines[0].name").value("Pipeline A"))
        .andExpect(jsonPath("$.dashboards[0].id").value(20))
        .andExpect(jsonPath("$.dashboards[0].name").value("Dashboard B"))
        .andExpect(jsonPath("$.proactiveJobs").isArray())
        .andExpect(jsonPath("$.totalCount").value(2));
  }

  @Test
  void getReferences_nonexistentDataset_returns404() throws Exception {
    when(datasetService.getReferences(999L))
        .thenThrow(new DatasetNotFoundException("Dataset not found: 999"));

    mockMvc
        .perform(
            get("/api/v1/datasets/999/references").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound());
  }
}
