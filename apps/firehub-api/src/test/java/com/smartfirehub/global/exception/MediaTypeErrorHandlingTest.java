package com.smartfirehub.global.exception;

import static com.smartfirehub.jooq.Tables.ROLE;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 미디어 타입 오류(WD-548) — 요청 Content-Type·Accept 가 맞지 않을 때 500·Whitelabel HTML·빈 본문 대신 415/406 한국어 JSON
 * 으로 답하는지 실제 HTTP 왕복으로 검증한다.
 *
 * <p>MockMvc 가 아니라 실제 서버 포트로 요청하는 이유: Accept 불일치 시의 Whitelabel 오류 페이지(/error 포워드)와 Tomcat 이 형식이 틀린
 * Content-Type 을 다루는 방식은 MockMvc 에서 재현되지 않는다. 운영과 같은 경로를 타야 결함과 수정이 모두 보인다.
 */
class MediaTypeErrorHandlingTest extends IntegrationTestBase {

  /** 요청 형식 오류(415) 안내 문구의 공통 앞부분. */
  private static final String UNSUPPORTED_PREFIX = "지원하지 않는 요청 형식입니다";

  /** 응답 형식 오류(406) 안내 문구의 공통 앞부분. */
  private static final String NOT_ACCEPTABLE_PREFIX = "요청한 응답 형식을 지원하지 않습니다";

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
    admin = fx.createUser("wd548-admin-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    fx.deleteUser(admin);
  }

  // --- 415: 요청 본문 형식 불일치 -------------------------------------------------

  /** 공개 로그인 엔드포인트에 JSON 이 아닌 본문을 보내면 500 이 아니라 415 + 한국어 JSON 이다. */
  @ParameterizedTest
  @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded"})
  void login_withNonJsonContentType_returns415(String contentType) throws Exception {
    HttpResponse<String> res =
        send(post("/api/v1/auth/login", "{\"username\":\"a\",\"password\":\"b\"}", contentType));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
    assertThat(res.body()).contains("application/json");
    assertThat(res.headers().firstValue("Accept")).as("지원 형식 안내 헤더").isPresent();
  }

  /** Content-Type 헤더가 아예 없어도 415 다. */
  @Test
  void login_withoutContentType_returns415() throws Exception {
    HttpResponse<String> res = send(post("/api/v1/auth/login", "{}", null));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
  }

  /** 인증이 필요한 엔드포인트도 Content-Type 이 없으면 415 다. */
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/dataset-categories", "/api/v1/roles"})
  void authenticatedEndpoint_withoutContentType_returns415(String path) throws Exception {
    HttpResponse<String> res = send(authed(post(path, "{}", null)));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
  }

  /**
   * 형식이 틀린 Content-Type 은 500 이 아니라 415 + 한국어 JSON 이다(Spring 이 Invalid mime type 사유의 415 예외로 올린다).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/json; charset=bogus",
        "application/json;charset=",
        "application/;",
        "json",
        "application/json; q="
      })
  void login_withMalformedContentType_returns415(String contentType) throws Exception {
    HttpResponse<String> res = send(post("/api/v1/auth/login", "{}", contentType));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
    assertThat(objectMapper.readTree(res.body()).path("message").asText())
        .as("지원 목록이 비어도 JSON 으로 안내한다")
        .contains("application/json");
  }

  /** 멀티파트 전용 업로드에 JSON 을 보내면 415 이고, 안내는 multipart/form-data 를 가리킨다. */
  @Test
  void fileUpload_withJson_returns415() throws Exception {
    HttpResponse<String> res = send(authed(post("/api/v1/files", "{}", "application/json")));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
    assertThat(objectMapper.readTree(res.body()).path("message").asText())
        .contains("multipart/form-data");
  }

  /**
   * multipart/form-data 인데 boundary 가 없으면 400 + 업로드 본문 해석 불가 안내다(JSON 형식 안내가 아니다). WD-626 에서 확인한 결과
   * 이미 맞게 나가므로 회귀 고정용이다. multipart 해석은 Tomcat 이 하므로 MockMvc 슬라이스가 아니라 실제 서버 왕복이 필요하다(WD-649 에서
   * SpringMvcStandardErrorHandlingTest 로부터 옮김 — 별도 컨텍스트 없이 이 공유 통합 컨텍스트에서 돈다).
   */
  @Test
  void fileUpload_multipartWithoutBoundary_returns400UploadGuidance() throws Exception {
    HttpResponse<String> res =
        send(authed(post("/api/v1/files", "garbage", "multipart/form-data")));
    assertJsonError(res, 400, "파일 업로드 요청 본문을 해석할 수 없습니다.");
  }

  /**
   * boundary 는 있지만 본문에 경계 구분자가 없으면 Tomcat 은 part 0개로 해석한다 — 그래서 필수 part 누락(WD-521)으로 400 이고 errors
   * 에 {@code files} 가 실린다. 415·JSON 형식 안내가 아니라 업로드 파일이 필요하다는 안내이므로 맞는 응답이다(WD-626 에서 확인, 고정). 위와 같은
   * 이유로 실제 서버 왕복이다.
   */
  @Test
  void fileUpload_multipartWithBrokenBody_returns400MissingFilesPart() throws Exception {
    HttpResponse<String> res =
        send(
            authed(
                post("/api/v1/files", "not a multipart body", "multipart/form-data; boundary=x")));
    assertJsonError(res, 400, GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE);
    assertThat(objectMapper.readTree(res.body()).path("errors").path("files").asText())
        .isEqualTo("'files' 파일이 필요합니다.");
  }

  // --- 406: 응답 형식 불일치 -----------------------------------------------------

  /** Accept 가 JSON 을 받지 않아도(text/html·application/xml) Whitelabel·빈 본문이 아니라 406 한국어 JSON 이다. */
  @ParameterizedTest
  @ValueSource(strings = {"text/html", "application/xml", "image/png"})
  void list_withNonJsonAccept_returns406Json(String accept) throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/dataset-categories", accept)));
    assertJsonError(res, 406, NOT_ACCEPTABLE_PREFIX);
  }

  /**
   * 상세 조회에 Accept: image/png 를 보내면 500 이 아니라 406 한국어 JSON 이다. 컨트롤러까지 도달해 정상 응답을 쓰려다 협상에 실패하는 경로를
   * 보려고 실제로 존재하는 시스템 ADMIN 역할을 조회한다.
   */
  @Test
  void detail_withImageAccept_returns406Json() throws Exception {
    long adminRoleId =
        inTenantFixture(
            () ->
                dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.NAME.eq("ADMIN"))
                    .limit(1)
                    .fetchSingle(ROLE.ID));
    HttpResponse<String> res = send(authed(get("/api/v1/roles/" + adminRoleId, "image/png")));
    assertJsonError(res, 406, NOT_ACCEPTABLE_PREFIX);
  }

  /** 요청·응답 형식이 둘 다 JSON 이 아니어도(Content-Type text/plain + Accept text/html) 415 본문이 JSON 으로 나간다. */
  @Test
  void login_withNonJsonContentTypeAndAccept_returns415Json() throws Exception {
    HttpResponse<String> res =
        send(post("/api/v1/auth/login", "x", "text/plain").header("Accept", "text/html"));
    assertJsonError(res, 415, UNSUPPORTED_PREFIX);
  }

  // --- 일반 오류 응답: Accept 가 JSON 이 아니어도 JSON ------------------------------------

  /** 없는 역할 조회에 Accept: text/html 이면 Whitelabel HTML 이 아니라 404 한국어 JSON 이다. */
  @Test
  void missingRole_withHtmlAccept_returns404Json() throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/roles/987654321", "text/html")));
    assertJsonError(res, 404, null);
  }

  /** 없는 데이터셋 조회에 Accept: image/png 면 빈 본문 500 이 아니라 404 한국어 JSON 이다(이슈 댓글의 재현 경로). */
  @Test
  void missingDataset_withImageAccept_returns404Json() throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/datasets/987654321", "image/png")));
    assertJsonError(res, 404, null);
  }

  /** 검증 실패(400)도 Accept: application/xml 에서 빈 본문이 아니라 JSON 이다. */
  @Test
  void validationError_withXmlAccept_returns400Json() throws Exception {
    HttpResponse<String> res =
        send(
            post("/api/v1/auth/login", "{}", "application/json")
                .header("Accept", "application/xml"));
    assertJsonError(res, 400, GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE);
    assertThat(objectMapper.readTree(res.body()).path("errors").path("username").isMissingNode())
        .as("필드 오류가 함께 실린다")
        .isFalse();
  }

  // --- 회귀: 정상 요청은 그대로 ---------------------------------------------------

  /** 정상 JSON 요청(잘못된 자격증명)은 여전히 기존 처리(401)를 탄다. */
  @Test
  void login_withJson_stillHandledNormally() throws Exception {
    HttpResponse<String> res =
        send(
            post(
                "/api/v1/auth/login",
                "{\"username\":\"nobody-wd548\",\"password\":\"wrong-password\"}",
                "application/json"));
    assertThat(res.statusCode()).as("본문: %s", res.body()).isEqualTo(401);
  }

  /** 정상 Accept(application/json) 조회는 200 이다. */
  @Test
  void list_withJsonAccept_returns200() throws Exception {
    HttpResponse<String> res = send(authed(get("/api/v1/dataset-categories", "application/json")));
    assertThat(res.statusCode()).as("본문: %s", res.body()).isEqualTo(200);
  }

  // --- helpers ----------------------------------------------------------------

  /**
   * 상태·JSON Content-Type·ErrorResponse 형식·message 를 한 번에 확인한다. {@code messagePrefix} 가 null 이면 한국어
   * 문구인지만 본다.
   */
  private void assertJsonError(HttpResponse<String> res, int status, String messagePrefix)
      throws Exception {
    assertThat(res.statusCode()).as("본문: %s", res.body()).isEqualTo(status);
    assertThat(res.headers().firstValue("Content-Type").orElse(""))
        .as("본문: %s", res.body())
        .contains("application/json");
    JsonNode body = objectMapper.readTree(res.body());
    assertThat(body.path("status").asInt()).isEqualTo(status);
    String message = body.path("message").asText();
    if (messagePrefix != null) {
      assertThat(message).startsWith(messagePrefix);
    } else {
      // 접두어를 정하지 않은 경우(도메인 예외 문구)는 한국어 안내인지만 본다.
      assertThat(message).as("한국어 message").containsPattern("[가-힣]");
    }
  }

  private HttpRequest.Builder post(String path, String body, String contentType) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body));
    if (contentType != null) {
      b.header("Content-Type", contentType);
    }
    return b;
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
