package com.smartfirehub.global.security;

import com.smartfirehub.global.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;

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
@Slf4j
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
    // WD-666: 앞선 필터가 이미 응답을 커밋했으면 상태·본문을 쓰지 않는다 — 커밋된 응답에 쓰면 예외가 나거나 앞 본문 뒤에 JSON 이 붙어 깨진다.
    if (response.isCommitted()) {
      log.debug(
          "인증 실패 401 을 쓰지 않음(응답이 이미 커밋됨): {} {}", request.getMethod(), request.getRequestURI());
      return;
    }
    // WD-666: 401 을 디버그 로그로 남긴다 — 메서드·경로(쿼리 문자열 제외)·예외 종류만. 토큰·쿼리·예외 메시지는 비밀이 섞일 수 있어 싣지 않는다.
    // 401 은 만료 토큰 갱신 흐름에서 흔해 info 이상이면 로그가 넘친다.
    log.debug(
        "인증 실패 401: {} {} ({})",
        request.getMethod(),
        request.getRequestURI(),
        authException.getClass().getSimpleName());
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
    try {
      objectMapper.writeValue(response.getOutputStream(), body);
    } catch (JacksonIOException e) {
      // Jackson 3(WD-793)은 출력 스트림 I/O 오류를 비검사 JacksonIOException 으로 감싼다 — 예전처럼 IOException 으로 올린다.
      // 원인 없는 JacksonIOException 은 없어야 하지만, 있으면 NPE 대신 그 예외를 IOException 으로 감싸 올린다.
      IOException cause = e.getCause();
      throw cause != null ? cause : new IOException(e.getMessage(), e);
    }
  }
}
