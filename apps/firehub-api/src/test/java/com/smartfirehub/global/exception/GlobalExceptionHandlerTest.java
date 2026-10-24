package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(controllers = ExceptionStubController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

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
        .andExpect(jsonPath("$.message").value("데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void pipelineNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/pipeline-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void triggerNotFound_returns404() throws Exception {
    mockMvc
        .perform(get("/test/exception/trigger-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("트리거를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
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
        .andExpect(jsonPath("$.message").value("데이터 무결성 오류로 저장할 수 없습니다"));
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
        .andExpect(jsonPath("$.message").value("데이터 무결성 오류로 저장할 수 없습니다"))
        .andExpect(jsonPath("$.message", not(containsString("dataset_mapping"))))
        .andExpect(jsonPath("$.message", not(containsString("tenant_id"))))
        .andExpect(jsonPath("$.message", not(containsString("relation"))));
  }

  /**
   * 분류되는 세 원인은 각자 고정된 사용자 메시지를 갖는다(#320 — 원문 concat 없음). WD-99 부터 영어 원문 대신 한국어 문구다 — 화면이 이 문구를 그대로
   * 띄운다.
   */
  @Test
  void dataIntegrityViolation_classifiedCauses_keepStableMessagesWithoutRawDetail()
      throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation/duplicate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("이미 같은 값이 있어 저장할 수 없습니다"));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation/fk"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("연결할 항목을 찾을 수 없습니다"));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation/check"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("입력값이 제약 조건을 만족하지 않습니다"))
        .andExpect(jsonPath("$.message", not(containsString("ck_dataset_status"))));
  }

  /**
   * (WD-99) 외래 키 위반은 방향에 따라 문구가 다르다 — 삭제 쪽(다른 곳에서 참조 중)과 삽입·수정 쪽(연결 대상 없음)을 한 문구로 쓰면 한쪽에서 반대 뜻이 된다.
   * 판별할 수 없으면 중립 문구다. 어느 경우에도 테이블·제약 이름 원문은 응답에 없다.
   */
  @Test
  void dataIntegrityViolation_foreignKey_messageFollowsDirection() throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation/fk-delete"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("다른 곳에서 사용 중이라 삭제할 수 없습니다"))
        .andExpect(jsonPath("$.message", not(containsString("pipeline_step"))));

    // 메시지에 방향 단서가 없어도 PostgreSQL 세부(still referenced)로 삭제 쪽임을 안다.
    mockMvc
        .perform(get("/test/exception/data-integrity-violation-pg/fk-delete-detail"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("다른 곳에서 사용 중이라 삭제할 수 없습니다"));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation/fk-unknown"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("연결된 항목 때문에 처리할 수 없습니다"))
        .andExpect(jsonPath("$.message", not(containsString("fk_x"))));
  }

  /**
   * (WD-98) DB 데이터 오류(22xxx)는 영어 「Data integrity violation」(409)이 아니라 한국어 사유의 400 이다. SQL 문·내부
   * 테이블명·DB 원문은 응답에 새지 않는다.
   */
  @Test
  void dataIntegrityViolation_dataError22xxx_returns400WithKoreanReason() throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation-data-error/numeric"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(
            jsonPath("$.message")
                .value("저장할 수 없는 값이 있어 처리하지 못했습니다: 숫자 범위를 넘는 값이 있습니다(소수 컬럼은 정수부 최대 12자리)"))
        .andExpect(jsonPath("$.message", not(containsString("Data integrity"))))
        .andExpect(jsonPath("$.message", not(containsString("secret_tbl"))))
        .andExpect(jsonPath("$.message", not(containsString("overflow"))));

    mockMvc
        .perform(get("/test/exception/data-integrity-violation-data-error/varchar"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value("저장할 수 없는 값이 있어 처리하지 못했습니다: 'code' 컬럼에 글자 수 제한(최대 3자)을 넘는 값이 있습니다"));
  }

  /** (WD-98) 22xxx 가 아닌 제약조건 위반(중복 키 23505)은 400 이 아니라 409 중복 문구다(WD-99 부터 한국어). */
  @Test
  void dataIntegrityViolation_nonDataError_keeps409() throws Exception {
    mockMvc
        .perform(get("/test/exception/data-integrity-violation-data-error/duplicate"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("이미 같은 값이 있어 저장할 수 없습니다"));
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
        .andExpect(jsonPath("$.message").value("예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도하세요."));
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

  /**
   * 데이터셋 애드혹 쿼리 경로(#385 Task 3)에서 SqlValidator 가 던지는 UnsafeSqlException 이 500 으로 새지 않고, 파이프라인 SQL
   * 스텝(#136)과 동일하게 400 + ErrorResponse 로 매핑되는지 고정한다. DatasetController → DatasetDataService →
   * DataTableQueryService 어디에도 이 예외를 가로채는 try/catch 가 없으므로(코드 확인 완료), 이 핸들러 매핑이 실제 사용자 응답의 전부다.
   */
  @Test
  void unsafeSql_returns400_notInternalServerError() throws Exception {
    mockMvc
        .perform(get("/test/exception/unsafe-sql"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value(containsString("public.user")));
  }

  /**
   * 커밋 전 응답의 IOException 은 빈 200 이 아니라 일반 예외와 같은 형식의 500 JSON 이어야 한다(WD-204). 예전엔 void 핸들러가 로그만 남겨
   * 화면이 성공으로 오인했다. 원문 메시지는 노출하지 않는다.
   */
  @Test
  void ioException_uncommitted_returns500Json_notEmpty200() throws Exception {
    mockMvc
        .perform(get("/test/exception/io-error"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.message").value("예기치 않은 오류가 발생했습니다. 잠시 후 다시 시도하세요."))
        .andExpect(content().string(not(containsString("Unsupported Excel format"))));
  }

  /** SSE 요청(Accept: text/event-stream)이라도 협상 실패 없이 JSON 500 이 나가야 한다. */
  @Test
  void ioException_uncommitted_withEventStreamAccept_stillReturns500Json() throws Exception {
    mockMvc
        .perform(get("/test/exception/io-error").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500));
  }

  /** 클라이언트 연결 끊김(Broken pipe)은 기존처럼 본문 없이 조용히 끝낸다(bfd5c1fc 의도 유지). */
  @Test
  void ioException_clientDisconnected_staysSilent() throws Exception {
    mockMvc.perform(get("/test/exception/io-broken-pipe")).andExpect(content().string(""));
  }

  /** 이미 커밋된(스트리밍 중) 응답에는 아무것도 쓰지 않는다 — 핸들러가 null(처리 완료)을 돌려준다. */
  @Test
  void ioException_committedResponse_returnsNull() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    assertThat(
            handler.handleIOException(
                new java.io.IOException("Unsupported"), new MockHttpServletRequest(), response))
        .isNull();
  }

  /** 엑셀/CSV 를 읽지 못한 경우 400 + 한국어 안내, POI 원문은 본문에 없다(WD-204). */
  @Test
  void fileUnreadable_returns400_koreanWithoutParserText() throws Exception {
    mockMvc
        .perform(get("/test/exception/file-unreadable"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 엑셀 형식이 아닙니다."))
        .andExpect(content().string(not(containsString("OOXML"))));
  }
}
