package com.smartfirehub.dataimport.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ImportMode;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
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
import org.mockito.ArgumentCaptor;
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

  /**
   * POST /datasets/{id}/imports — X-Forwarded-For 헤더가 있으면 첫 번째 값을 ipAddress로 service에 전달한다 (이슈
   * #147). 리버스 프록시 환경에서 실제 클라이언트 IP가 감사 로그에 기록되어야 한다.
   */
  @Test
  void importFile_withXForwardedFor_passesClientIpToService() throws Exception {
    when(importService.importFile(
            anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(new ImportStartResponse("job-1", "PENDING"));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .header("Authorization", "Bearer test-token")
                .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                .with(
                    req -> {
                      req.setRemoteAddr("10.0.0.1"); // 프록시 IP — XFF가 우선이어야 함
                      return req;
                    }))
        .andExpect(status().isCreated());

    ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
    verify(importService)
        .importFile(
            eq(1L),
            any(),
            any(),
            eq(1L),
            any(),
            ipCaptor.capture(),
            any(),
            any(),
            eq(ImportMode.APPEND));
    assertThat(ipCaptor.getValue()).isEqualTo("203.0.113.5");
  }

  /** POST /datasets/{id}/imports — X-Forwarded-For 헤더가 없으면 remoteAddr가 ipAddress로 전달된다 */
  @Test
  void importFile_withoutXForwardedFor_passesRemoteAddrToService() throws Exception {
    when(importService.importFile(
            anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(new ImportStartResponse("job-2", "PENDING"));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .header("Authorization", "Bearer test-token")
                .with(
                    req -> {
                      req.setRemoteAddr("198.51.100.42");
                      return req;
                    }))
        .andExpect(status().isCreated());

    ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
    verify(importService)
        .importFile(eq(1L), any(), any(), eq(1L), any(), ipCaptor.capture(), any(), any(), any());
    assertThat(ipCaptor.getValue()).isEqualTo("198.51.100.42");
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
