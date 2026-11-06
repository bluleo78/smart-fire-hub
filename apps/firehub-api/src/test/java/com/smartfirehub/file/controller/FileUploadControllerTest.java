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
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** FileUploadController WebMvcTest — 파일 업로드/조회 엔드포인트 인증/권한 검증 */
@SuppressWarnings("null")
@WebMvcTest(FileUploadController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class FileUploadControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FileUploadService fileUploadService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
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

  /** GET /files/{id}/content — 파일 콘텐츠 스트리밍 다운로드 성공 (OOM 방지: Resource 반환) */
  @Test
  void getFileContent_withAuth_returnsOk() throws Exception {
    byte[] data = "col1,col2\n1,2".getBytes();
    // ByteArrayResource는 테스트용 mock; 실제 서비스는 FileSystemResource를 사용한다
    FileContentResult result =
        new FileContentResult(new ByteArrayResource(data), "text/csv", "test.csv", data.length);
    when(fileUploadService.getFileContent(anyLong(), anyLong())).thenReturn(result);

    mockMvc
        .perform(get("/api/v1/files/1/content").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));
  }

  /** POST /files — part 이름이 files 가 아니면(필수 part 누락) 500 이 아니라 400 과 한국어 안내 (WD-521) */
  @Test
  void uploadFiles_withoutFilesPart_returnsBadRequest() throws Exception {
    MockMultipartFile wrong =
        new MockMultipartFile("foo", "x.html", "text/html", "<p>x</p>".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/files").file(wrong).header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.files").value("'files' 파일이 필요합니다."));
  }

  /** POST /files — multipart 본문을 해석하지 못하면 500 이 아니라 400 (WD-521) */
  @Test
  void uploadFiles_unreadableMultipart_returnsBadRequest() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("files", "test.csv", "text/csv", "a".getBytes());
    when(fileUploadService.uploadFiles(anyList(), anyLong()))
        .thenThrow(new org.springframework.web.multipart.MultipartException("no boundary"));

    mockMvc
        .perform(multipart("/api/v1/files").file(file).header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("파일 업로드 요청 본문을 해석할 수 없습니다."));
  }

  /** POST /files — 허용하지 않는 형식은 400 과 한국어 문구 (WD-521, 예전 「Unsupported file type: …」) */
  @Test
  void uploadFiles_unsupportedType_returnsKoreanMessage() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("files", "x.html", "text/html", "<p>x</p>".getBytes());
    when(fileUploadService.uploadFiles(anyList(), anyLong()))
        .thenThrow(
            new com.smartfirehub.file.exception.UnsupportedUploadFileTypeException("text/html"));

    mockMvc
        .perform(multipart("/api/v1/files").file(file).header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("지원하지 않는 파일 형식입니다: text/html"));
  }
}
