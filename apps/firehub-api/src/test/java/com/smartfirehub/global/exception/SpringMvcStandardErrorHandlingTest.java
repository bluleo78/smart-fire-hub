package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 남은 Spring MVC 표준 예외(WD-626) — 전용 처리가 없어 일반 예외로 떨어지던 4xx 표준 예외(필수 헤더·쿠키 누락,
 * ResponseStatusException 등)가 500 「예기치 않은 오류」가 아니라 원래 상태 코드의 한국어 JSON 으로 나가는지, SSE 엔드포인트의 406 안내가
 * text/event-stream 을 가리키는지, 이미 커밋된 스트림 응답에 JSON 오류 본문이 섞이지 않는지를 실제 HTTP 왕복으로 검증한다.
 *
 * <p>MediaTypeErrorHandlingTest(WD-548)와 같은 이유로 MockMvc 대신 실제 서버 포트로 요청한다 — 커밋된 응답·multipart 해석·협상
 * 실패는 운영과 같은 Tomcat 경로를 타야 결함이 보인다. 표준 예외를 일으키는 엔드포인트는 {@link
 * SpringMvcStandardErrorStubController}(테스트 전용)를 쓴다.
 */
class SpringMvcStandardErrorHandlingTest extends IntegrationTestBase {

  /** 일반 500 안내 문구의 앞부분. */
  private static final String UNEXPECTED_PREFIX = "예기치 않은 오류";

  @LocalServerPort private int port;
  @Autowired private DSLContext dsl;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ObjectMapper objectMapper;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private SecurityFixture fx;
  private long admin;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    admin = fx.createUser("wd626-admin-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    fx.deleteUser(admin);
  }

  // --- 400: 필수 헤더·쿠키 누락 -----------------------------------------------------

  /** 필수 요청 헤더가 빠지면 500 이 아니라 400 이고, errors 에 헤더 이름이 실린다(쿼리 파라미터 누락 WD-405 와 같은 모양). */
  @Test
  void missingRequiredHeader_returns400WithHeaderName() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/header", "application/json")));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("message").asText()).isEqualTo(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE);
    assertThat(body.path("errors").path("X-Wd626-Token").asText())
        .isEqualTo("'X-Wd626-Token' 헤더가 필요합니다.");
  }

  /** 헤더는 왔지만 값이 비어 변환 결과가 없으면 「필요합니다」가 아니라 「값이 올바르지 않습니다」다(WD-626). */
  @Test
  void blankNumberHeader_returns400InvalidHeaderValue() throws Exception {
    HttpResponse<String> res =
        send(
            authed(get("/test/wd626/header-number", "application/json"))
                .header("X-Wd626-Number", ""));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("errors").path("X-Wd626-Number").asText())
        .isEqualTo("'X-Wd626-Number' 헤더 값이 올바르지 않습니다.");
  }

  /** 필수 헤더가 있으면 정상 처리된다(회귀). */
  @Test
  void requiredHeaderPresent_returns200() throws Exception {
    HttpResponse<String> res =
        send(authed(get("/test/wd626/header", "application/json")).header("X-Wd626-Token", "ok"));
    assertThat(res.statusCode()).as("본문: %s", res.body()).isEqualTo(200);
  }

  /** 다른 4xx 표준 예외(필수 쿠키 누락)도 일반 4xx 길로 400 한국어 JSON 이다. */
  @Test
  void missingRequiredCookie_returns400Korean() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/cookie", "application/json")));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("message").asText()).isEqualTo("요청이 올바르지 않습니다.");
  }

  // --- ResponseStatusException --------------------------------------------------

  /** 한국어 reason 의 ResponseStatusException 은 그 상태·그 문장으로 답한다(SavedQueryService 의 409). */
  @Test
  void responseStatusException_withKoreanReason_usesStatusAndReason() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/status-korean", "application/json")));
    JsonNode body = assertJsonError(res, 409);
    assertThat(body.path("message").asText()).isEqualTo("공유 쿼리의 SQL은 수정할 수 없습니다.");
  }

  /** 공백뿐인 reason 은 상태별 한국어 일반 문구로 답한다(WD-626 — reason 은 비어 있지 않을 때만 그대로 쓴다). */
  @Test
  void responseStatusException_withBlankReason_usesStatusMessage() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/status-blank", "application/json")));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("message").asText()).isEqualTo("요청이 올바르지 않습니다.");
  }

  /** 일반 4xx 길은 표준 예외가 실은 응답 헤더(Retry-After)를 그대로 넘기고, 429 는 전용 안내 문구다(WD-626). */
  @Test
  void standardException_withHeader_forwardsHeaderAnd429Message() throws Exception {
    HttpResponse<String> res =
        send(authed(get("/test/wd626/status-retry-after", "application/json")));
    JsonNode body = assertJsonError(res, 429);
    assertThat(res.headers().firstValue("Retry-After")).hasValue("30");
    assertThat(body.path("message").asText()).isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
  }

  /** reason 이 없는 4xx 는 공통 문구로 그 상태를 유지한다. */
  @Test
  void responseStatusException_withoutReason_keepsStatus() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/status-gone", "application/json")));
    JsonNode body = assertJsonError(res, 410);
    assertThat(body.path("message").asText()).isEqualTo("요청을 처리할 수 없습니다.");
  }

  // --- 5xx 표준 예외는 그대로 500 -------------------------------------------------

  /** 5xx 상태의 표준 예외는 지금처럼 500 「예기치 않은 오류」다(상태를 그대로 노출하지 않는다). */
  @Test
  void serverErrorResponse_staysGeneric500() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/error-5xx", "application/json")));
    JsonNode body = assertJsonError(res, 500);
    assertThat(body.path("message").asText()).startsWith(UNEXPECTED_PREFIX);
  }

  // --- 406: SSE 엔드포인트 -------------------------------------------------------

  /** SSE 전용 알림 스트림에 Accept: application/xml 이면 406 이고, 안내는 JSON 이 아니라 text/event-stream 을 가리킨다. */
  @Test
  void sseEndpoint_withXmlAccept_returns406PointingToEventStream() throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/notifications/stream", "application/xml")));
    JsonNode body = assertJsonError(res, 406);
    String message = body.path("message").asText();
    assertThat(message).startsWith("요청한 응답 형식을 지원하지 않습니다");
    assertThat(message).contains("text/event-stream");
    assertThat(message).doesNotContain("application/json");
  }

  /** JSON 엔드포인트의 406 안내는 그대로 application/json 이다(회귀). */
  @Test
  void jsonEndpoint_withXmlAccept_stillPointsToJson() throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/dataset-categories", "application/xml")));
    JsonNode body = assertJsonError(res, 406);
    assertThat(body.path("message").asText()).contains("application/json");
  }

  // --- 형식이 틀린 multipart ------------------------------------------------------

  /**
   * multipart/form-data 인데 boundary 가 없으면 400 + 업로드 본문 해석 불가 안내다(JSON 형식 안내가 아니다). WD-626 에서 확인한 결과
   * 이미 맞게 나가므로 회귀 고정용이다.
   */
  @Test
  void fileUpload_multipartWithoutBoundary_returns400UploadGuidance() throws Exception {
    HttpResponse<String> res =
        send(authed(post("/api/v1/files", "garbage", "multipart/form-data")));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("message").asText()).isEqualTo("파일 업로드 요청 본문을 해석할 수 없습니다.");
  }

  /**
   * boundary 는 있지만 본문에 경계 구분자가 없으면 Tomcat 은 part 0개로 해석한다 — 그래서 필수 part 누락(WD-521)으로 400 이고 errors
   * 에 {@code files} 가 실린다. 415·JSON 형식 안내가 아니라 업로드 파일이 필요하다는 안내이므로 맞는 응답이다(WD-626 에서 확인, 고정).
   */
  @Test
  void fileUpload_multipartWithBrokenBody_returns400MissingFilesPart() throws Exception {
    HttpResponse<String> res =
        send(
            authed(
                post("/api/v1/files", "not a multipart body", "multipart/form-data; boundary=x")));
    JsonNode body = assertJsonError(res, 400);
    assertThat(body.path("errors").path("files").asText()).isEqualTo("'files' 파일이 필요합니다.");
  }

  // --- 커밋된 응답 ------------------------------------------------------------------

  /** 스트림을 이미 흘려보낸(커밋된) 뒤 일반 예외가 나도 JSON 오류 본문이 스트림 뒤에 섞이지 않는다. */
  @Test
  void committedStream_thenGenericException_noJsonAppended() throws Exception {
    HttpResponse<String> res =
        send(authed(get("/test/wd626/committed-generic", "text/event-stream")));
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).startsWith("data: hello");
    assertThat(res.body()).doesNotContain(UNEXPECTED_PREFIX).doesNotContain("\"status\"");
  }

  /** 커밋된 뒤의 4xx 표준 예외도 JSON 을 섞지 않는다. */
  @Test
  void committedStream_then4xxException_noJsonAppended() throws Exception {
    HttpResponse<String> res = send(authed(get("/test/wd626/committed-4xx", "text/event-stream")));
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).startsWith("data: hello");
    assertThat(res.body()).doesNotContain("\"status\"");
  }

  /**
   * 커밋된 뒤 전용 처리가 있는 예외(IllegalStateException → 409)가 나도 JSON 을 섞지 않는다(WD-626). 커밋 확인은 일반 처리만이 아니라
   * 모든 처리가 거치는 json() 한곳에서 한다.
   */
  @Test
  void committedStream_thenSpecificHandlerException_noJsonAppended() throws Exception {
    HttpResponse<String> res =
        send(authed(get("/test/wd626/committed-specific", "text/event-stream")));
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).startsWith("data: hello");
    assertThat(res.body()).doesNotContain("\"status\"").doesNotContain("커밋 후 상태 충돌");
  }

  // --- helpers ----------------------------------------------------------------

  /** 상태·JSON Content-Type·본문 status 를 확인하고 본문을 돌려준다. */
  private JsonNode assertJsonError(HttpResponse<String> res, int status) throws Exception {
    assertThat(res.statusCode()).as("본문: %s", res.body()).isEqualTo(status);
    assertThat(res.headers().firstValue("Content-Type").orElse(""))
        .as("본문: %s", res.body())
        .contains("application/json");
    JsonNode body = objectMapper.readTree(res.body());
    assertThat(body.path("status").asInt()).isEqualTo(status);
    assertThat(body.path("message").asText()).as("한국어 message").containsPattern("[가-힣]|Validation");
    return body;
  }

  private HttpRequest.Builder post(String path, String body, String contentType) {
    return HttpRequest.newBuilder(uri(path))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", contentType);
  }

  private HttpRequest.Builder get(String path, String accept) {
    return HttpRequest.newBuilder(uri(path)).GET().header("Accept", accept);
  }

  private HttpRequest.Builder authed(HttpRequest.Builder b) {
    return b.header(
        "Authorization",
        "Bearer " + jwt.generateAccessToken(admin, "u" + admin, DEFAULT_TEST_TENANT_ID));
  }

  private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
    return client.send(
        b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
