package com.smartfirehub.global.security;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
