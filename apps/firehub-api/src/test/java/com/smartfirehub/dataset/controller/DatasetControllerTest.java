package com.smartfirehub.dataset.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
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
            "CUSTOM",
            LocalDateTime.now(),
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null);
    PageResponse<DatasetResponse> page = new PageResponse<>(List.of(dataset), 0, 20, 1, 1);

    when(datasetService.getDatasets(
            any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyBoolean()))
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
        new CreateDatasetRequest("Orders", "orders", "Order data", 1L, "CUSTOM", List.of());
    DatasetDetailResponse detail =
        new DatasetDetailResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            new CategoryResponse(1L, "Sales", "Sales data"),
            "CUSTOM",
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
            List.of());

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
            "CUSTOM",
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
            List.of());

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
  void getDatasets_withoutAuth_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/datasets")).andExpect(status().isForbidden());
  }
}
