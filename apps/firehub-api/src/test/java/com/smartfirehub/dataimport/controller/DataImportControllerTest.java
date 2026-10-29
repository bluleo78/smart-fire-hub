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
import com.smartfirehub.dataimport.exception.FileUnreadableException;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DataImportService importService;
  @MockitoBean private UserRepository userRepository;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
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

  /**
   * WD-253: 미리보기·사전 검증·가져오기가 모두 multipart 폼 필드 sheetName 을 ParseOptions 에 실어 서비스로 넘긴다 — 임포트 잡은 이
   * ParseOptions 를 그대로 받아 같은 시트를 읽는다. 빈 값은 「지정 안 함」(null)이다.
   */
  @Test
  void sheetName_formField_reachesParseOptions_onPreviewValidateAndImport() throws Exception {
    when(importService.previewImport(anyLong(), any(), any(), anyBoolean()))
        .thenReturn(
            new ImportPreviewResponse(List.of(), List.of(), List.of(), 0, "데이터", List.of()));
    when(importService.validateImport(anyLong(), any(), any(), any())).thenReturn(null);
    when(importService.importFile(
            anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(new ImportStartResponse("job-1", "PENDING"));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(csvFile())
                .param("sheetName", "숨김")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sheetName").value("데이터"));
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/validate")
                .file(csvFile())
                .param("mappings", "[]")
                .param("sheetName", "숨김")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .param("sheetName", "숨김")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isCreated());

    ArgumentCaptor<com.smartfirehub.dataimport.dto.ParseOptions> preview =
        ArgumentCaptor.forClass(com.smartfirehub.dataimport.dto.ParseOptions.class);
    verify(importService).previewImport(eq(1L), any(), preview.capture(), anyBoolean());
    assertThat(preview.getValue().sheetName()).isEqualTo("숨김");

    ArgumentCaptor<com.smartfirehub.dataimport.dto.ParseOptions> validate =
        ArgumentCaptor.forClass(com.smartfirehub.dataimport.dto.ParseOptions.class);
    verify(importService).validateImport(eq(1L), any(), any(), validate.capture());
    assertThat(validate.getValue().sheetName()).isEqualTo("숨김");

    ArgumentCaptor<com.smartfirehub.dataimport.dto.ParseOptions> imported =
        ArgumentCaptor.forClass(com.smartfirehub.dataimport.dto.ParseOptions.class);
    verify(importService)
        .importFile(eq(1L), any(), any(), eq(1L), any(), any(), any(), imported.capture(), any());
    assertThat(imported.getValue().sheetName()).isEqualTo("숨김");
  }

  /**
   * 미리보기에서 파서가 파일을 읽지 못하면 400 + 한국어 안내가 나가야 한다(WD-204). 예전엔 엑셀이 아닌 .xlsx 가 빈 200 으로 나가 화면이 빈 2단계로
   * 넘어갔고, 손상된 OOXML 은 POI 영문 메시지가 토스트에 보였다.
   */
  @Test
  void previewImport_unreadableFile_returns400Korean() throws Exception {
    when(importService.previewImport(anyLong(), any(), any(), anyBoolean()))
        .thenThrow(
            new FileUnreadableException(
                FileUnreadableException.EXCEL_UNREADABLE,
                new IllegalArgumentException("not a valid OOXML (Office Open XML) file")));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(csvFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(FileUnreadableException.EXCEL_UNREADABLE))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("OOXML"))));
  }

  /** WD-205: 헤더에 같은 이름의 열이 있으면 미리보기가 400 + 한국어 사유로 거절된다(기존 UnsupportedFileType 핸들러 경유). */
  @Test
  void previewImport_duplicateHeader_returns400WithKoreanMessage() throws Exception {
    String message = "파일에 같은 이름의 열이 있습니다: region (1·2번째 열). 열 이름을 고친 뒤 다시 올려 주세요.";
    when(importService.previewImport(anyLong(), any(), any(), anyBoolean()))
        .thenThrow(new InvalidFileHeaderException(message));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(csvFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  /** WD-205: 사전 검증도 같은 규칙으로 400 + 한국어 사유를 돌려준다. */
  @Test
  void validateImport_duplicateHeader_returns400WithKoreanMessage() throws Exception {
    String message = "파일에 같은 이름의 열이 있습니다: cnt (2·3번째 열). 열 이름을 고친 뒤 다시 올려 주세요.";
    when(importService.validateImport(anyLong(), any(), any(), any()))
        .thenThrow(new InvalidFileHeaderException(message));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/validate")
                .file(csvFile())
                .param("mappings", "[]")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  // ── WD-250: 입력 오류는 400 + 한국어(빈 본문·500·영어 문구 대신) ─────────────────────────

  /** 틀린 importMode — 예전에는 본문 없는 400. 허용 값을 나열한 한국어 문구로 답하고 서비스는 부르지 않는다. */
  @Test
  void importFile_invalidImportMode_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .param("importMode", "MERGE")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(DataImportController.INVALID_IMPORT_MODE_MESSAGE))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("APPEND, UPSERT, REPLACE")));
    verify(importService, org.mockito.Mockito.never())
        .importFile(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any());
  }

  /** importMode 는 대소문자를 가리지 않는다(기존 동작 유지). */
  @Test
  void importFile_lowercaseImportMode_isAccepted() throws Exception {
    when(importService.importFile(
            anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(new ImportStartResponse("job-1", "PENDING"));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .param("importMode", "replace")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isCreated());
    verify(importService)
        .importFile(
            anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), eq(ImportMode.REPLACE));
  }

  /** 깨진 mappings JSON — 예전에는 Jackson 예외가 500 으로 떨어졌다. */
  @Test
  void importFile_brokenMappingsJson_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports")
                .file(csvFile())
                .param("mappings", "{not json")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(DataImportController.INVALID_MAPPINGS_MESSAGE));
  }

  /** 사전 검증의 깨진 mappings(배열이 아닌 객체)도 같은 400. */
  @Test
  void validateImport_mappingsNotArray_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/validate")
                .file(csvFile())
                .param("mappings", "{\"fileColumn\":\"a\"}")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(DataImportController.INVALID_MAPPINGS_MESSAGE));
  }

  /** mappings 없이 사전 검증 — 예전에는 필수 파라미터 누락으로 500. 400 한국어로 거부하고 서비스는 부르지 않는다. */
  @Test
  void validateImport_withoutMappings_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/validate")
                .file(csvFile())
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(DataImportController.MISSING_MAPPINGS_MESSAGE));
    verify(importService, org.mockito.Mockito.never())
        .validateImport(anyLong(), any(), any(), any());
  }

  /** JSON null 도 매핑 없음으로 본다 — 같은 400. */
  @Test
  void validateImport_nullMappings_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/validate")
                .file(csvFile())
                .param("mappings", "null")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(DataImportController.MISSING_MAPPINGS_MESSAGE));
  }

  /** 허용하지 않는 인코딩 — 영어 「Unsupported encoding … Allowed: [순서가 매번 다른 집합]」 대신 고정 순서 한국어. */
  @Test
  void previewImport_unsupportedEncoding_returns400Korean() throws Exception {
    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(csvFile())
                .param("encoding", "LATIN1")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "지원하지 않는 인코딩입니다. 사용할 수 있는 값: AUTO, UTF-8, UTF-16LE, UTF-16BE, EUC-KR, CP949, MS949"));
  }

  /** 지원하지 않는 확장자 — 서비스가 던진 한국어 문구가 400 으로 그대로 나간다. */
  @Test
  void previewImport_unsupportedExtension_returns400Korean() throws Exception {
    when(importService.previewImport(anyLong(), any(), any(), anyBoolean()))
        .thenThrow(
            new com.smartfirehub.dataimport.exception.UnsupportedFileTypeException(
                com.smartfirehub.dataimport.exception.UnsupportedFileTypeException
                    .UNSUPPORTED_EXTENSION));

    mockMvc
        .perform(
            multipart("/api/v1/datasets/1/imports/preview")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes()))
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    com.smartfirehub.dataimport.exception.UnsupportedFileTypeException
                        .UNSUPPORTED_EXTENSION));
  }

  /** 없는(또는 다른 데이터셋의) 가져오기 id — 예전 400 「Import not found: N」 대신 404 한국어. */
  @Test
  void getImport_notFound_returns404Korean() throws Exception {
    when(importService.getImportById(1L, 999999L))
        .thenThrow(new com.smartfirehub.dataimport.exception.ImportNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/datasets/1/imports/999999").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.message")
                .value(com.smartfirehub.dataimport.exception.ImportNotFoundException.MESSAGE));
  }
}
