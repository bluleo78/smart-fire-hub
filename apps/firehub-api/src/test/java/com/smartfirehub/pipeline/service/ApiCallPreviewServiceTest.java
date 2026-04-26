package com.smartfirehub.pipeline.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.pipeline.dto.ApiCallPreviewRequest;
import com.smartfirehub.pipeline.dto.ApiCallPreviewResponse;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ApiCallPreviewService 통합 테스트.
 *
 * <p>핵심 검증 항목: 1. UTF-8 멀티바이트 문자(한글 등) 안전 절단 — 버그 C1 수정 검증 - 바이트 경계에서 문자 중간을 자를 경우 깨진 문자(\uFFFD) 생성
 * 방지 - 선행 바이트 위치까지 후퇴하여 완전한 문자만 포함 2. ASCII 전용 문자열 truncate 정상 동작 3. maxBytes 이내 문자열은 그대로 반환 4. 정상
 * API 호출 성공 케이스 5. SSRF 방어 — 사설 IP 접근 차단 6. 연결 오류 시 에러 응답 반환
 *
 * <p>WireMock으로 외부 HTTP 서버를 모킹하고, SsrfProtectionService는 테스트용 no-op으로 대체한다. dataPath는 JSONPath
 * 표현식이므로 null이면 예외가 발생한다 — 테스트에서는 반드시 "$"를 전달한다.
 */
@ExtendWith(MockitoExtension.class)
class ApiCallPreviewServiceTest {

  /** WireMock 서버 (동적 포트) */
  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetWireMock() {
    wireMock.resetAll();
  }

  @Mock ApiConnectionService apiConnectionService;

  /** 테스트 대상 서비스 인스턴스 */
  ApiCallPreviewService service;

  @BeforeEach
  void setUp() {
    // SSRF 검사를 우회하는 테스트용 no-op SsrfProtectionService
    // (WireMock localhost 접근을 허용)
    SsrfProtectionService noOpSsrf =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            // 테스트에서는 SSRF 검사 생략 (WireMock 서버 허용)
          }
        };

    service =
        new ApiCallPreviewService(
            noOpSsrf, new JsonResponseParser(), apiConnectionService, WebClient.builder());
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /** WireMock 기본 URL */
  private String baseUrl() {
    return "http://localhost:" + wireMock.port();
  }

  /**
   * 주어진 응답 바디를 반환하는 WireMock stub을 등록하고 preview를 실행한다. dataPath="$" 로 루트 배열을 지정하므로 responseBody는
   * JSON 배열이어야 한다.
   *
   * <p>rawJson truncate 검증이 목적이므로 rows 파싱 결과는 검증하지 않는다.
   *
   * @param responseBody WireMock이 반환할 응답 바디 (JSON 배열 형식)
   * @return ApiCallPreviewService.preview() 결과
   */
  private ApiCallPreviewResponse doPreviewWithPath(String responseBody, String dataPath) {
    wireMock.stubFor(
        get(urlEqualTo("/test"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(responseBody)));

    return service.preview(
        new ApiCallPreviewRequest(
            baseUrl() + "/test", "GET", null, null, null, dataPath, null, null, null, 5000));
  }

  /** rawJson truncate 검증 전용 헬퍼. 응답 바디는 배열로 감싸고 dataPath="$"를 사용한다. */
  private ApiCallPreviewResponse doPreviewForTruncate(String largeString) {
    // 큰 문자열을 JSON 배열 원소로 감싸서 전송
    // rawJson은 전체 응답 바이트를 10KB로 절단한 결과
    String jsonBody = "[{\"data\":\"__PLACEHOLDER__\"}]".replace("__PLACEHOLDER__", "");
    // 직접 배열 JSON 구성 (문자열 이스케이프 없이 전송하기 위해 raw body 사용)
    // rawJson 검증이 목적이므로 단순 문자열을 배열로 감쌈
    String arrayBody = "[\"placeholder\"]"; // 파싱용 최소 배열

    wireMock.stubFor(
        get(urlEqualTo("/truncate-test"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody(largeString.getBytes(StandardCharsets.UTF_8))));

    return service.preview(
        new ApiCallPreviewRequest(
            baseUrl() + "/truncate-test",
            "GET",
            null,
            null,
            null,
            "$", // 루트 JSONPath
            null,
            null,
            null,
            5000));
  }

  // =========================================================================
  // UTF-8 truncate 버그 수정 검증 (C1)
  // =========================================================================

  /**
   * 버그 C1 수정 검증: 한글 문자열이 10KB를 초과할 때 rawJson에 깨진 문자(\uFFFD)가 없어야 한다.
   *
   * <p>수정 전: new String(bytes, 0, maxBytes, UTF_8) → 멀티바이트 문자 중간 절단 → \uFFFD 생성 수정 후: maxBytes에서 뒤로
   * 이동하며 UTF-8 선행 바이트 위치까지 후퇴 → 완전한 문자만 포함
   *
   * <p>한글 1글자 = UTF-8 3바이트이므로 10KB 경계에서 3의 배수가 아닌 위치로 잘리면 버그 발생.
   */
  @Test
  void preview_rawJson_koreanText_noMojibake() {
    // 한글 3500자 = 10,500 UTF-8 바이트 > 10,240 바이트(10KB) → truncate 발생
    // 배열로 감싼 JSON: [{"msg":"가나다..."}]
    String koreanContent = "가나다라마바사아자차카타파하".repeat(250); // 3500자
    String jsonBody = "[{\"msg\":\"" + koreanContent + "\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(jsonBody, "$");

    // rawJson은 10KB 이하로 절단됨
    String rawJson = response.rawJson();
    assertThat(rawJson).isNotNull();

    // 핵심: 깨진 유니코드 대체 문자(\uFFFD)가 포함되지 않아야 한다 (버그 C1)
    assertThat(rawJson).doesNotContain("\uFFFD");

    // 절단된 결과가 유효한 UTF-8인지 검증 (인코딩 후 다시 디코딩해도 동일)
    byte[] encodedBack = rawJson.getBytes(StandardCharsets.UTF_8);
    String reDecoded = new String(encodedBack, StandardCharsets.UTF_8);
    assertThat(reDecoded).isEqualTo(rawJson);
  }

  /** 버그 C1 수정 검증: 일본어(히라가나)도 3바이트 UTF-8 문자이므로 동일한 버그에 노출됨. 절단 후 깨진 문자가 없어야 한다. */
  @Test
  void preview_rawJson_japaneseText_noMojibake() {
    String japaneseContent = "あいうえおかきくけこさしすせそたちつてとなにぬねの".repeat(150);
    String jsonBody = "[{\"msg\":\"" + japaneseContent + "\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(jsonBody, "$");

    String rawJson = response.rawJson();
    assertThat(rawJson).isNotNull();
    assertThat(rawJson).doesNotContain("\uFFFD");
  }

  /** 버그 C1 수정 검증: 이모지(4바이트 UTF-8 문자)도 경계에서 안전하게 절단되어야 한다. */
  @Test
  void preview_rawJson_emoji_noMojibake() {
    // 이모지 1자 = 4바이트, 충분히 쌓아서 10KB 초과
    String emojiContent = "😀🎉🚀🌟💡🔥🎯🏆".repeat(400);
    String jsonBody = "[{\"msg\":\"" + emojiContent + "\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(jsonBody, "$");

    String rawJson = response.rawJson();
    assertThat(rawJson).isNotNull();
    assertThat(rawJson).doesNotContain("\uFFFD");
  }

  /**
   * 정상: ASCII 전용 문자열은 1바이트이므로 후퇴 없이 maxBytes에서 정확히 절단된다. 절단 후 rawJson의 UTF-8 바이트 크기는 10KB 이하여야 한다.
   */
  @Test
  void preview_rawJson_asciiOnly_truncatesAtExactByte() {
    // 10KB + 500바이트 초과 ASCII 문자열
    String asciiContent = "a".repeat(10 * 1024 + 500);
    String jsonBody = "[{\"data\":\"" + asciiContent + "\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(jsonBody, "$");

    String rawJson = response.rawJson();
    assertThat(rawJson).isNotNull();
    int byteLength = rawJson.getBytes(StandardCharsets.UTF_8).length;
    assertThat(byteLength).isLessThanOrEqualTo(10 * 1024);
    assertThat(rawJson).doesNotContain("\uFFFD");
  }

  /** 정상: 응답 바디가 10KB 이내이면 truncate 없이 원본 그대로 반환된다. */
  @Test
  void preview_rawJson_withinMaxBytes_returnsOriginal() {
    String shortJson = "[{\"message\":\"안녕하세요\",\"status\":\"ok\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(shortJson, "$");

    // 10KB 이내이므로 rawJson은 원본과 동일
    assertThat(response.rawJson()).isEqualTo(shortJson);
    assertThat(response.rawJson()).doesNotContain("\uFFFD");
  }

  /** 정상: 한글이 10KB를 초과하지 않으면 원본 그대로 반환된다. */
  @Test
  void preview_rawJson_koreanWithinLimit_returnsOriginal() {
    String koreanJson = "[{\"name\":\"홍길동\",\"city\":\"서울특별시\"}]";

    ApiCallPreviewResponse response = doPreviewWithPath(koreanJson, "$");

    assertThat(response.rawJson()).isEqualTo(koreanJson);
  }

  // =========================================================================
  // 정상 API 호출 케이스
  // =========================================================================

  /** 정상: GET 요청으로 JSON 배열 응답을 받아 rows에 반환 */
  @Test
  void preview_get_jsonArray_returnsRows() {
    String jsonResponse = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";
    wireMock.stubFor(
        get(urlEqualTo("/api/data"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jsonResponse)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/api/data",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(
                    new ApiCallPreviewRequest.FieldMappingPreview("name", "name", "TEXT"),
                    new ApiCallPreviewRequest.FieldMappingPreview("age", "age", "INTEGER")),
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.errorMessage()).isNull();
    assertThat(response.rows()).hasSize(2);
    assertThat(response.totalExtractedRows()).isEqualTo(2);
    assertThat(response.columns()).containsExactly("name", "age");
  }

  /** 정상: dataPath를 지정하면 중첩 JSON에서 배열을 추출한다 */
  @Test
  void preview_withDataPath_extractsNestedArray() {
    String jsonResponse =
        "{\"result\":{\"items\":[{\"id\":1,\"label\":\"A\"},{\"id\":2,\"label\":\"B\"}]}}";
    wireMock.stubFor(
        get(urlEqualTo("/nested"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jsonResponse)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/nested",
                "GET",
                null,
                null,
                null,
                "$.result.items",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(2);
  }

  /** 정상: 결과가 MAX_PREVIEW_ROWS(5)를 초과하면 rows는 5건만 포함 */
  @Test
  void preview_manyRows_limitsPreviewToFiveRows() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 1; i <= 10; i++) {
      if (i > 1) sb.append(",");
      sb.append("{\"id\":").append(i).append("}");
    }
    sb.append("]");

    wireMock.stubFor(
        get(urlEqualTo("/many"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(sb.toString())));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/many", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows()).hasSize(5); // MAX_PREVIEW_ROWS 제한
    assertThat(response.totalExtractedRows()).isEqualTo(10); // 전체는 10건
  }

  /** 정상: POST 요청도 정상 실행된다 */
  @Test
  void preview_postMethod_success() {
    wireMock.stubFor(
        post(urlEqualTo("/post-endpoint"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"result\":\"ok\"}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/post-endpoint",
                "POST",
                null,
                null,
                "{\"query\":\"test\"}",
                "$",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.errorMessage()).isNull();
  }

  // =========================================================================
  // SSRF 방어 검증
  // =========================================================================

  /**
   * 보안: 실제 SsrfProtectionService를 사용하면 사설 IP(127.0.0.1)가 차단된다. success=false, errorMessage가
   * non-null이어야 한다.
   */
  @Test
  void preview_privateIp_ssrfBlocked_returnsError() {
    // 실제 SsrfProtectionService 사용 (사설 IP 차단)
    ApiCallPreviewService realSsrfService =
        new ApiCallPreviewService(
            new SsrfProtectionService(), // 실제 구현체
            new JsonResponseParser(),
            apiConnectionService,
            WebClient.builder());

    ApiCallPreviewResponse response =
        realSsrfService.preview(
            new ApiCallPreviewRequest(
                "http://127.0.0.1:8080/secret",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotNull();
  }

  // =========================================================================
  // 오류 케이스
  // =========================================================================

  /** 오류: HTTP 서버 연결 실패 시 success=false, errorMessage 반환 */
  @Test
  void preview_connectionRefused_returnsError() {
    // WireMock이 아닌 닫힌 포트로 요청
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "http://localhost:19999/unreachable",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                null,
                1000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotNull();
  }

  // =========================================================================
  // Phase 9: apiConnectionId 기반 URL 해석
  // =========================================================================

  /**
   * apiConnectionId가 설정된 경우: connection.baseUrl + request.url(path)로 URL을 구성한다. request.url()에
   * path만 넣으면 baseUrl과 결합되어야 한다.
   */
  @Test
  void preview_apiConnectionId_joinsBaseUrlAndPath() {
    // apiConnectionService.getById()가 baseUrl을 가진 connection을 반환
    com.smartfirehub.apiconnection.dto.ApiConnectionResponse mockConn =
        new com.smartfirehub.apiconnection.dto.ApiConnectionResponse(
            1L,
            "Test API",
            null,
            "BEARER",
            java.util.Map.of(),
            "http://localhost:" + wireMock.port(),
            null,
            null,
            null,
            null,
            null,
            1L,
            null,
            null);
    when(apiConnectionService.getById(1L)).thenReturn(mockConn);

    wireMock.stubFor(
        get(urlEqualTo("/v1/data"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    // request.url()에 path만 설정, apiConnectionId로 baseUrl 제공
    com.smartfirehub.pipeline.dto.ApiCallPreviewResponse response =
        service.preview(
            new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest(
                "/v1/data", "GET", null, null, null, "$", null, 1L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  // =========================================================================
  // Auth 헤더 적용 검증
  // =========================================================================

  /**
   * BEARER 인증: inlineAuth에 authType=BEARER, token 설정 시 Authorization: Bearer {token} 헤더가 전달되어야 한다.
   * WireMock에서 해당 헤더가 있어야만 200을 반환하도록 stub을 설정하여 검증한다.
   */
  @Test
  void preview_bearerAuth_appliesAuthorizationHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/secure"))
            .withHeader("Authorization", equalTo("Bearer my-secret-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/secure",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of("authType", "BEARER", "token", "my-secret-token"),
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  /**
   * API_KEY 헤더 방식: inlineAuth에 authType=API_KEY, placement=header, headerName, apiKey 설정 시 지정한
   * 헤더명으로 API 키가 전달되어야 한다.
   */
  @Test
  void preview_apiKeyHeaderAuth_appliesCustomHeaderName() {
    wireMock.stubFor(
        get(urlEqualTo("/api-key-header"))
            .withHeader("X-Api-Key", equalTo("key-abc-123"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"result\":\"ok\"}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/api-key-header",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of(
                    "authType", "API_KEY",
                    "placement", "header",
                    "headerName", "X-Api-Key",
                    "apiKey", "key-abc-123"),
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  // =========================================================================
  // Query Params 병합 검증
  // =========================================================================

  /**
   * Query Params 병합: request.queryParams(static)와 inlineAuth의 API_KEY(query placement)가 합쳐져서 URL
   * 쿼리스트링에 포함되어야 한다.
   */
  @Test
  void preview_queryParams_mergesStaticAndDynamic() {
    // static param(page=1)과 auth param(api_key=secret) 모두 있어야 200 반환
    wireMock.stubFor(
        get(urlPathEqualTo("/data"))
            .withQueryParam("page", equalTo("1"))
            .withQueryParam("api_key", equalTo("secret"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":10}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/data",
                "GET",
                null,
                java.util.Map.of("page", "1"),
                null,
                "$",
                null,
                null,
                java.util.Map.of(
                    "authType", "API_KEY",
                    "placement", "query",
                    "paramName", "api_key",
                    "apiKey", "secret"),
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  /**
   * API_KEY 쿼리파라미터 방식: inlineAuth에 authType=API_KEY, placement=query 설정 시 지정한 파라미터명으로 API 키가 URL
   * 쿼리스트링에 포함되어야 한다.
   */
  @Test
  void preview_apiKeyQueryAuth_appendsToQueryString() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search"))
            .withQueryParam("token", equalTo("qparam-key"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"name\":\"foo\"}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/search",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of(
                    "authType", "API_KEY",
                    "placement", "query",
                    "paramName", "token",
                    "apiKey", "qparam-key"),
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  // =========================================================================
  // 타임아웃 검증
  // =========================================================================

  /** 타임아웃 적용: timeoutMs가 설정된 경우 그 시간 내에 응답이 오면 성공한다. 여유 있는 타임아웃(5000ms)으로 정상 응답을 받는 케이스 검증. */
  @Test
  void preview_customTimeoutMs_appliedToRequest() {
    wireMock.stubFor(
        get(urlEqualTo("/timeout-ok"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"status\":\"ok\"}]")
                    .withFixedDelay(100))); // 100ms 지연, 5000ms timeout 이내

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/timeout-ok", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
  }

  /**
   * 타임아웃 초과: 서버 응답이 timeoutMs를 초과하면 success=false, errorMessage 반환. WireMock으로 응답을 늦게 반환하도록 설정하여
   * 타임아웃 동작을 검증한다.
   */
  @Test
  void preview_requestTimeout_returnsErrorMessage() {
    wireMock.stubFor(
        get(urlEqualTo("/slow"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")
                    .withFixedDelay(2000))); // 2초 지연

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/slow",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                null,
                500)); // 500ms timeout → 초과 발생

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotNull();
  }

  /** 오류: 잘못된 dataPath는 success=false, errorMessage 반환 */
  @Test
  void preview_invalidDataPath_returnsError() {
    wireMock.stubFor(
        get(urlEqualTo("/simple"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/simple",
                "GET",
                null,
                null,
                null,
                "$.nonexistent.path",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotNull();
  }
}
