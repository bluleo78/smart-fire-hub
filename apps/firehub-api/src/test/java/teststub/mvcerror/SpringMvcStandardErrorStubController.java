package teststub.mvcerror;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spring MVC 표준 예외 처리(WD-626, WD-649) 검증용 테스트 전용 스텁 컨트롤러.
 *
 * <p>운영 코드에는 필수 헤더·쿠키를 받는 엔드포인트나 커밋 후 예외를 던지는 경로를 재현하기 어려운 곳이 많아, 표준 예외를 그대로 일으키는 최소 엔드포인트를 둔다.
 *
 * <p>패키지를 com.smartfirehub 밖(teststub)에 두는 이유(WD-649, WD-640 의 ValidationStubController 와 같은 방식):
 * 예전에는 스캔 패키지의 {@code @RestController} 라 모든 {@code @SpringBootTest} 컨텍스트에 {@code /test/wd626/*} 가
 * 등록됐다. {@code @SpringBootApplication} 의 컴포넌트 스캔 범위 밖이라 전체 컨텍스트에는 등록되지 않고, 이 스텁을 쓰는 WebMvc 슬라이스
 * 테스트(SpringMvcStandardErrorHandlingTest)만 {@code @Import} 로 올린다. 전체 컨텍스트에 없음은
 * ValidationStubIsolationTest 가 확인한다.
 */
@RestController
@RequestMapping("/test/wd626")
public class SpringMvcStandardErrorStubController {

  /** 필수 요청 헤더 누락 → MissingRequestHeaderException. */
  @GetMapping("/header")
  public String requiredHeader(@RequestHeader("X-Wd626-Token") String token) {
    return token;
  }

  /** 필수 쿠키 누락 → MissingRequestCookieException(ServletRequestBindingException 하위, 400). */
  @GetMapping("/cookie")
  public String requiredCookie(@CookieValue("wd626") String cookie) {
    return cookie;
  }

  /** 한국어 문장을 reason 으로 실은 ResponseStatusException — reason 은 보이지 않고 상태별 기본 문구여야 한다(WD-649). */
  @GetMapping("/status-korean")
  public void statusKorean() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "공유 쿼리의 SQL은 수정할 수 없습니다.");
  }

  /** 라이브러리가 던진 것 같은 영어 reason 의 ResponseStatusException — 상태별 한국어 문구로 바꿔야 한다(WD-649). */
  @GetMapping("/status-english")
  public void statusEnglish() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Entity version mismatch: row 42");
  }

  /** 공백뿐인 reason 의 ResponseStatusException — 상태별 일반 문구로 답해야 한다. */
  @GetMapping("/status-blank")
  public void statusBlank() {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "   ");
  }

  /** 응답 헤더(Retry-After)를 실은 429 표준 예외 — 일반 4xx 길이 헤더를 그대로 넘겨야 한다. */
  @GetMapping("/status-retry-after")
  public void statusRetryAfter() {
    ErrorResponseException ex = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS);
    ex.getHeaders().set(HttpHeaders.RETRY_AFTER, "30");
    throw ex;
  }

  /** 숫자 헤더 — 값이 비면 변환 결과가 null 이라 「변환 후 누락」 MissingRequestHeaderException 이 난다. */
  @GetMapping("/header-number")
  public String requiredNumberHeader(@RequestHeader("X-Wd626-Number") Long number) {
    return String.valueOf(number);
  }

  /** reason 없는 4xx ResponseStatusException. */
  @GetMapping("/status-gone")
  public void statusGone() {
    throw new ResponseStatusException(HttpStatus.GONE);
  }

  /** Retry-After 를 실은 503 표준 예외 — 500 으로 뭉개지지 않고 상태·헤더를 그대로 넘겨야 한다(WD-649). */
  @GetMapping("/error-5xx")
  public void error5xx() {
    ErrorResponseException ex = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    ex.getHeaders().set(HttpHeaders.RETRY_AFTER, "120");
    throw ex;
  }

  /**
   * 내부 정보가 담긴 reason 의 501 ResponseStatusException — 상태는 501, 문구는 reason 이 아닌 기본 문구여야 한다(WD-649).
   */
  @GetMapping("/not-implemented")
  public void notImplemented() {
    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "내부 모듈 com.example.Foo 미구현");
  }

  /** 응답 형식이 여럿(JSON·CSV)인 엔드포인트 — 406 안내가 둘 다 말해야 한다(WD-649). */
  @GetMapping(
      value = "/multi-format",
      produces = {MediaType.APPLICATION_JSON_VALUE, "text/csv"})
  public String multiFormat() {
    return "ok";
  }

  /** SSE 전용 엔드포인트 — 406 안내가 text/event-stream 을 가리켜야 한다(WD-626). */
  @GetMapping(value = "/sse-only", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public String sseOnly() {
    return "data: ok\n\n";
  }

  /** JSON 전용 엔드포인트 — 406 안내가 application/json 하나다(회귀). */
  @GetMapping(value = "/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
  public String jsonOnly() {
    return "{}";
  }

  /** SSE 처럼 응답을 먼저 커밋한 뒤 일반 예외가 나는 경로. */
  @GetMapping("/committed-generic")
  public void committedGeneric(HttpServletResponse response) throws IOException {
    commitPartialStream(response);
    throw new RuntimeException("boom after commit wd626");
  }

  /** 응답을 먼저 커밋한 뒤 4xx 표준 예외가 나는 경로. */
  @GetMapping("/committed-4xx")
  public void committed4xx(HttpServletResponse response) throws IOException {
    commitPartialStream(response);
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "커밋 후 오류");
  }

  /** 응답을 먼저 커밋한 뒤 5xx 표준 예외가 나는 경로 — 상태 보존(WD-649)이 커밋된 스트림을 깨지 않아야 한다. */
  @GetMapping("/committed-5xx")
  public void committed5xx(HttpServletResponse response) throws IOException {
    commitPartialStream(response);
    throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** 응답을 먼저 커밋한 뒤 전용 처리가 있는 예외(IllegalStateException → 409)가 나는 경로. */
  @GetMapping("/committed-specific")
  public void committedSpecific(HttpServletResponse response) throws IOException {
    commitPartialStream(response);
    throw new IllegalStateException("커밋 후 상태 충돌");
  }

  /** text/event-stream 한 줄을 쓰고 flush 해 응답을 커밋한다. */
  private static void commitPartialStream(HttpServletResponse response) throws IOException {
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.getOutputStream().write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
    response.flushBuffer();
  }
}
