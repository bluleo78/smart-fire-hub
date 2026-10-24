package com.smartfirehub.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GlobalExceptionHandler 추가 핸들러 커버리지 테스트. ExceptionStubControllerExtended가 던지는 예외를 통해 핸들러 분기를 검증한다.
 */
@SuppressWarnings("null")
@WebMvcTest(controllers = ExceptionStubControllerExtended.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerExtendedTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @Test
  void savedQueryNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/saved-query-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        // WD-196·WD-240: 분석 NotFound 문구는 한국어·번호 없이 응답 message 에 그대로 실린다(화면 토스트 원문)
        .andExpect(jsonPath("$.message").value("저장 쿼리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void chartNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/chart-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        // WD-196·WD-240: 분석 NotFound 문구는 한국어·번호 없이 응답 message 에 그대로 실린다(화면 토스트 원문)
        .andExpect(jsonPath("$.message").value("차트를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void dashboardNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/dashboard-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        // WD-196·WD-240: 분석 NotFound 문구는 한국어·번호 없이 응답 message 에 그대로 실린다(화면 토스트 원문)
        .andExpect(jsonPath("$.message").value("대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void apiConnectionNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/api-connection-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("API 연결을 찾을 수 없습니다: 88"));
  }

  /**
   * 「없음」이 아닌 API 연결 오류(지원하지 않는 인증 방식·내부 주소 거절 등)는 400 이다(WD-180). 예전에는 ApiConnectionException 전체가
   * 404 로 매핑돼 입력 오류가 「찾을 수 없음」으로 나갔다.
   */
  @Test
  void apiConnectionInvalid_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/api-connection-invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("지원하지 않는 인증 방식입니다: FOO (사용 가능: API_KEY, BEARER)"));
  }

  @Test
  void fileNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/file-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void fileSizeExceeded_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/file-size-exceeded"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void unsupportedUploadType_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/unsupported-upload-type"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void proactiveJobError_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/proactive-job-error"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void userDeactivated_returns401() throws Exception {
    mockMvc
        .perform(get("/test/exception2/user-deactivated"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void categoryNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/category-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        // WD-240: 영어 「Category not found: N」 대신 한국어·번호 없는 문구
        .andExpect(jsonPath("$.message").value("카테고리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void duplicateDatasetName_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception2/duplicate-dataset-name"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void columnModification_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/column-modification"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void cryptoError_returns500() throws Exception {
    mockMvc
        .perform(get("/test/exception2/crypto-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500));
  }

  @Test
  void serializationError_returns500() throws Exception {
    mockMvc
        .perform(get("/test/exception2/serialization-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500));
  }

  @Test
  void externalServiceError_returns502() throws Exception {
    mockMvc
        .perform(get("/test/exception2/external-service-error"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status").value(502));
  }

  @Test
  void embeddingError_returns502() throws Exception {
    mockMvc
        .perform(get("/test/exception2/embedding-error"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status").value(502));
  }

  @Test
  void importProcessing_returns500() throws Exception {
    mockMvc
        .perform(get("/test/exception2/import-processing"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500));
  }

  @Test
  void concurrentImport_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception2/concurrent-import"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void cyclicTriggerDependency_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception2/cyclic-trigger-dependency"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
  }

  /** 업로드 파일 크기 초과 시 500이 아닌 400과 한국어 안내 메시지를 반환해야 한다 (#137) */
  @Test
  void maxUploadSizeExceeded_returns400_withKoreanMessage() throws Exception {
    mockMvc
        .perform(get("/test/exception2/max-upload-size-exceeded"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("파일 크기가 허용 한도(256MB)를 초과했습니다. 더 작은 파일을 업로드해주세요."));
  }

  /**
   * @Validated 쿼리 파라미터 제약 위반 시 400 반환 (#139)
   */
  @Test
  void constraintViolation_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/constraint-violation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  /** path variable Long 타입에 비-숫자 문자열이 전달되면 500이 아닌 400 반환 (#219) */
  @Test
  void typeMismatch_pathLong_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/type-mismatch-long/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.errors.id").exists());
  }

  /** Boolean 쿼리 파라미터에 잘못된 값이 전달되면 500이 아닌 400 반환 (#219) */
  @Test
  void typeMismatch_queryBoolean_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception2/type-mismatch-boolean").param("flag", "notbool"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors.flag").exists());
  }

  /** malformed JSON body 시 500이 아닌 400 반환 (#219) */
  @Test
  void messageNotReadable_malformedJson_returns400() throws Exception {
    mockMvc
        .perform(
            post("/test/exception2/message-not-readable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not valid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("요청 본문을 해석할 수 없습니다."));
  }
}
