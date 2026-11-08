package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import teststub.mvcerror.SpringMvcStandardErrorStubController;

/**
 * 남은 Spring MVC 표준 예외(WD-626, WD-649) — 전용 처리가 없어 일반 예외로 떨어지던 표준 예외(필수 헤더·쿠키 누락,
 * ResponseStatusException, 5xx 표준 예외 등)가 500 「예기치 않은 오류」가 아니라 원래 상태 코드·헤더의 한국어 JSON 으로 나가는지, 컨트롤러
 * 파라미터 검증이 필드 맵을 싣는지, 406 안내가 지원 형식을 모두 말하는지, 이미 커밋된 스트림 응답에 JSON 오류 본문이 섞이지 않는지를 검증한다.
 *
 * <p>MVC 슬라이스(@WebMvcTest)로 돈다(WD-649) — 예전에는 실제 서버 포트(RANDOM_PORT)로 요청하면서 스텁 설정을 {@code @Import} 해
 * 전체 앱 컨텍스트를 하나 더 띄웠다. 여기 검증은 모두 Spring MVC 의 예외 처리·협상이라 MockMvc 로 같은 길을 탄다(커밋된 응답은 스텁이 {@code
 * flushBuffer()} 로 MockHttpServletResponse 를 커밋해 재현한다). Tomcat 의 multipart 해석이 필요한 두 경우만 공유 통합
 * 컨텍스트의 {@link MediaTypeErrorHandlingTest} 에 있다. 표준 예외를 일으키는 엔드포인트는 {@link
 * SpringMvcStandardErrorStubController}(테스트 전용, 스캔 범위 밖 teststub 패키지)를 이 슬라이스에만 들여온다. 보안 필터 체인은
 * GlobalExceptionHandlerTest 와 같은 구성이다(나머지 요청은 permitAll).
 */
@SuppressWarnings("null")
@WebMvcTest(controllers = SpringMvcStandardErrorStubController.class)
// 스텁은 컴포넌트 스캔 범위 밖(teststub)이라 controllers 필터로는 잡히지 않는다 — @Import 로 이 슬라이스에만
// 올린다(ValidationErrorShapeTest 와 같은 방식).
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  GlobalExceptionHandler.class,
  SpringMvcStandardErrorStubController.class
})
class SpringMvcStandardErrorHandlingTest {

  // WebMvcConfig 의 인터셉터·보안 필터가 요구하는 빈 — 목은 아무것도 던지지 않는다(GlobalExceptionHandlerTest 와 같은 구성).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @Autowired private MockMvc mockMvc;

  /** 일반 500 안내 문구의 앞부분. */
  private static final String UNEXPECTED_PREFIX = "예기치 않은 오류";

  // --- 400: 필수 헤더·쿠키 누락 -----------------------------------------------------

  /** 필수 요청 헤더가 빠지면 500 이 아니라 400 이고, errors 에 헤더 이름이 실린다(쿼리 파라미터 누락 WD-405 와 같은 모양). */
  @Test
  void missingRequiredHeader_returns400WithHeaderName() throws Exception {
    expectJsonError(perform(get("/test/wd626/header")), 400)
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors['X-Wd626-Token']").value("'X-Wd626-Token' 헤더가 필요합니다."));
  }

  /** 헤더는 왔지만 값이 비어 변환 결과가 없으면 「필요합니다」가 아니라 「값이 올바르지 않습니다」다(WD-626). */
  @Test
  void blankNumberHeader_returns400InvalidHeaderValue() throws Exception {
    expectJsonError(perform(get("/test/wd626/header-number").header("X-Wd626-Number", "")), 400)
        .andExpect(
            jsonPath("$.errors['X-Wd626-Number']").value("'X-Wd626-Number' 헤더 값이 올바르지 않습니다."));
  }

  /** 필수 헤더가 있으면 정상 처리된다(회귀). */
  @Test
  void requiredHeaderPresent_returns200() throws Exception {
    perform(get("/test/wd626/header").header("X-Wd626-Token", "ok")).andExpect(status().isOk());
  }

  /** 다른 4xx 표준 예외(필수 쿠키 누락)도 일반 4xx 길로 400 한국어 JSON 이다. */
  @Test
  void missingRequiredCookie_returns400Korean() throws Exception {
    expectJsonError(perform(get("/test/wd626/cookie")), 400)
        .andExpect(jsonPath("$.message").value("요청이 올바르지 않습니다."));
  }

  // --- ResponseStatusException: reason 은 보이지 않는다(WD-649) ---------------------------

  /** 한국어 reason 이어도 그대로 보이지 않고 그 상태의 기본 문구다 — 사용자 문구가 필요하면 도메인 예외를 쓴다(WD-649). */
  @Test
  void responseStatusException_withKoreanReason_usesStatusMessage() throws Exception {
    expectJsonError(perform(get("/test/wd626/status-korean")), 409)
        .andExpect(jsonPath("$.message").value("요청이 현재 상태와 충돌합니다."));
  }

  /** 라이브러리가 던진 것 같은 영어 reason 도 상태별 한국어 문구로 바뀐다(WD-649). */
  @Test
  void responseStatusException_withEnglishReason_usesStatusMessage() throws Exception {
    expectJsonError(perform(get("/test/wd626/status-english")), 409)
        .andExpect(jsonPath("$.message").value("요청이 현재 상태와 충돌합니다."))
        .andExpect(content().string(not(containsString("Entity version mismatch"))));
  }

  /** 공백뿐인 reason 도 상태별 한국어 일반 문구로 답한다. */
  @Test
  void responseStatusException_withBlankReason_usesStatusMessage() throws Exception {
    expectJsonError(perform(get("/test/wd626/status-blank")), 400)
        .andExpect(jsonPath("$.message").value("요청이 올바르지 않습니다."));
  }

  /** 일반 4xx 길은 표준 예외가 실은 응답 헤더(Retry-After)를 그대로 넘기고, 429 는 전용 안내 문구다(WD-626). */
  @Test
  void standardException_withHeader_forwardsHeaderAnd429Message() throws Exception {
    expectJsonError(perform(get("/test/wd626/status-retry-after")), 429)
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.message").value("요청이 너무 많습니다. 잠시 후 다시 시도하세요."));
  }

  /** reason 이 없는 4xx 는 공통 문구로 그 상태를 유지한다. */
  @Test
  void responseStatusException_withoutReason_keepsStatus() throws Exception {
    expectJsonError(perform(get("/test/wd626/status-gone")), 410)
        .andExpect(jsonPath("$.message").value("요청을 처리할 수 없습니다."));
  }

  // --- 5xx 표준 예외는 상태·헤더 보존 ---------------------------------------------

  /** 503 표준 예외는 500 으로 뭉개지지 않고 503 + Retry-After 그대로, 문구는 상태별 한국어 안내다(WD-649). */
  @Test
  void serviceUnavailableErrorResponse_keeps503AndRetryAfter() throws Exception {
    expectJsonError(perform(get("/test/wd626/error-5xx")), 503)
        .andExpect(header().string("Retry-After", "120"))
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.SERVICE_UNAVAILABLE_MESSAGE));
  }

  /** 501 은 501 이고, reason 의 내부 정보는 보이지 않고 기본 문구다(WD-649). */
  @Test
  void notImplementedResponseStatus_keeps501WithoutReason() throws Exception {
    expectJsonError(perform(get("/test/wd626/not-implemented")), 501)
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.NOT_IMPLEMENTED_MESSAGE))
        .andExpect(content().string(not(containsString("com.example.Foo"))));
  }

  // --- 406 ---------------------------------------------------------------------

  /**
   * SSE 전용 엔드포인트에 Accept: application/xml 이면 406 이고, 안내는 JSON 이 아니라 text/event-stream 을
   * 가리킨다(WD-626).
   */
  @Test
  void sseEndpoint_withXmlAccept_returns406PointingToEventStream() throws Exception {
    expectJsonError(perform(get("/test/wd626/sse-only").accept(MediaType.APPLICATION_XML)), 406)
        .andExpect(jsonPath("$.message", startsWith("요청한 응답 형식을 지원하지 않습니다")))
        .andExpect(jsonPath("$.message", containsString("text/event-stream")))
        .andExpect(jsonPath("$.message", not(containsString("application/json"))));
  }

  /** JSON 엔드포인트의 406 안내는 그대로 application/json 이다(회귀). */
  @Test
  void jsonEndpoint_withXmlAccept_stillPointsToJson() throws Exception {
    expectJsonError(perform(get("/test/wd626/json-only").accept(MediaType.APPLICATION_XML)), 406)
        .andExpect(jsonPath("$.message", containsString("application/json 으로만")));
  }

  /** 응답 형식이 여럿(produces JSON·CSV)이면 406 안내가 둘 다 말한다(WD-649 — 예전에는 JSON 하나만 「으로만」 안내). */
  @Test
  void multiFormatEndpoint_withXmlAccept_mentionsAllFormats() throws Exception {
    expectJsonError(perform(get("/test/wd626/multi-format").accept(MediaType.APPLICATION_XML)), 406)
        .andExpect(jsonPath("$.message", containsString("application/json 또는 text/csv")))
        .andExpect(jsonPath("$.message", not(containsString("으로만"))));
  }

  // --- 커밋된 응답 ------------------------------------------------------------------

  /** 스트림을 이미 흘려보낸(커밋된) 뒤 일반 예외가 나도 JSON 오류 본문이 스트림 뒤에 섞이지 않는다. */
  @Test
  void committedStream_thenGenericException_noJsonAppended() throws Exception {
    String body = committedBody("/test/wd626/committed-generic");
    assertThat(body).doesNotContain(UNEXPECTED_PREFIX);
  }

  /** 커밋된 뒤의 4xx 표준 예외도 JSON 을 섞지 않는다. */
  @Test
  void committedStream_then4xxException_noJsonAppended() throws Exception {
    committedBody("/test/wd626/committed-4xx");
  }

  /** 커밋된 뒤의 5xx 표준 예외도 상태 보존(WD-649)과 무관하게 JSON 을 섞지 않는다. */
  @Test
  void committedStream_then5xxException_noJsonAppended() throws Exception {
    committedBody("/test/wd626/committed-5xx");
  }

  /**
   * 커밋된 뒤 전용 처리가 있는 예외(IllegalStateException → 409)가 나도 JSON 을 섞지 않는다 — 확인이 json() 한곳에 있다(WD-626).
   */
  @Test
  void committedStream_thenSpecificHandlerException_noJsonAppended() throws Exception {
    String body = committedBody("/test/wd626/committed-specific");
    assertThat(body).doesNotContain("커밋 후 상태 충돌");
  }

  // --- helpers ----------------------------------------------------------------

  /** 요청을 보낸다 — Accept 를 정하지 않으면 MockMvc 기본(헤더 없음 = 무엇이든)이다. */
  private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request);
  }

  /** 상태·JSON Content-Type·본문 status·한국어 message 를 확인한다. */
  private static ResultActions expectJsonError(ResultActions actions, int status) throws Exception {
    return actions
        .andExpect(status().is(status))
        .andExpect(header().string("Content-Type", containsString("application/json")))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.message", org.hamcrest.Matchers.matchesPattern("(?s).*[가-힣].*")));
  }

  /** 커밋된 스트림 응답 — 200, 스트림 첫 줄로 시작, 뒤에 JSON 오류 본문이 없다. 본문을 돌려준다. */
  private String committedBody(String path) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get(path).accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(body).startsWith("data: hello").doesNotContain("\"status\"");
    return body;
  }
}
