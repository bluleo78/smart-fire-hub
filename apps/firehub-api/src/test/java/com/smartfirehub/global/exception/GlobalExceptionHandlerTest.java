package com.smartfirehub.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(controllers = ExceptionStubController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  // --- Tests -----------------------------------------------------------------

  @Test
  void usernameAlreadyExists_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception/username-exists"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Username already exists"));
  }

  @Test
  void emailAlreadyExists_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception/email-exists"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Email already exists"));
  }

  @Test
  void invalidCredentials_returns401() throws Exception {
    mockMvc
        .perform(get("/test/exception/invalid-credentials"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid credentials"));
  }

  @Test
  void invalidToken_returns401() throws Exception {
    mockMvc
        .perform(get("/test/exception/invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid token"));
  }

  @Test
  void accessDenied_returns403() throws Exception {
    mockMvc
        .perform(get("/test/exception/access-denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"))
        .andExpect(jsonPath("$.message").value("Access denied"));
  }

  @Test
  void userNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/user-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("User not found"));
  }

  @Test
  void roleNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/role-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Role not found"));
  }

  @Test
  void datasetNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/dataset-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Dataset not found"));
  }

  @Test
  void pipelineNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/pipeline-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Pipeline not found"));
  }

  @Test
  void triggerNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/trigger-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Trigger not found"));
  }

  @Test
  void aiSessionNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/ai-session-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("AI 세션을 찾을 수 없습니다: 99"));
  }

  @Test
  void illegalArgument_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception/illegal-argument"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Illegal argument"));
  }

  @Test
  void systemRoleModification_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception/system-role-modification"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Cannot modify system role"));
  }

  @Test
  void importValidation_returns400_withErrorsMap() throws Exception {
    mockMvc
        .perform(get("/test/exception/import-validation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Import validation failed"))
        .andExpect(jsonPath("$.errors.error_0").value("Row 1: missing value"))
        .andExpect(jsonPath("$.errors.error_1").value("Row 2: invalid type"));
  }

  @Test
  void cyclicDependency_returns400() throws Exception {
    mockMvc
        .perform(get("/test/exception/cyclic-dependency"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Cyclic dependency detected"));
  }

  @Test
  void scriptExecution_returns500() throws Exception {
    mockMvc
        .perform(get("/test/exception/script-execution"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.error").value("Internal Server Error"))
        .andExpect(jsonPath("$.message").value("Script execution failed"));
  }

  @Test
  void dataIntegrityViolation_returns409() throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value("Data integrity violation"));
  }

  /**
   * 분류되지 않은 제약조건 위반이라도 원문 DB 메시지(테이블·컬럼명)가 사용자 응답에 새면 안 된다 (#320). 상세는 서버 로그에만 남기고 응답은 일반화된 문구만
   * 전달한다.
   */
  @Test
  void dataIntegrityViolation_unclassifiedCause_doesNotLeakRawDbMessage() throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation-unclassified"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.message").value("Data integrity violation"))
        .andExpect(jsonPath("$.message", not(containsString("dataset_mapping"))))
        .andExpect(jsonPath("$.message", not(containsString("tenant_id"))))
        .andExpect(jsonPath("$.message", not(containsString("relation"))));
  }

  /** 분류되는 세 원인의 사용자 메시지는 #320 수정 이후에도 그대로여야 한다. 체크 제약만 원문 concat 부분을 제거했다. */
  @Test
  void dataIntegrityViolation_classifiedCauses_keepStableMessagesWithoutRawDetail()
      throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation/duplicate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Data integrity violation: duplicate entry"));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation/fk"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message").value("Data integrity violation: referenced record not found"));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation/check"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Data integrity violation: constraint check failed"))
        .andExpect(jsonPath("$.message", not(containsString("ck_dataset_status"))));
  }

  @Test
  void methodArgumentNotValid_returns400_withFieldErrors() throws Exception {
    mockMvc
        .perform(
            post("/test/exception/method-argument-not-valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.errors.name").isNotEmpty());
  }

  @Test
  void accountLocked_returns429() throws Exception {
    mockMvc
        .perform(get("/test/exception/account-locked"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.error").value("Too Many Requests"))
        .andExpect(
            jsonPath("$.message").value("Too many failed login attempts. Please try again later."));
  }

  @Test
  void unexpectedError_returns500_withGenericMessage() throws Exception {
    mockMvc
        .perform(get("/test/exception/unexpected-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.error").value("Internal Server Error"))
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
  }

  /** 지원하지 않는 HTTP 메서드 요청 시 500 대신 405 반환 확인 (#197) */
  @Test
  void unsupportedHttpMethod_returns405() throws Exception {
    mockMvc
        .perform(post("/test/exception/unexpected-error"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.error").value("Method Not Allowed"))
        .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."));
  }
}
