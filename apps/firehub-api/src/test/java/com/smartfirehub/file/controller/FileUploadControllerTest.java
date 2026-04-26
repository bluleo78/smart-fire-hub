package com.smartfirehub.file.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.file.dto.FileUploadResponse;
import com.smartfirehub.file.service.FileUploadService;
import com.smartfirehub.file.service.FileUploadService.FileContentResult;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** FileUploadController WebMvcTest — 파일 업로드/조회 엔드포인트 인증/권한 검증 */
@SuppressWarnings("null")
@WebMvcTest(FileUploadController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class FileUploadControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileUploadService fileUploadService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ai:write"));
  }

  private FileUploadResponse sampleResponse() {
    return new FileUploadResponse(1L, "test.csv", "text/csv", 1024L, "DATA", Instant.now());
  }

  /** POST /files — 파일 업로드 성공 시 200 반환 */
  @Test
  void uploadFiles_withPermission_returnsOk() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("files", "test.csv", "text/csv", "col1,col2\n1,2".getBytes());

    when(fileUploadService.uploadFiles(anyList(), anyLong())).thenReturn(List.of(sampleResponse()));

    mockMvc
        .perform(multipart("/api/v1/files").file(file).header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].originalName").value("test.csv"));
  }

  /** POST /files — 인증 없으면 401 */
  @Test
  void uploadFiles_withoutAuth_returnsUnauthorized() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("files", "test.csv", "text/csv", "data".getBytes());
    mockMvc.perform(multipart("/api/v1/files").file(file)).andExpect(status().isUnauthorized());
  }

  /** GET /files/{id} — 파일 정보 조회 성공 */
  @Test
  void getFileInfo_withPermission_returnsOk() throws Exception {
    when(fileUploadService.getFileInfo(anyLong(), anyLong())).thenReturn(sampleResponse());

    mockMvc
        .perform(get("/api/v1/files/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
  }

  /** GET /files/{id} — 인증 없으면 401 */
  @Test
  void getFileInfo_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/files/1")).andExpect(status().isUnauthorized());
  }

  /** GET /files/{id}/content — 파일 콘텐츠 다운로드 성공 */
  @Test
  void getFileContent_withAuth_returnsOk() throws Exception {
    FileContentResult result =
        new FileContentResult("col1,col2\n1,2".getBytes(), "text/csv", "test.csv");
    when(fileUploadService.getFileContent(anyLong(), anyLong())).thenReturn(result);

    mockMvc
        .perform(get("/api/v1/files/1/content").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));
  }
}
