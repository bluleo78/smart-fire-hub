package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dataimport.exception.ImportValidationException;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.ErrorResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import teststub.validation.ValidationStubController;

/**
 * 검증 실패 응답 모양 통일(WD-640) — 경로가 달라도 400 + 「입력값이 올바르지 않습니다.」 + 한국어 필드 오류로 나오는지 HTTP 왕복으로 확인한다.
 *
 * <p>결함: HandlerMethodValidationException(메서드 파라미터 제약)·BindException(@ModelAttribute)은 처리기가 없어 일반
 * 예외 500 으로 떨어졌고, 클래스 단위 합성 제약(global error)은 getFieldErrors() 만 읽어 응답에서 사라졌다.
 */
@SuppressWarnings("null")
@WebMvcTest(controllers = ValidationStubController.class)
// 스텁은 컴포넌트 스캔 범위 밖(teststub)이라 controllers 필터로는 잡히지 않는다 — @Import 로 이 슬라이스에만 올린다.
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  GlobalExceptionHandler.class,
  ValidationStubController.class
})
class ValidationErrorShapeTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @Autowired private MockMvc mockMvc;

  /** 쿼리 파라미터 @Min 위반 → 400, 키는 @RequestParam 이름(size)이지 자바 파라미터 이름(pageSize)이 아니다. */
  @Test
  void requestParamConstraint_returns400WithKoreanFieldError() throws Exception {
    mockMvc
        .perform(get("/test/validation/param").param("size", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors.size").value("1 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors.pageSize").doesNotExist());
  }

  /** 경로 변수 @Size 위반 → 400, 한국어 Size 문구. */
  @Test
  void pathVariableConstraint_returns400WithKoreanFieldError() throws Exception {
    mockMvc
        .perform(get("/test/validation/path/abcdef"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors.code").value("3자 이하로 입력하세요."));
  }

  /** 본문 + 파라미터 제약이 함께 있으면 본문 필드 오류도 같은 응답의 errors 에 실린다. */
  @Test
  void bodyAndParamConstraint_bothReported() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/body-and-param")
                .param("size", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors.name").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors.size").value("1 이상이어야 합니다."));
  }

  /**
   * @ModelAttribute 필수값 위반·숫자 칸 형식 오류 → 400, 형식 오류도 영어 「Failed to convert…」가 아니라 한국어.
   */
  @Test
  void modelAttribute_returns400WithKoreanMessages() throws Exception {
    mockMvc
        .perform(get("/test/validation/model").param("name", "").param("age", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors.name").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors.age").value(ValidationErrorCollector.TYPE_MISMATCH_MESSAGE));
  }

  /** 클래스 단위 합성 제약 → 사라지지 않고 예약 키(_global)에 한국어 문구로 실린다. */
  @Test
  void classLevelConstraint_surfacesUnderGlobalKey() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/class-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a\",\"confirm\":\"b\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(
            jsonPath("$.errors." + ValidationErrorCollector.GLOBAL_KEY)
                .value("비밀번호 확인이 일치하지 않습니다."));
  }

  /** MethodArgumentNotValidException 이 아닌 순수 BindException 도 같은 400 모양(처리기 직접 호출). */
  @Test
  void plainBindException_handledAs400() {
    BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "form");
    br.addError(new ObjectError("form", new String[] {"SameValues"}, null, "두 값이 같아야 합니다."));
    ErrorResponse body =
        new GlobalExceptionHandler(null)
            .handleValidationExceptions(
                new BindException(br), new MockHttpServletRequest("GET", "/x"))
            .getBody();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE);
    assertThat(body.errors()).containsEntry(ValidationErrorCollector.GLOBAL_KEY, "두 값이 같아야 합니다.");
  }

  /** 임포트 검증 오류(error_N)는 행 순서대로 나간다 — 토스트가 첫 값을 보이므로 1행 오류가 먼저여야 한다. */
  @Test
  void importValidationErrors_keepRowOrder() {
    List<String> rows = IntStream.range(0, 12).mapToObj(i -> (i + 1) + "행 오류").toList();
    ErrorResponse body =
        new GlobalExceptionHandler(null)
            .handleImportValidation(
                new ImportValidationException("검증 실패", rows),
                new MockHttpServletRequest("POST", "/x"))
            .getBody();
    assertThat(body.errors().values()).containsExactlyElementsOf(rows);
  }

  /** 목록 본문 원소 오류는 「[1].name」 — 자바 파라미터 이름(requests)을 붙이지 않는다. */
  @Test
  void listBody_elementErrorsKeyedByIndex() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/list-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"name\":\"ok\"},{\"name\":\"\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.errors['[1].name']").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors['requests[1].name']").doesNotExist());
  }

  /**
   * 본문 값 제약(@NotEmpty List) 위반은 500 이 아니라 400 — 키는 본문 전체라 클라이언트가 부를 이름이 없어 _global, 같은 요청의 쿼리 파라미터
   * 오류는 그대로 size(WD-654). 결함: visitResults 의 ParameterErrors 캐스팅이 ClassCastException 으로 500 이 났다.
   */
  @Test
  void bodyValueConstraint_returns400NotClassCast500() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/not-empty-body")
                .param("size", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors._global").value("비어 있을 수 없습니다."))
        .andExpect(jsonPath("$.errors.size").value("1 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors.requests").doesNotExist());
  }

  /** 같은 본문의 원소 오류(값 제약 위반 없음)는 평소처럼 「[0].name」. */
  @Test
  void bodyValueConstraintEndpoint_elementErrorsStillKeyedByIndex() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/not-empty-body")
                .param("size", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"name\":\"\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors['[0].name']").value("필수 입력값입니다."));
  }

  /** 대체 경로(본문 값 제약 위반)에서도 원소 오류는 「[0].name」으로 남고, 본문 값 위반은 _global 이다(WD-654). */
  @Test
  void bodyValueConstraintFallback_keepsElementErrors() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/sized-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"name\":\"\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.errors['[0].name']").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors._global").exists());
  }

  /**
   * 폼 객체 자체의 값 위반은 @ModelAttribute 를 적었든 안 적었든 _global 이고, 자바 파라미터 이름(form) 키가 없다(WD-654 리뷰). 결함:
   * 애너테이션이 없으면 대체 경로가 값 파라미터로 보아 「form」 키로 나갔다.
   */
  @Test
  void modelObjectValueConstraint_sameShapeWithOrWithoutAnnotation() throws Exception {
    for (String path :
        List.of(
            "/test/validation/model-value-annotated", "/test/validation/model-value-unannotated")) {
      mockMvc
          .perform(get(path).param("name", "x"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
          .andExpect(jsonPath("$.errors._global").value("값을 지정할 수 없습니다."))
          .andExpect(jsonPath("$.errors.form").doesNotExist());
    }
  }

  /** 값 목록 파라미터 원소는 「ids[1]」. */
  @Test
  void listParam_elementKeyedWithName() throws Exception {
    mockMvc
        .perform(get("/test/validation/list-param").param("ids", "3", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors['ids[1]']").value("1 이상이어야 합니다."));
  }

  /**
   * @CookieValue 제약 — 키는 쿠키 이름(자바 파라미터 이름 session 이 아니다).
   */
  @Test
  void cookieValue_keyedByCookieName() throws Exception {
    mockMvc
        .perform(get("/test/validation/cookie").cookie(new Cookie("sid", "abcdef")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.sid").value("3자 이하로 입력하세요."))
        .andExpect(jsonPath("$.errors.session").doesNotExist());
  }

  /**
   * 애너테이션 없는 MultipartFile 은 값 파라미터다 — 키는 파라미터 이름(file), _global 이 아니다(WD-665 1). 결함: BeanUtils 로
   * 단순 타입이 아니라 폼 객체로 갈려 _global 로 나갔다(Spring 은 요청 파라미터로 바인딩하는데).
   */
  @Test
  void unannotatedMultipartFile_keyedByParameterName() throws Exception {
    mockMvc
        .perform(
            multipart("/test/validation/multipart-unannotated")
                .file(new MockMultipartFile("other", "a.txt", "text/plain", new byte[] {1})))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.errors.file").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors._global").doesNotExist());
  }

  /** 애너테이션 없는 MultipartFile 목록도 값 파라미터다 — 키는 files(WD-665 1). */
  @Test
  void unannotatedMultipartFileList_keyedByParameterName() throws Exception {
    mockMvc
        .perform(
            multipart("/test/validation/multipart-list-unannotated")
                .file(new MockMultipartFile("other", "a.txt", "text/plain", new byte[] {1})))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.files").value("비어 있을 수 없습니다."))
        .andExpect(jsonPath("$.errors._global").doesNotExist());
  }

  /** 애너테이션 없는 Optional&lt;Integer&gt; 는 값 파라미터다 — 키는 page(WD-665 1). */
  @Test
  void unannotatedOptionalSimple_keyedByParameterName() throws Exception {
    mockMvc
        .perform(get("/test/validation/optional-param").param("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.page").value("1 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors._global").doesNotExist());
  }

  /**
   * 목록 본문의 한 원소가 필드 오류와 클래스 단위 오류를 함께 내면 「[1].name」·「[1]」 — 접두 모양이 하나이고(「pairs[1]」 없음), 원소 위치를 잃고
   * _global 로 가지 않는다(WD-665 2·4).
   */
  @Test
  void listBodyElement_fieldAndClassLevelShareIndexPrefix() throws Exception {
    mockMvc
        .perform(
            post("/test/validation/pair-list")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[{\"name\":\"ok\",\"password\":\"a\",\"confirm\":\"a\"},"
                        + "{\"name\":\"\",\"password\":\"a\",\"confirm\":\"b\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors['[1].name']").value("필수 입력값입니다."))
        .andExpect(jsonPath("$.errors['[1]']").value("비밀번호 확인이 일치하지 않습니다."))
        .andExpect(jsonPath("$.errors['pairs[1]']").doesNotExist())
        .andExpect(jsonPath("$.errors._global").doesNotExist())
        .andExpect(jsonPath("$.errors['[0]']").doesNotExist());
  }

  /** 합성(메타 애너테이션) 제약의 위반도 키는 @RequestParam 이름(code)이다(WD-665 5). */
  @Test
  void composedConstraint_keyedByRequestParamName() throws Exception {
    mockMvc
        .perform(get("/test/validation/composed").param("code", "abcdef"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.code").value("3자 이하로 입력하세요."))
        .andExpect(jsonPath("$.errors.value").doesNotExist());
  }
}
