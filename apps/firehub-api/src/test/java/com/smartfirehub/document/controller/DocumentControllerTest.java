package com.smartfirehub.document.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.document.service.DocumentIngestionService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
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

/** DocumentController WebMvcTest — 문서 업로드/목록/조회/삭제 엔드포인트와 권한 검증. */
@SuppressWarnings("null")
@WebMvcTest(DocumentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DocumentControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DocumentIngestionService ingestionService;
  @MockitoBean private DocumentFileRepository fileRepository;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    // JWT "test-token" → userId 1L, 권한은 문서 엔드포인트 전체를 커버하도록 부여한다.
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("data:import", "dataset:read", "dataset:write"));
  }

  private MockMultipartFile textFile() {
    return new MockMultipartFile(
        "file", "report.txt", "text/plain", "소방 점검 보고서. 화재 예방 점검 결과.".getBytes());
  }

  private DocumentFileResponse pendingResponse() {
    return new DocumentFileResponse(
        10L, 1L, "report.txt", "text/plain", 100L, "PENDING", null, null, null, 1L,
        LocalDateTime.now(), null);
  }

  /** POST /datasets/{id}/documents — 업로드 성공 시 202 + 초기 상태(PENDING) 반환. */
  @Test
  void upload_withPermission_returnsAcceptedWithPendingStatus() throws Exception {
    when(ingestionService.upload(eq(1L), any(), eq("report.txt"), eq("text/plain"), eq(1L)))
        .thenReturn(pendingResponse());

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/documents")
                .file(textFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.originalName").value("report.txt"));

    verify(ingestionService).upload(eq(1L), any(), eq("report.txt"), eq("text/plain"), eq(1L));
  }

  /** POST /datasets/{id}/documents — 인증 없으면 401. */
  @Test
  void upload_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/datasets/1/documents").file(textFile()))
        .andExpect(status().isUnauthorized());
  }

  /** GET /datasets/{id}/documents — 업로드한 문서가 목록에 포함되어 반환된다. */
  @Test
  void list_withPermission_returnsDocuments() throws Exception {
    when(fileRepository.findByDataset(1L)).thenReturn(List.of(pendingResponse()));

    mockMvc
        .perform(
            get("/api/v1/datasets/1/documents").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(10))
        .andExpect(jsonPath("$[0].originalName").value("report.txt"));
  }

  /** GET /datasets/{id}/documents/{documentId} — datasetId가 일치하면 200 + 본문. */
  @Test
  void get_whenExists_returnsOk() throws Exception {
    when(fileRepository.findById(10L)).thenReturn(Optional.of(pendingResponse()));

    mockMvc
        .perform(
            get("/api/v1/datasets/1/documents/10")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(10))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  /** GET /datasets/{id}/documents/{documentId} — 존재하지 않으면 404. */
  @Test
  void get_whenMissing_returnsNotFound() throws Exception {
    when(fileRepository.findById(99L)).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/datasets/1/documents/99")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound());
  }

  /** GET — 문서는 존재하나 경로의 datasetId와 소속이 다르면 404(교차 데이터셋 접근 차단, C1). */
  @Test
  void get_whenDatasetMismatch_returnsNotFound() throws Exception {
    // 문서 datasetId=1 이지만 경로는 datasets/2 → 소속 불일치로 404 여야 한다.
    when(fileRepository.findById(10L)).thenReturn(Optional.of(pendingResponse()));

    mockMvc
        .perform(
            get("/api/v1/datasets/2/documents/10")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound());
  }

  /** DELETE /datasets/{id}/documents/{documentId} — 소속 일치 시 삭제 위임 후 204. */
  @Test
  void delete_withPermission_returnsNoContent() throws Exception {
    when(fileRepository.findById(10L)).thenReturn(Optional.of(pendingResponse()));

    mockMvc
        .perform(
            delete("/api/v1/datasets/1/documents/10")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(ingestionService).deleteDocument(10L);
  }

  /** DELETE — 경로의 datasetId와 소속이 다르면 404 + 삭제 미수행(교차 데이터셋 삭제 차단, C1). */
  @Test
  void delete_whenDatasetMismatch_returnsNotFound() throws Exception {
    // 문서 datasetId=1 이지만 경로는 datasets/2 → 삭제하지 않고 404.
    when(fileRepository.findById(10L)).thenReturn(Optional.of(pendingResponse()));

    mockMvc
        .perform(
            delete("/api/v1/datasets/2/documents/10")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound());

    verify(ingestionService, never()).deleteDocument(anyLong());
  }

  /** POST /datasets/{id}/documents — data:import 권한이 없으면 403(권한 없는 호출 거부, I3). */
  @Test
  void upload_withoutPermission_returnsForbidden() throws Exception {
    // 권한 집합을 비워 data:import 누락 상태를 만든다.
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/documents")
                .file(textFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isForbidden());
  }
}
