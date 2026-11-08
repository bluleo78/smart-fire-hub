package com.smartfirehub.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 인증이 없거나 무효한 요청에 공통 {@link ErrorResponse} 모양의 401 을 쓰는 진입점(WD-549).
 *
 * <p>왜 별도 클래스인가: 이 401 은 Spring Security 필터 단계에서 나가므로 GlobalExceptionHandler 를 지나지 않는다. 예전 람다는 문자열
 * 이어 붙이기로 {@code {"error","message"}} 두 필드만, 영어 내부 메시지({@code authException.getMessage()})를 이스케이프
 * 없이, charset 없이(컨테이너 기본 ISO-8859-1) 내보내 다른 오류와 모양이 달랐다. 여기서는 Spring 의 ObjectMapper 로 직렬화하고 UTF-8 을
 * 명시한다.
 *
 * <p>403 이 아닌 401 을 쓰는 이유는 그대로다 — 프론트엔드의 토큰 갱신 인터셉터가 401 에서만 refresh 를 시도한다.
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  /** 고정 안내 문구. 내부 예외 메시지(영어·구현 세부)를 클라이언트에 노출하지 않으려고 쓰지 않는다. */
  static final String MESSAGE = "로그인이 필요합니다. 다시 로그인하세요.";

  private final ObjectMapper objectMapper;

  public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 401 · UTF-8 JSON · 공통 ErrorResponse(timestamp·path 는 GlobalExceptionHandler 와 같은 방식)를 쓴다. */
  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    HttpStatus status = HttpStatus.UNAUTHORIZED;
    ErrorResponse body =
        new ErrorResponse(
            status.value(),
            status.getReasonPhrase(),
            MESSAGE,
            null,
            Instant.now().toString(),
            request.getRequestURI());
    response.setStatus(status.value());
    // charset 을 명시하지 않으면 직접 쓰는 응답은 ISO-8859-1 로 나가 한국어가 깨진다.
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
