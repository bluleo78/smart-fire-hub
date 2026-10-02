package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 기계가 읽는 오류 코드(ErrorResponse.code) 계약 고정.
 *
 * <p>웹은 403 을 "권한 없음" 과 "비밀번호 변경 필요" 로 구분해야 하고(메시지 문자열 비교는 깨지기
 * 쉽다), 기존 응답(코드 없음)의 JSON 모양은 바뀌면 안 된다 — 두 가지를 함께 검증한다.
 */
class CodedApiExceptionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void codedException_isRenderedWithStatusAndCode() {
    var handler = new GlobalExceptionHandler();
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/signup");

    var response =
        handler.handleCodedApiException(
            new CodedApiException(HttpStatus.FORBIDDEN, "SIGNUP_DISABLED", "공개 가입이 닫혀 있습니다"),
            request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("SIGNUP_DISABLED");
    assertThat(response.getBody().message()).isEqualTo("공개 가입이 닫혀 있습니다");
    assertThat(response.getBody().status()).isEqualTo(403);
  }

  @Test
  void codedException_detailsGoToErrorsMap() {
    var handler = new GlobalExceptionHandler();
    var response =
        handler.handleCodedApiException(
            new CodedApiException(
                HttpStatus.CONFLICT, "MEMBER_SUSPENDED", "정지됨", java.util.Map.of("userId", "42")),
            new MockHttpServletRequest("POST", "/api/v1/users"));
    assertThat(response.getBody().errors()).containsEntry("userId", "42");
  }

  @Test
  void legacyErrorResponse_omitsCodeField() throws Exception {
    // 기존 6인자 생성자로 만든 응답에는 code 키 자체가 없어야 한다(기존 클라이언트 무영향).
    var legacy = new ErrorResponse(400, "Bad Request", "x", null, "t", "/p");
    String json = objectMapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("\"code\"");
  }
}
