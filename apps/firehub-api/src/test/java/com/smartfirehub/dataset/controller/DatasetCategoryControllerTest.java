package com.smartfirehub.dataset.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CategoryRequest;
import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.service.DatasetCategoryService;
import com.smartfirehub.global.config.SecurityConfig;
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

/** DatasetCategoryController WebMvcTest — 카테고리 CRUD 엔드포인트 검증 */
@SuppressWarnings("null")
@WebMvcTest(DatasetCategoryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DatasetCategoryControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DatasetCategoryService categoryService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "dataset:write", "dataset:delete"));
  }

  private CategoryResponse sampleCategory() {
    return new CategoryResponse(1L, "공간데이터", "공간 관련 데이터셋");
  }

  /** GET /dataset-categories — 인증 성공 시 카테고리 목록 반환 */
  @Test
  void getAllCategories_withPermission_returnsOk() throws Exception {
    when(categoryService.getAllCategories()).thenReturn(List.of(sampleCategory()));

    mockMvc
        .perform(get("/api/v1/dataset-categories").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].name").value("공간데이터"));
  }

  /** GET /dataset-categories — 인증 없으면 401 */
  @Test
  void getAllCategories_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/dataset-categories")).andExpect(status().isUnauthorized());
  }

  /** POST /dataset-categories — 카테고리 생성 성공 시 201 반환 */
  @Test
  void createCategory_withPermission_returnsCreated() throws Exception {
    CategoryRequest request = new CategoryRequest("공간데이터", "공간 관련 데이터셋");
    when(categoryService.createCategory(anyString(), anyString())).thenReturn(sampleCategory());

    mockMvc
        .perform(
            post("/api/v1/dataset-categories")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1));
  }

  /** PUT /dataset-categories/{id} — 카테고리 수정 성공 시 204 반환 */
  @Test
  void updateCategory_withPermission_returnsNoContent() throws Exception {
    CategoryRequest request = new CategoryRequest("수정된이름", "수정 설명");

    mockMvc
        .perform(
            put("/api/v1/dataset-categories/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());
  }

  /** DELETE /dataset-categories/{id} — 카테고리 삭제 성공 시 204 반환 */
  @Test
  void deleteCategory_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/dataset-categories/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }
}
