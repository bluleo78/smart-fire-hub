package com.smartfirehub.dataimport.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** DataImportController WebMvcTest — 파일 임포트 미리보기/검증/임포트/이력 조회 엔드포인트 검증 */
@SuppressWarnings("null")
@WebMvcTest(DataImportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DataImportControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DataImportService importService;
  @MockitoBean private UserRepository userRepository;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("data:import", "dataset:read"));
    // UserRepository mock: userId 1 → name "testuser"
    when(userRepository.findById(1L))
        .thenReturn(
            Optional.of(
                new UserResponse(
                    1L, "testuser", "test@test.com", "테스트유저", true, LocalDateTime.now())));
  }

  private MockMultipartFile csvFile() {
    return new MockMultipartFile("file", "data.csv", "text/csv", "col1,col2\n1,2".getBytes());
  }

  /** GET /datasets/{id}/imports — 인증 성공 시 임포트 이력 반환 */
  @Test
  void getImports_withPermission_returnsOk() throws Exception {
    when(importService.getImportsByDatasetId(1L)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/datasets/1/imports").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  /** GET /datasets/{id}/imports — 인증 없으면 401 */
  @Test
  void getImports_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/datasets/1/imports")).andExpect(status().isUnauthorized());
  }

  /** POST /datasets/{id}/imports/preview — 파일 미리보기 성공 */
  @Test
  void previewImport_withPermission_returnsOk() throws Exception {
    ImportPreviewResponse preview = new ImportPreviewResponse(List.of(), List.of(), List.of(), 10);
    when(importService.previewImport(anyLong(), any(), any())).thenReturn(preview);

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(csvFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }
}
