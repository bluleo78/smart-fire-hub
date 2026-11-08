package com.smartfirehub.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 인증 진입점 401 응답 모양 회귀 테스트(WD-549).
 *
 * <p>왜 필요한가: 토큰 없음·변조·잘못된 스킴의 401 은 Spring Security 진입점이 직접 쓰므로 GlobalExceptionHandler 를 지나지 않는다.
 * 예전에는 문자열 이어 붙이기로 {@code {"error","message"}} 두 필드만, 영어 내부 메시지를, ISO-8859-1 로 내보내 다른 오류 응답과 모양이
 * 달랐다. 공통 {@code ErrorResponse} 모양·UTF-8·고정 한국어 문구로 나가는지 확인한다.
 */
@AutoConfigureMockMvc
class JsonAuthenticationEntryPointTest extends IntegrationTestBase {

  private static final String PROTECTED_PATH = "/api/v1/users/me";
  private static final String EXPECTED_MESSAGE = "로그인이 필요합니다. 다시 로그인하세요.";

  @Autowired private MockMvc mockMvc;

  /** Authorization 헤더가 아예 없으면 공통 오류 모양의 401 을 받는다. */
  @Test
  void noAuthorization_returnsCommonErrorResponse() throws Exception {
    assertCommon401(mockMvc.perform(get(PROTECTED_PATH)));
  }

  /** 서명이 맞지 않는 변조 토큰도 같은 401 을 받는다. */
  @Test
  void tamperedBearerToken_returnsCommonErrorResponse() throws Exception {
    assertCommon401(
        mockMvc.perform(
            get(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.tampered.sig")));
  }

  /** Basic 스킴은 지원하지 않으므로 인증 없음과 같은 401 을 받는다. */
  @Test
  void basicScheme_returnsCommonErrorResponse() throws Exception {
    assertCommon401(
        mockMvc.perform(
            get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")));
  }

  /** 소문자 bearer 는 인식하지 않는 스킴이라 같은 401 을 받는다. */
  @Test
  void lowercaseBearerScheme_returnsCommonErrorResponse() throws Exception {
    assertCommon401(
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "bearer abc.def")));
  }

  /** 회귀: 공개 엔드포인트는 토큰 없이도 진입점에 걸리지 않고 정상 응답한다. */
  @Test
  void publicEndpoint_withoutToken_stillWorks() throws Exception {
    mockMvc.perform(get("/api/v1/auth/signup-status")).andExpect(status().isOk());
  }

  /**
   * WD-666: 앞 필터가 이미 응답을 커밋했으면(본문을 쓰기 시작함) 진입점은 상태·본문을 건드리지 않는다 — 커밋된 응답에 쓰면 IllegalStateException
   * 이나 깨진 본문이 된다.
   */
  @Test
  void committedResponse_isLeftUntouched() throws Exception {
    JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(new ObjectMapper());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(200);
    response.getWriter().write("partial");
    response.flushBuffer();

    entryPoint.commence(request, response, new BadCredentialsException("x"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("partial");
  }

  /**
   * WD-666: 401 을 디버그 로그로 남긴다 — 메서드·경로·예외 종류만 싣고, 쿼리 문자열·Authorization 값·예외 메시지 같은 비밀이 섞일 수 있는 값은
   * 싣지 않는다.
   */
  @Test
  void unauthorized_isLoggedAtDebugWithoutSecrets() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(JsonAuthenticationEntryPoint.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_PATH);
      request.setQueryString("token=secret-token");
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-jwt");
      new JsonAuthenticationEntryPoint(new ObjectMapper())
          .commence(
              request, new MockHttpServletResponse(), new BadCredentialsException("secret-detail"));

      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.get(0);
      assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
      String line = event.getFormattedMessage();
      assertThat(line).contains("GET").contains(PROTECTED_PATH).contains("BadCredentialsException");
      assertThat(line).doesNotContain("secret");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
  }

  /** 401 · UTF-8 · ErrorResponse 전 필드 · 한국어 문구 · 요청 경로를 한 번에 확인한다. */
  private void assertCommon401(ResultActions result) throws Exception {
    result
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsStringIgnoringCase("json")))
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, containsStringIgnoringCase("charset=UTF-8")))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value(EXPECTED_MESSAGE))
        .andExpect(jsonPath("$.path").value(PROTECTED_PATH))
        .andExpect(jsonPath("$.timestamp").value(notNullValue()))
        .andExpect(jsonPath("$.errors").value(nullValue()))
        // 한국어가 바이트 수준에서도 깨지지 않았는지 UTF-8 로 다시 읽어 확인한다.
        .andExpect(content().encoding("UTF-8"));
  }
}
