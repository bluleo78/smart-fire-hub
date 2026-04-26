package com.smartfirehub.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GlobalExceptionHandler 추가 핸들러 커버리지 테스트. ExceptionStubControllerExtended가 던지는 예외를 통해 핸들러 분기를 검증한다.
 */
@SuppressWarnings("null")
@WebMvcTest(controllers = ExceptionStubControllerExtended.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerExtendedTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @Test
  void savedQueryNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/saved-query-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void chartNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/chart-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void dashboardNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/dashboard-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void apiConnectionNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception2/api-connection-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
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
        .andExpect(jsonPath("$.status").value(404));
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
}
