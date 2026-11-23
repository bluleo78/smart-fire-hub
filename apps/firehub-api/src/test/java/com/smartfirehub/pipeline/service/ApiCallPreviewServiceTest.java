package com.smartfirehub.pipeline.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.pipeline.dto.ApiCallPreviewRequest;
import com.smartfirehub.pipeline.dto.ApiCallPreviewResponse;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.pipeline.service.executor.SsrfSafeHttpClientFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.ReactorResourceFactory;
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

  /**
   * 사전 검사(validateUrl)만 끈 SsrfProtectionService — 접속 시점 검사(validateResolvedAddress)는 실제 판정 그대로다.
   */
  SsrfProtectionService noOpSsrf;

  @BeforeEach
  void setUp() {
    // SSRF 검사를 우회하는 테스트용 no-op SsrfProtectionService
    // (WireMock localhost 접근을 허용)
    noOpSsrf =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            // 테스트에서는 SSRF 검사 생략 (WireMock 서버 허용)
          }
        };

    service =
        new ApiCallPreviewService(
            noOpSsrf,
            new JsonResponseParser(),
            apiConnectionService,
            WebClient.builder(),
            // WireMock(루프백)에 접속해야 하므로 접속 시점 주소 검사(WD-613)도 끈다.
            new SsrfSafeHttpClientFactory(address -> {}));
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
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "name", "name", "TEXT", null, null),
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "age", "age", "INTEGER", null, null)),
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.errorMessage()).isNull();
    assertThat(response.rows()).hasSize(2);
    assertThat(response.totalExtractedRows()).isEqualTo(2);
    assertThat(response.columns()).containsExactly("name", "age");
    assertThat(response.totalColumns()).isEqualTo(response.columns().size());
  }

  // ── WD-947: 매핑 없는 미리보기는 원본 항목을 행으로 ──

  private ApiCallPreviewResponse previewRaw(
      List<ApiCallPreviewRequest.FieldMappingPreview> mappings) {
    wireMock.stubFor(
        get(urlEqualTo("/raw"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":1,\"name\":\"a\",\"tags\":[\"x\"]},{\"id\":2,\"extra\":true}]")));
    return service.preview(
        new ApiCallPreviewRequest(
            baseUrl() + "/raw", "GET", null, null, null, "$", mappings, null, null, 5000));
  }

  private void assertRawRows(ApiCallPreviewResponse response) {
    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("id", "name", "tags", "extra");
    assertThat(response.totalExtractedRows()).isEqualTo(2);
    assertThat(response.rows().get(0))
        .containsEntry("id", 1)
        .containsEntry("name", "a")
        .containsEntry("tags", "[\"x\"]");
  }

  /** 매핑이 null 이면 원본 최상위 필드가 행·열로 온다. */
  @Test
  void preview_withoutFieldMappings_returnsRawTopLevelRows() {
    assertRawRows(previewRaw(null));
  }

  /** 빈 매핑 목록도 원본 모드. */
  @Test
  void preview_emptyFieldMappings_returnsRawRows() {
    assertRawRows(previewRaw(List.of()));
  }

  /** 웹이 보내는 빈칸 소스 매핑만 있어도 원본 모드. */
  @Test
  void preview_blankSourceFieldOnly_returnsRawRows() {
    assertRawRows(
        previewRaw(
            List.of(new ApiCallPreviewRequest.FieldMappingPreview("", "", "TEXT", null, null))));
  }

  /** 매핑이 있으면 원본으로 바뀌지 않고 매핑 결과만 나온다. */
  @Test
  void preview_withFieldMapping_keepsMappedRows() {
    ApiCallPreviewResponse response =
        previewRaw(
            List.of(new ApiCallPreviewRequest.FieldMappingPreview("id", "pk", "TEXT", null, null)));
    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("pk");
    assertThat(response.rows().get(0).keySet()).containsExactly("pk");
  }

  /**
   * 원본 행에서 값 전체가 BASIC 비밀번호인 칸은 가려지고, 글 속에 든 비밀번호도 가려진다 — 원본 모드는 rawJson 과 같은 규칙이라 흔한 단어 비밀번호면 다른
   * 글도 가려질 수 있다(매핑 모드 결과 행은 전체 일치만 — preview_basicShortWordPassword_keepsRowsIntact).
   */
  @Test
  void preview_rawRows_basicPasswordCellMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "admin"));
    wireMock.stubFor(
        get(urlEqualTo("/roles"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"role\":\"admin user\",\"pw\":\"admin\"}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/roles", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0))
        .containsEntry("role", "**** user")
        .containsEntry("pw", "****");
  }

  /** 원본 행의 중첩 값 안에 든 BASIC 비밀번호도 가려진다(나머지 구조는 보존). */
  @Test
  void preview_rawRows_basicPasswordInNestedValueMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "admin"));
    wireMock.stubFor(
        get(urlEqualTo("/nestedpw"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"user\":{\"name\":\"bob\",\"pw\":\"admin\"}}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/nestedpw", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    String user = (String) response.rows().get(0).get("user");
    assertThat(user).doesNotContain("admin").contains("****").contains("name\":\"bob");
  }

  /**
   * 원본 행의 문자열 칸 속에 든 BASIC 비밀번호도 가려진다 — 요청 본문을 문자열로 되돌려 주는 에코 API(라이브 확인에서 httpbin 의 data 칸으로 발견).
   * 비밀번호가 없는 문자열 칸은 그대로다.
   */
  @Test
  void preview_rawRows_basicPasswordInsideStringCellMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(
            java.util.Map.of("authType", "BASIC", "username", "bob", "password", "Wd947Secret"));
    wireMock.stubFor(
        get(urlEqualTo("/echopw"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"data\":\"{\\\"pw\\\":\\\"Wd947Secret\\\",\\\"pin\\\":7}\",\"city\":\"seoul\"}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/echopw", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    String data = (String) response.rows().get(0).get("data");
    assertThat(data).doesNotContain("Wd947Secret");
    assertThat(response.rows().get(0)).containsEntry("city", "seoul");
  }

  /** 원본 행의 큰 중첩 값 칸은 500자 + 「…」 로 잘리고 스칼라 칸은 그대로다. */
  @Test
  void preview_rawRows_largeNestedValueTruncated() {
    String items = "[" + "\"abcdefghij\",".repeat(100) + "\"z\"]";
    wireMock.stubFor(
        get(urlEqualTo("/bigNested"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"items\":" + items + "}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/bigNested", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    String cell = (String) response.rows().get(0).get("items");
    assertThat(cell).hasSize(501).endsWith("…");
    assertThat(response.rows().get(0)).containsEntry("id", 1);
  }

  /** WD-947 3차: 숫자 칸도 글 속 비밀번호 규칙을 따른다(91234567 속 123456) — 가릴 게 없는 숫자는 숫자로 남는다. */
  @Test
  void preview_rawRows_numberContainingPasswordMaskedAsString() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "123456"));
    wireMock.stubFor(
        get(urlEqualTo("/numsub"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"n\":91234567,\"m\":7}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/numsub", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0).get("n")).isInstanceOf(String.class);
    assertThat(response.rows().get(0).get("n").toString()).doesNotContain("123456");
    assertThat(response.rows().get(0).get("m")).isEqualTo(7);
  }

  /** 원본 모드 미리보기 요청의 잘못된 dateFormat 은 실행과 같은 설정 오류로 끝난다(WD-947 4차). */
  @Test
  void preview_rawMode_invalidDateFormatMapping_reportsConfigError() {
    wireMock.stubFor(
        get(urlEqualTo("/rawdf"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/rawdf",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "", "t", "TIMESTAMP", "yyyy-MM-dd{", null)),
                null,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotBlank();
    assertThat(response.rows()).isEmpty();
  }

  /** 원본 모드: 잘못된 sourceTimezone 도 설정 오류다. */
  @Test
  void preview_rawMode_invalidSourceTimezoneMapping_reportsConfigError() {
    wireMock.stubFor(
        get(urlEqualTo("/rawtz"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/rawtz",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "", "t", "TIMESTAMP", null, "Not/AZone")),
                null,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotBlank();
  }

  /** 원본 모드: 올바른 dateFormat 이면 원본 행이 그대로 나온다. */
  @Test
  void preview_rawMode_validDateFormatMapping_returnsRawRows() {
    wireMock.stubFor(
        get(urlEqualTo("/rawok"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/rawok",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "", "t", "TIMESTAMP", "yyyy-MM-dd HH:mm:ss", "Asia/Seoul")),
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0)).containsEntry("id", 1);
  }

  /** 매핑 모드(소스 있음)에서도 같은 잘못된 dateFormat 은 같은 설정 오류다. */
  @Test
  void preview_mappedMode_invalidDateFormatMapping_reportsSameConfigError() {
    wireMock.stubFor(
        get(urlEqualTo("/mapdf"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"at\":\"2024-01-01 00:00:00\"}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/mapdf",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(
                    new ApiCallPreviewRequest.FieldMappingPreview(
                        "at", "t", "TIMESTAMP", "yyyy-MM-dd{", null)),
                null,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isNotBlank();
  }

  /** 지수 표기가 될 큰 소수도 평문 숫자 문자열로 비밀번호를 가린다(WD-947 4차). */
  @Test
  void preview_rawRows_largeDecimalContainingPasswordMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "345678"));
    wireMock.stubFor(
        get(urlEqualTo("/bigdec"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"amount\":12345678.9}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/bigdec", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0).get("amount")).isEqualTo("12****.9");
    assertThat(response.rows().toString()).doesNotContain("345678");
  }

  /** 가릴 것 없는 소수는 원래 Number 그대로. */
  @Test
  void preview_rawRows_plainDecimalStaysNumber() {
    wireMock.stubFor(
        get(urlEqualTo("/plaindec"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"v\":1.5}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/plaindec", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.rows().get(0).get("v")).isInstanceOf(Number.class);
    assertThat(((Number) response.rows().get(0).get("v")).doubleValue()).isEqualTo(1.5);
  }

  /** WD-947 3차: 500자 넘는 키는 뺀다(hiddenColumns), 600자리 숫자는 잘린 문자열이 된다. */
  @Test
  void preview_rawRows_overlongKeyHiddenAndHugeNumberTruncated() {
    wireMock.stubFor(
        get(urlEqualTo("/biglimits"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":1,\""
                            + "k".repeat(501)
                            + "\":\"x\",\"big\":"
                            + "7".repeat(600)
                            + "}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/biglimits", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("id", "big");
    assertThat(response.totalColumns()).isEqualTo(2);
    assertThat(response.hiddenColumns()).isEqualTo(1);
    Object big = response.rows().get(0).get("big");
    assertThat(big).isInstanceOf(String.class);
    assertThat(((String) big).length()).isLessThanOrEqualTo(501);
    assertThat((String) big).endsWith("…");
  }

  /** WD-947 3차: 소스 필드가 빈 매핑(TEXT)이 있어도 원본 행이 나온다(검사 통과). */
  @Test
  void preview_rawRows_blankSourceMappingValidStillRaw() {
    wireMock.stubFor(
        get(urlEqualTo("/blanksrc"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/blanksrc",
                "GET",
                null,
                null,
                null,
                "$",
                List.of(new ApiCallPreviewRequest.FieldMappingPreview("", "c", "TEXT", null, null)),
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("id");
  }

  /** 숫자로 온 BASIC 비밀번호 칸도 원본 행에서 가려진다. */
  @Test
  void preview_rawRows_numericPasswordValueMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "123456"));
    wireMock.stubFor(
        get(urlEqualTo("/numpw"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"pin\":123456}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/numpw", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0)).containsEntry("pin", "****").containsEntry("id", 1);
  }

  /** 비밀번호와 같은 열 이름은 원본 행·columns 에서 가려진다. */
  @Test
  void preview_rawRows_passwordKeyMasked() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "admin"));
    wireMock.stubFor(
        get(urlEqualTo("/pwkey"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"admin\":1,\"id\":2}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/pwkey", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    // WD-947 리뷰: 비밀번호를 품은 키는 가려 두지 않고 열·행에서 뺀다(가린 이름 「****」 는 매핑할 수 없다).
    assertThat(response.columns()).containsExactly("id");
    assertThat(response.totalColumns()).isEqualTo(1);
    assertThat(response.rows().get(0).keySet()).containsExactly("id");
  }

  /** WD-947 리뷰: 비밀번호를 부분 문자열로 품은 키(token_비밀번호)는 columns·rows 어디에도 나가지 않는다. */
  @Test
  void preview_rawRows_keyContainingPasswordExcluded() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(
            java.util.Map.of("authType", "BASIC", "username", "bob", "password", "Wd947Secret"));
    wireMock.stubFor(
        get(urlEqualTo("/pwsubkey"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"token_Wd947Secret\":\"x\"}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                "/pwsubkey", "GET", null, null, null, "$", null, 23L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("id");
    assertThat(response.rows().get(0).keySet()).containsExactly("id");
    assertThat(response.totalColumns()).isEqualTo(1);
    assertThat(response.hiddenColumns()).isEqualTo(1);
    assertThat(response.columns().toString()).doesNotContain("Wd947Secret");
    assertThat(response.rows().toString()).doesNotContain("Wd947Secret");
  }

  /** WD-947 리뷰: 본문 가림이 이미 가린 흔적(****)을 품은 키(쿼리 API 키가 든 키)는 열·행에서 뺀다. */
  @Test
  void preview_rawRows_keyContainingMaskedSecretExcluded() {
    wireMock.stubFor(
        get(urlPathEqualTo("/maskedkey"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1,\"k_" + WD177_KEY + "\":\"x\"}]")));

    var response = previewWithQueryAuth(baseUrl() + "/maskedkey", WD177_KEY);

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).containsExactly("id");
    assertThat(response.rows().get(0).keySet()).containsExactly("id");
    assertThat(response.totalColumns()).isEqualTo(1);
    assertThat(response.columns().toString()).doesNotContain("****");
  }

  /** WD-947 리뷰: 객체가 아닌 항목은 행이 되지 않고(value 열 없음) 전체 항목 수에는 센다. */
  @Test
  void preview_rawRows_scalarItemsNotRows() {
    wireMock.stubFor(
        get(urlEqualTo("/mixed"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[1,\"a\",{\"k\":1}]")));
    var mixed =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/mixed", "GET", null, null, null, "$", null, null, null, 5000));
    assertThat(mixed.success()).isTrue();
    assertThat(mixed.totalExtractedRows()).isEqualTo(3);
    assertThat(mixed.rows()).hasSize(1);
    assertThat(mixed.rows().get(0)).containsEntry("k", 1).hasSize(1);
    assertThat(mixed.columns()).containsExactly("k");
    assertThat(mixed.totalColumns()).isEqualTo(1);
    assertThat(mixed.hiddenColumns()).isZero();

    wireMock.stubFor(
        get(urlEqualTo("/scalars"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[1,2]")));
    var scalars =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/scalars", "GET", null, null, null, "$", null, null, null, 5000));
    assertThat(scalars.success()).isTrue();
    assertThat(scalars.totalExtractedRows()).isEqualTo(2);
    assertThat(scalars.rows()).isEmpty();
    assertThat(scalars.columns()).isEmpty();
    assertThat(scalars.totalColumns()).isZero();
  }

  /** WD-947 리뷰: 100열에서 잘라도 전체 열 수(totalColumns)를 알린다. */
  @Test
  void preview_rawRows_columnsCappedReportsTotal() {
    wireMock.stubFor(
        get(urlEqualTo("/manyCols3"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(manyKeysJson())));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/manyCols3", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).hasSize(100);
    assertThat(response.totalColumns()).isEqualTo(150);
    assertThat(response.rows().get(0)).hasSize(100);
  }

  /** 원본 행의 큰 스칼라 문자열 칸도 500자 + 「…」 로 잘린다. */
  @Test
  void preview_rawRows_largeScalarStringTruncated() {
    wireMock.stubFor(
        get(urlEqualTo("/bigScalar"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"blob\":\"" + "x".repeat(600) + "\"}]")));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/bigScalar", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    String blob = (String) response.rows().get(0).get("blob");
    assertThat(blob).hasSize(501).endsWith("…");
  }

  /** 원본 모드 columns 는 처음 나온 순서로 최대 100개다. */
  @Test
  void preview_rawRows_columnsCappedAt100() {
    wireMock.stubFor(
        get(urlEqualTo("/manyCols"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(manyKeysJson())));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/manyCols", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.columns()).hasSize(100);
    assertThat(response.columns().get(0)).isEqualTo("k0");
  }

  /** 열 상한은 미리보기 행에도 적용된다 — 키 150개 항목의 행도 100칸이다. */
  @Test
  void preview_rawRows_columnCapAppliesToRows() {
    wireMock.stubFor(
        get(urlEqualTo("/manyCols2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(manyKeysJson())));

    var response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/manyCols2", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0)).hasSize(100);
  }

  /** 키 150개를 가진 항목 하나의 JSON 배열. */
  private static String manyKeysJson() {
    StringBuilder sb = new StringBuilder("[{");
    for (int i = 0; i < 150; i++) {
      if (i > 0) sb.append(',');
      sb.append("\"k").append(i).append("\":").append(i);
    }
    return sb.append("}]").toString();
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

  /** WD-969: 미리보기는 GET·POST 밖 메서드를 대상에 보내지 않고 안내로 거절한다. */
  @Test
  void preview_putMethod_rejectsWithoutCallingServer() {
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/put-endpoint", "put", null, null, "{}", "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo(ApiCallErrorMessages.unsupportedPreviewMethod("PUT"));
    wireMock.verify(0, anyRequestedFor(urlEqualTo("/put-endpoint")));
  }

  /** WD-969: DELETE 도 같은 안내로 거절한다. */
  @Test
  void preview_deleteMethod_rejectsWithMessage() {
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/del", "DELETE", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo(ApiCallErrorMessages.unsupportedPreviewMethod("DELETE"));
  }

  /** WD-969 리뷰 후속 ②: 메서드 값이 비어 있으면 GET 으로 본다 — «요청한 요청을…» 처럼 이름이 빠진 거절 안내가 뜨지 않는다. */
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void preview_blankMethod_treatedAsGet(String blank) {
    wireMock.stubFor(
        get(urlEqualTo("/blank-method"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"result\":\"ok\"}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/blank-method", blank, null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.errorMessage()).isNull();
    wireMock.verify(getRequestedFor(urlEqualTo("/blank-method")));
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
            WebClient.builder(),
            new SsrfSafeHttpClientFactory(new SsrfProtectionService()));

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
    // WD-150: 실행과 같은 한국어 차단 문구
    assertThat(response.errorMessage()).isEqualTo("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
  }

  // =========================================================================
  // 오류 케이스
  // =========================================================================

  /** 밑줄 든 호스트는 요청 전에 호스트 이름을 담은 고정 문구로 실패한다(WD-676) — 실행과 같은 문장. */
  @Test
  void preview_underscoreHost_failsWithUnsupportedHostMessage() {
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "http://my_api.invalid/x", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo(ApiCallErrorMessages.unsupportedHostName("my_api.invalid"));
  }

  /** 오류: HTTP 서버 연결 실패 시 success=false, errorMessage 반환 */
  @Test
  void preview_connectionRefused_returnsError() throws Exception {
    // 특권 포트 1 — 임시 포트 범위가 아니라 남이 다시 잡을 일이 없어 거부가 확실하다(WD-685: 방금 닫은 포트는 부하 중 재사용돼 간헐 실패)
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "http://127.0.0.1:1/unreachable",
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
    // WD-150: 실행 상세와 같은 문구(예전: 「Connection refused: …」 영어 원문)
    assertThat(response.errorMessage()).isEqualTo(ApiCallErrorMessages.connectionRefused());
    assertThat(response.errorMessage()).isEqualTo("연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요");
  }

  /**
   * WD-150: 4xx 는 상태별 한국어 안내 — 쿼리로 붙인 API 키가 문구에 남지 않는다(WebClientResponseException 메시지는 URL 을 담는다).
   */
  @Test
  void preview_401_withQueryApiKey_koreanMessageWithoutSecret() {
    wireMock.stubFor(get(urlPathEqualTo("/keyed")).willReturn(aResponse().withStatus(401)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/keyed",
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
                    "paramName", "serviceKey",
                    "apiKey", "SECRET123"),
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo("인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요")
        .doesNotContain("SECRET123");
  }

  /** WD-150: 5xx 도 실행과 같은 문구. */
  @Test
  void preview_503_koreanMessage() {
    wireMock.stubFor(get(urlEqualTo("/down")).willReturn(aResponse().withStatus(503)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/down", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.errorMessage()).isEqualTo("대상 서버 오류(HTTP 503)");
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
    // apiConnectionService.getByIdForCall()가 baseUrl을 가진 connection을 반환
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
    when(apiConnectionService.getByIdForCall(1L)).thenReturn(mockConn);
    // BEARER 연결이면 토큰이 있어야 한다 — 빈 비밀 검사는 연결의 auth_type 컬럼 기준이라(WD-639) 목의 기본 빈 Map 이면 호출 전에 막힌다.
    when(apiConnectionService.getDecryptedAuthConfig(1L))
        .thenReturn(java.util.Map.of("authType", "BEARER", "token", "test-token"));

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

  /**
   * (#113) saved API connection + placement=query: getDecryptedAuthConfig 가 authType 을 함께 반환하므로
   * Preview 서비스가 paramName=apiKey 를 URL 에 부착해야 한다. 기존에는 authType 누락으로 query 분기 미진입 → 401 발생.
   */
  @Test
  void preview_savedConnection_apiKeyQueryPlacement_appendsServiceKey() {
    com.smartfirehub.apiconnection.dto.ApiConnectionResponse mockConn =
        new com.smartfirehub.apiconnection.dto.ApiConnectionResponse(
            7L,
            "data.go.kr",
            null,
            "API_KEY",
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
    when(apiConnectionService.getByIdForCall(7L)).thenReturn(mockConn);
    // 핵심: 실제 운영 코드의 getDecryptedAuthConfig 가 authType 을 합쳐 반환하도록 변경됨
    when(apiConnectionService.getDecryptedAuthConfig(7L))
        .thenReturn(
            java.util.Map.of(
                "authType", "API_KEY",
                "placement", "query",
                "paramName", "serviceKey",
                "apiKey", "PROD-SECRET-KEY"));

    wireMock.stubFor(
        get(urlPathEqualTo("/getWthrDataList"))
            .withQueryParam("serviceKey", equalTo("PROD-SECRET-KEY"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"stnId\":108}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "/getWthrDataList", "GET", null, null, null, "$", null, 7L, null, 5000));

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

  /** 직접 URL 의 쿼리에 공백이 있어도 실행기(httpx)처럼 %20 으로 인코딩해 보낸다(WD-936). */
  @Test
  void preview_spaceInDirectUrl_sentEncodedLikeExecutor() {
    wireMock.stubFor(
        get(urlPathEqualTo("/wd936/search"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/wd936/search?q=seoul city",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.resolvedUrl()).contains("q=seoul%20city");
    assertThat(wireMock.getAllServeEvents())
        .extracting(e -> e.getRequest().getUrl())
        .containsExactly("/wd936/search?q=seoul%20city");
  }

  /** 한글 경로 + 쿼리 파라미터 칸의 한글 값은 한 번만 인코딩된다(%25 이중 인코딩 없음, WD-936). */
  @Test
  void preview_koreanPathWithQueryParam_encodedOnce() {
    wireMock.stubFor(
        get(urlPathEqualTo("/wd936/%EA%B2%80%EC%83%89"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/wd936/검색",
                "GET",
                null,
                java.util.Map.of("q", "서울"),
                null,
                "$",
                null,
                null,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(wireMock.getAllServeEvents())
        .extracting(e -> e.getRequest().getUrl())
        .containsExactly("/wd936/%EA%B2%80%EC%83%89?q=%EC%84%9C%EC%9A%B8");
    // 화면·AI 챗에 보이는 주소는 인코딩된 %EA… 가 아니라 읽을 수 있는 한글이다(WD-936 리뷰 후속)
    assertThat(response.resolvedUrl()).endsWith("/wd936/검색?q=서울");
  }

  /** 짝 없는 서로게이트가 든 주소는 %3F 로 바꿔 부르지 않고 URL 을 만들 수 없다고 거절한다(WD-936 리뷰 후속). */
  @Test
  void preview_loneSurrogateInDirectUrl_rejectedWithoutCall() {
    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/wd936/p?q=a\uD800b",
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
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

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
  // WD-177: resolvedUrl·오류 문구에 쿼리 인증 키 원문이 나가지 않는다
  // =========================================================================

  /** 영숫자 키 — 외부 서버에 원문이 실제로 도착하는지 단언용. */
  private static final String WD177_KEY = "PreviewSecret0001234";

  /** 쿼리에 그대로 쓸 수 없는 문자(=·한글·공백)와 +·/ 가 섞인 키. */
  private static final String WD177_SPECIAL_KEY = "pv+k/e==비밀 Qq77";

  private static java.util.Map<String, String> queryAuth(String key) {
    return java.util.Map.of(
        "authType", "API_KEY", "placement", "query", "paramName", "serviceKey", "apiKey", key);
  }

  private ApiCallPreviewResponse previewWithQueryAuth(String url, String key) {
    return service.preview(
        new ApiCallPreviewRequest(
            url, "GET", null, null, null, "$", null, null, queryAuth(key), 2000));
  }

  /** 키의 원문·form 인코딩형·%20 인코딩형이 모두 없는지. */
  private static void assertNoSecret(String text, String key) {
    if (text == null) return;
    String formEncoded = java.net.URLEncoder.encode(key, StandardCharsets.UTF_8);
    assertThat(text)
        .doesNotContain(key)
        .doesNotContain(formEncoded)
        .doesNotContain(formEncoded.replace("+", "%20"));
  }

  @Test
  void preview_queryKey_success_resolvedUrlMasked_butServerReceivesRawKey() {
    wireMock.stubFor(
        get(urlPathEqualTo("/wd177"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"a\":1}]")));

    ApiCallPreviewResponse response = previewWithQueryAuth(baseUrl() + "/wd177", WD177_KEY);

    assertThat(response.success()).isTrue();
    assertThat(response.resolvedUrl()).isEqualTo(baseUrl() + "/wd177?serviceKey=****1234");
    assertNoSecret(response.resolvedUrl(), WD177_KEY);
    // 호출은 원문 키로 나가야 한다(가리기는 응답용만).
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/wd177"))).get(0).getUrl())
        .isEqualTo("/wd177?serviceKey=" + WD177_KEY);
  }

  @Test
  void preview_queryKey_http401_resolvedUrlMaskedAndNoSecretInError() {
    wireMock.stubFor(get(urlPathEqualTo("/wd177")).willReturn(aResponse().withStatus(401)));

    ApiCallPreviewResponse response = previewWithQueryAuth(baseUrl() + "/wd177", WD177_KEY);

    assertThat(response.success()).isFalse();
    assertThat(response.resolvedUrl()).isEqualTo(baseUrl() + "/wd177?serviceKey=****1234");
    assertNoSecret(response.errorMessage(), WD177_KEY);
  }

  @Test
  void preview_queryKey_connectionRefused_resolvedUrlMaskedAndNoSecretInError() throws Exception {
    // 특권 포트 1 — 임시 포트 범위가 아니라 남이 다시 잡을 일이 없어 거부가 확실하다(WD-685: 방금 닫은 포트는 부하 중 재사용돼 간헐 실패)
    String url = "http://127.0.0.1:1/wd177";

    ApiCallPreviewResponse response = previewWithQueryAuth(url, WD177_KEY);

    assertThat(response.success()).isFalse();
    assertThat(response.resolvedUrl()).isEqualTo(url + "?serviceKey=****1234");
    assertNoSecret(response.errorMessage(), WD177_KEY);
  }

  /** 응답이 요청 파라미터(키)를 되울리는 경우 rawJson 에서도 키를 가린다. */
  @Test
  void preview_queryKey_responseEchoingKey_rawJsonMasked() {
    wireMock.stubFor(
        get(urlPathEqualTo("/wd177"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"echo\":\"" + WD177_KEY + "\"}]")));

    ApiCallPreviewResponse response = previewWithQueryAuth(baseUrl() + "/wd177", WD177_KEY);

    assertThat(response.success()).isTrue();
    assertThat(response.rawJson()).contains("****1234");
    assertNoSecret(response.rawJson(), WD177_KEY);
  }

  /**
   * 쿼리에 그대로 쓸 수 없는 문자가 섞인 디코딩 키(WD-182): 예전에는 「URL 인코딩된 값을 넣으세요」로 실패했다(그 전에는 Spring 메시지에 키 원문이 실렸다,
   * WD-177). 이제 공용 규칙으로 한 번 인코딩해 호출하고, resolvedUrl·오류 문구엔 원문·보낸 형태가 없다.
   */
  @Test
  void preview_specialCharKey_encodedOnce_noSecretInErrorOrUrl() {
    stubWd182();

    ApiCallPreviewResponse response = previewWithQueryAuth(baseUrl() + "/wd177", WD177_SPECIAL_KEY);

    assertThat(response.success()).isTrue();
    assertThat(receivedWd177Urls())
        .containsExactly("/wd177?serviceKey=pv%2Bk%2Fe%3D%3D%EB%B9%84%EB%B0%80%20Qq77");
    assertNoSecret(response.errorMessage(), WD177_SPECIAL_KEY);
    assertNoSecret(response.resolvedUrl(), WD177_SPECIAL_KEY);
    assertThat(response.resolvedUrl()).doesNotContain("pv%2Bk%2Fe%3D%3D");
  }

  /**
   * 공공데이터포털이 주는 같은 키의 인코딩 키·디코딩 키(시험용 가짜 키)가 미리보기에서도 「테스트」·실행과 같은 바이트로 나간다(WD-182). 기대값은
   * ApiConnectionTestUrlMaskingTest·ApiCallExecutorTest 와 같다.
   */
  @Test
  void preview_encodedAndDecodedKeys_sameBytes() {
    stubWd182();

    assertThat(previewWithQueryAuth(baseUrl() + "/wd177", "rP1x%2FAbC%2Bd%3D%3D").success())
        .isTrue();
    assertThat(previewWithQueryAuth(baseUrl() + "/wd177", "rP1x/AbC+d==").success()).isTrue();

    assertThat(receivedWd177Urls())
        .containsExactly(
            "/wd177?serviceKey=rP1x%2FAbC%2Bd%3D%3D", "/wd177?serviceKey=rP1x%2FAbC%2Bd%3D%3D");
  }

  /**
   * 정적 쿼리 값에 {@code = 공백 한글} 이 있어도 이제 공용 규칙으로 한 번 인코딩해 호출한다(WD-200) — 예전에는 「URL 인코딩해 넣으세요」로 거부했지만
   * 운영 실행(Python 실행기)은 같은 값을 인코딩해 보냈다. resolvedUrl 엔 보낸 형태가 보이되 한글은 글자로(WD-936 리뷰 후속), 키는 가려진다.
   */
  @Test
  void preview_unencodedStaticParam_encodedOnce_keyStillMasked() {
    stubWd182();

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/wd177",
                "GET",
                null,
                java.util.Map.of("q", "a=b 한글"),
                null,
                "$",
                null,
                null,
                queryAuth(WD177_SPECIAL_KEY),
                2000));

    assertThat(response.success()).isTrue();
    assertThat(receivedWd177Urls())
        .containsExactly(
            "/wd177?q=a%3Db%20%ED%95%9C%EA%B8%80"
                + "&serviceKey=pv%2Bk%2Fe%3D%3D%EB%B9%84%EB%B0%80%20Qq77");
    // 표시용 주소는 한글만 글자로 되돌리고 ASCII 이스케이프(%3D·%20)는 보낸 그대로다(WD-936 리뷰 후속)
    assertThat(response.resolvedUrl()).contains("q=a%3Db%20한글");
    assertNoSecret(response.resolvedUrl(), WD177_SPECIAL_KEY);
    assertThat(response.resolvedUrl()).doesNotContain("pv%2Bk%2Fe%3D%3D");
  }

  /**
   * 같은 정적 토큰의 이미 인코딩된 형태·인코딩 안 한 형태가 미리보기에서 같은 바이트로 나간다(WD-200). 기대값은 executor 의 Python 실행기 테스트·공용
   * 사례 표(query-param-encoding-vectors.json)와 같다 — 예전 Python 실행기는 인코딩된 형태를 {@code %252B} 로 이중 인코딩했다.
   */
  @Test
  void preview_staticParam_encodedAndUnencoded_sameBytes() {
    stubWd182();

    for (String value : java.util.List.of("abc%2Bd%3D%3D", "abc+d==")) {
      assertThat(
              service
                  .preview(
                      new ApiCallPreviewRequest(
                          baseUrl() + "/wd177",
                          "GET",
                          null,
                          java.util.Map.of("token", value),
                          null,
                          "$",
                          null,
                          null,
                          null,
                          2000))
                  .success())
          .isTrue();
    }

    assertThat(receivedWd177Urls())
        .containsExactly("/wd177?token=abc%2Bd%3D%3D", "/wd177?token=abc%2Bd%3D%3D");
  }

  private static void stubWd182() {
    wireMock.stubFor(
        get(urlPathEqualTo("/wd177"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"a\":1}]")));
  }

  /** WireMock 이 /wd177 로 받은 요청 URL(받은 순서, 디코딩하지 않은 원형). */
  private static java.util.List<String> receivedWd177Urls() {
    return wireMock.findAll(getRequestedFor(urlPathEqualTo("/wd177"))).stream()
        .map(com.github.tomakehurst.wiremock.verification.LoggedRequest::getUrl)
        .toList();
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
    // WD-150: 실행과 같은 타임아웃 문구
    assertThat(response.errorMessage()).isEqualTo("응답 시간 초과(0.5초) — 대상 서버 상태나 시간 제한을 확인하세요");
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
    assertThat(response.errorMessage()).isEqualTo("응답에서 데이터 경로를 찾을 수 없습니다: $.nonexistent.path");
  }

  // =========================================================================
  // WD-639: 빈 비밀 검사 — 연결·인라인 인증 모두 호출 전에 막는다
  // =========================================================================

  /** 미리보기용 저장 연결 응답(기준 URL 만 의미 있음). */
  private com.smartfirehub.apiconnection.dto.ApiConnectionResponse connAt(
      Long id, String authType) {
    return new com.smartfirehub.apiconnection.dto.ApiConnectionResponse(
        id,
        "wd639",
        null,
        authType,
        java.util.Map.of(),
        baseUrl(),
        null,
        null,
        null,
        null,
        null,
        1L,
        null,
        null);
  }

  /**
   * 저장 연결의 비밀이 비어 있으면(빈 비밀로 저장된 옛 연결, 「Bearer」 만 있는 토큰) 대상 서버를 부르지 않고 무엇을 채워야 하는지 안내한다. 예전에는
   * 「Authorization: Bearer 」·빈 키 헤더로 호출해 대상의 401/403 만 보였다 — 연결 테스트·상태 점검(WD-623)과 같은 규칙이다.
   */
  @Test
  void preview_savedConnectionWithEmptySecret_failsWithoutCall() {
    wireMock.stubFor(
        get(urlEqualTo("/data")).willReturn(aResponse().withStatus(200).withBody("[]")));
    when(apiConnectionService.getByIdForCall(8L)).thenReturn(connAt(8L, "BEARER"));
    when(apiConnectionService.getDecryptedAuthConfig(8L))
        .thenReturn(java.util.Map.of("authType", "BEARER", "token", "Bearer"));
    when(apiConnectionService.getByIdForCall(9L)).thenReturn(connAt(9L, "API_KEY"));
    when(apiConnectionService.getDecryptedAuthConfig(9L))
        .thenReturn(
            java.util.Map.of("authType", "API_KEY", "placement", "header", "headerName", "X-Key"));

    ApiCallPreviewResponse bearer =
        service.preview(
            new ApiCallPreviewRequest("/data", "GET", null, null, null, "$", null, 8L, null, 5000));
    ApiCallPreviewResponse key =
        service.preview(
            new ApiCallPreviewRequest("/data", "GET", null, null, null, "$", null, 9L, null, 5000));

    assertThat(bearer.success()).isFalse();
    assertThat(bearer.errorMessage())
        .isEqualTo("API 연결 「wd639」의 Bearer 토큰이 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
    assertThat(key.success()).isFalse();
    assertThat(key.errorMessage())
        .isEqualTo("API 연결 「wd639」의 API 키가 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /** 인라인 인증도 같다 — 방식이 정해졌는데 비밀이 비면(정리한 뒤 기준) 호출하지 않는다. */
  @Test
  void preview_inlineAuthWithEmptySecret_failsWithoutCall() {
    wireMock.stubFor(
        get(urlEqualTo("/data")).willReturn(aResponse().withStatus(200).withBody("[]")));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/data",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of("authType", "BEARER", "token", "Bearer   "),
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo("스텝 인증 설정의 Bearer 토큰이 비어 있습니다 — 파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /** 방식이 없거나 모르는 인라인 인증은 검사 대상이 아니다(회귀) — 예전처럼 호출한다. */
  @Test
  void preview_inlineAuthWithoutKnownAuthType_stillCalls() {
    wireMock.stubFor(
        get(urlEqualTo("/data"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    ApiCallPreviewResponse noType =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/data",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of("token", ""),
                5000));
    ApiCallPreviewResponse unknown =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/data",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                java.util.Map.of("authType", "NONE"),
                5000));

    assertThat(noType.success()).isTrue();
    assertThat(unknown.success()).isTrue();
    wireMock.verify(2, getRequestedFor(urlEqualTo("/data")));
  }

  /**
   * 빈 비밀 판정은 연결의 auth_type 컬럼 기준이다 — authConfig JSON 안에 다른 authType 키가 있어도(실제 조회는 컬럼으로 덮는다, WD-647)
   * 컬럼이 BEARER 이고 토큰이 있으면 빈 비밀이 아니다. 호출은 그대로 나간다.
   */
  @Test
  void preview_emptySecretCheckUsesConnectionColumnAuthType() {
    wireMock.stubFor(
        get(urlEqualTo("/data"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
    when(apiConnectionService.getByIdForCall(10L)).thenReturn(connAt(10L, "BEARER"));
    when(apiConnectionService.getDecryptedAuthConfig(10L))
        .thenReturn(java.util.Map.of("authType", "API_KEY", "token", "t"));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "/data", "GET", null, null, null, "$", null, 10L, null, 5000));

    assertThat(response.errorMessage()).isNull();
    wireMock.verify(1, getRequestedFor(urlEqualTo("/data")));
  }

  /**
   * 헤더 이름 없이 저장된 API_KEY 헤더 연결도 연결 테스트·실행과 같은 기본 헤더(X-API-Key)로 키를 보낸다 — 예전에는 헤더를 붙이지 않아 연결 테스트는
   * 통과하고 미리보기는 401 이었다(WD-639).
   */
  @Test
  void preview_apiKeyHeaderConnectionWithoutHeaderName_sendsDefaultHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/data"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
    when(apiConnectionService.getByIdForCall(11L)).thenReturn(connAt(11L, "API_KEY"));
    when(apiConnectionService.getDecryptedAuthConfig(11L))
        .thenReturn(java.util.Map.of("authType", "API_KEY", "apiKey", "wd639-default-key"));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "/data", "GET", null, null, null, "$", null, 11L, null, 5000));

    assertThat(response.success()).isTrue();
    wireMock.verify(
        getRequestedFor(urlEqualTo("/data")).withHeader("X-API-Key", equalTo("wd639-default-key")));
  }

  // =========================================================================
  // WD-647: 대체 키·빈 헤더 이름·빈 비밀 실패의 resolvedUrl
  // =========================================================================

  /** JSON 배열 하나를 돌려주는 스텁. */
  private void stubJsonArray(String path) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
  }

  /** 인라인 인증만 바꾼 GET 미리보기 요청. */
  private ApiCallPreviewRequest inlinePreview(String path, java.util.Map<String, String> auth) {
    return new ApiCallPreviewRequest(
        baseUrl() + path, "GET", null, null, null, "$", null, null, auth, 5000);
  }

  /** 인라인 BEARER 의 value(AI 에이전트 형식)는 빈 비밀이 아니고, 그 값을 실제로 보낸다. */
  @Test
  void preview_inlineBearerValueKey_passesCheckAndSendsToken() {
    stubJsonArray("/v647");

    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview("/v647", java.util.Map.of("authType", "BEARER", "value", "abc")));

    assertThat(response.errorMessage()).isNull();
    wireMock.verify(
        getRequestedFor(urlEqualTo("/v647")).withHeader("Authorization", equalTo("Bearer abc")));
  }

  /** 인라인 API_KEY 의 key_value 는 헤더로, value 는 쿼리로도 보낸다 — 표시 URL 은 키를 가린다. */
  @Test
  void preview_inlineApiKeyAlternateKeys_passCheckAndSendKey() {
    stubJsonArray("/kv647");
    stubJsonArray("/q647");

    ApiCallPreviewResponse header =
        service.preview(
            inlinePreview("/kv647", java.util.Map.of("authType", "API_KEY", "key_value", "kv647")));
    ApiCallPreviewResponse query =
        service.preview(
            inlinePreview(
                "/q647",
                java.util.Map.of(
                    "authType",
                    "API_KEY",
                    "placement",
                    "query",
                    "paramName",
                    "svc",
                    "value",
                    "QueryValue647")));

    assertThat(header.errorMessage()).isNull();
    wireMock.verify(
        getRequestedFor(urlEqualTo("/kv647")).withHeader("X-API-Key", equalTo("kv647")));
    assertThat(query.errorMessage()).isNull();
    wireMock.verify(getRequestedFor(urlEqualTo("/q647?svc=QueryValue647")));
    assertThat(query.resolvedUrl()).doesNotContain("QueryValue647");
  }

  /** 헤더 이름이 빈 문자열이면 기본 헤더(X-API-Key)로 보낸다. */
  @Test
  void preview_blankHeaderName_sendsDefaultHeader() {
    stubJsonArray("/bh647");

    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview(
                "/bh647",
                java.util.Map.of(
                    "authType",
                    "API_KEY",
                    "placement",
                    "header",
                    "headerName",
                    "",
                    "apiKey",
                    "bh-key")));

    assertThat(response.errorMessage()).isNull();
    wireMock.verify(
        getRequestedFor(urlEqualTo("/bh647")).withHeader("X-API-Key", equalTo("bh-key")));
  }

  /** 빈 비밀로 실패해도 다른 실패처럼 요청 URL(키 가림)을 돌려준다 — 예전에는 null 이었다. */
  @Test
  void preview_emptySecretFailure_returnsResolvedUrl() {
    when(apiConnectionService.getByIdForCall(12L)).thenReturn(connAt(12L, "BEARER"));
    when(apiConnectionService.getDecryptedAuthConfig(12L))
        .thenReturn(java.util.Map.of("authType", "BEARER", "token", ""));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                "/data",
                "GET",
                null,
                java.util.Map.of("q", "1"),
                null,
                "$",
                null,
                12L,
                null,
                5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).contains("Bearer 토큰이 비어 있습니다");
    assertThat(response.resolvedUrl()).isEqualTo(baseUrl() + "/data?q=1");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  // =========================================================================
  // WD-647 코드리뷰: 검사 순서·보낼 값 정리·헤더 이름 검사·비밀 가림
  // =========================================================================

  /**
   * 빈 비밀은 URL 조립보다 먼저 본다 — 공백이 든 주소여도 빈 비밀 안내가 나가고 아무것도 호출하지 않는다. 공백은 이제 호출 전에 인코딩되므로(WD-936) 주소는
   * 만들 수 있고 resolvedUrl 은 인코딩된 값이다.
   */
  @Test
  void preview_emptySecretWithSpaceInUrl_reportsEmptySecret() {
    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview("/data?q=a b", java.util.Map.of("authType", "BEARER", "token", "")));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).contains("Bearer 토큰이 비어 있습니다");
    assertThat(response.resolvedUrl()).contains("q=a%20b");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /**
   * 빈 비밀은 URL 조립보다 먼저 본다 — 인코딩 뒤에도 만들 수 없는 주소(쿼리 값 속 제어 문자)여도 빈 비밀 안내가 이기고 resolvedUrl 은 null 이며 아무것도
   * 호출하지 않는다(WD-936 복원).
   */
  @Test
  void preview_emptySecretWithUnbuildableUrl_reportsEmptySecret() {
    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview("/data?q=a\u0001b", java.util.Map.of("authType", "BEARER", "token", "")));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).contains("Bearer 토큰이 비어 있습니다");
    assertThat(response.resolvedUrl()).isNull();
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /** 검사가 정리한 값(「Bearer 」 접두어·앞뒤 공백 뗌)을 그대로 보낸다 — 「Bearer Bearer abc」 가 나가면 안 된다. */
  @Test
  void preview_bearerValueWithPrefix_sendsNormalizedToken() {
    stubJsonArray("/np647");

    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview(
                "/np647", java.util.Map.of("authType", "BEARER", "value", " Bearer abc647 ")));

    assertThat(response.errorMessage()).isNull();
    wireMock.verify(
        getRequestedFor(urlEqualTo("/np647"))
            .withHeader("Authorization", equalTo("Bearer abc647")));
  }

  /** 인라인 key_name 헤더 이름도 저장 연결과 같은 규칙으로 본다 — 예약 헤더(Host)면 부르지 않고 안내한다. */
  @Test
  void preview_reservedInlineHeaderName_failsWithoutCall() {
    stubJsonArray("/hn647");

    ApiCallPreviewResponse response =
        service.preview(
            inlinePreview(
                "/hn647",
                java.util.Map.of("authType", "API_KEY", "key_name", "Host", "key_value", "k647")));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).contains("Host").contains("스텝 인증 설정");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /** 응답이 헤더로 보낸 키·토큰을 되울려도 rawJson 에는 가린 값만 남는다. */
  @Test
  void preview_echoedHeaderSecrets_areScrubbedFromRawJson() {
    wireMock.stubFor(
        get(urlPathEqualTo("/echo647"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"got\":\"EchoToken647xyz EchoKey647xyz\"}]")));

    ApiCallPreviewResponse bearer =
        service.preview(
            inlinePreview(
                "/echo647", java.util.Map.of("authType", "BEARER", "value", "EchoToken647xyz")));
    ApiCallPreviewResponse key =
        service.preview(
            inlinePreview(
                "/echo647", java.util.Map.of("authType", "API_KEY", "key_value", "EchoKey647xyz")));

    assertThat(bearer.rawJson()).doesNotContain("EchoToken647xyz");
    assertThat(key.rawJson()).doesNotContain("EchoKey647xyz");
  }

  // =========================================================================
  // WD-657: 결과 행도 rawJson 과 같은 가린 본문으로 만든다
  // =========================================================================

  /** 필드 매핑 하나(TEXT)를 둔 인라인 인증 미리보기 요청. */
  private ApiCallPreviewRequest mappedPreview(
      String path, String dataPath, String sourceField, Map<String, String> auth) {
    return new ApiCallPreviewRequest(
        baseUrl() + path,
        "GET",
        null,
        null,
        null,
        dataPath,
        List.of(
            new ApiCallPreviewRequest.FieldMappingPreview(sourceField, "v", "TEXT", null, null)),
        null,
        auth,
        5000);
  }

  /**
   * httpbin /headers 처럼 요청 헤더를 되울리는 응답 — dataPath {@code $.headers}·필드 매핑 Authorization 으로 받은 행에도,
   * rawJson 에도 비밀 원문 대신 가린 값만 있다. 예전에는 rawJson 만 가려 행으로 「Bearer <원문>」 이 나갔다.
   */
  @Test
  void preview_echoedAuthorizationHeader_rowsAndRawJsonMasked() {
    wireMock.stubFor(
        get(urlPathEqualTo("/headers657"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"headers\":{\"Authorization\":\"{{request.headers.Authorization}}\"}}")
                    .withTransformers("response-template")));

    ApiCallPreviewResponse response =
        service.preview(
            mappedPreview(
                "/headers657",
                "$.headers",
                "Authorization",
                Map.of("authType", "BEARER", "token", "EchoSecret657abcd")));

    assertThat(response.errorMessage()).isNull();
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0)).containsEntry("v", "Bearer ****abcd");
    assertThat(response.rawJson()).contains("Bearer ****abcd").doesNotContain("EchoSecret657");
    assertThat(response.rows().toString()).doesNotContain("EchoSecret657");
  }

  /** JSON 이스케이프된 형태(슬래시 앞 역슬래시)로 되울려도 파싱하면 원문이 되므로 값 단위로 가린다 — 중첩 배열·객체 안까지. */
  @Test
  void preview_escapedEchoInNestedValues_rowsAndRawJsonMasked() {
    wireMock.stubFor(
        get(urlPathEqualTo("/escaped657"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"v\":{\"deep\":[\"Esc\\/Key657zz\"]}},{\"v\":\"plain\"}]}")));

    ApiCallPreviewResponse response =
        service.preview(
            mappedPreview(
                "/escaped657",
                "$.items",
                "v",
                Map.of("authType", "API_KEY", "key_value", "Esc/Key657zz")));

    assertThat(response.errorMessage()).isNull();
    assertThat(response.rows()).hasSize(2);
    assertThat(response.rows().toString()).doesNotContain("Esc/Key657").contains("****57zz");
    assertThat(response.rawJson()).doesNotContain("Key657").contains("plain");
  }

  /** 따옴표 없는 숫자로 되울린 비밀도 가린다 — 본문 문자열을 바로 바꾸면 JSON 이 깨져 미리보기가 「JSON 이 아닙니다」 로 실패했을 것이다. */
  @Test
  void preview_numericEchoedSecret_maskedWithoutBreakingJson() {
    wireMock.stubFor(
        get(urlPathEqualTo("/num657"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"n\":98765432,\"other\":1}]")));

    ApiCallPreviewResponse response =
        service.preview(
            mappedPreview(
                "/num657", "$", "n", Map.of("authType", "API_KEY", "apiKey", "98765432")));

    assertThat(response.success()).isTrue();
    assertThat(response.rows().get(0)).containsEntry("v", "****5432");
    assertThat(response.rawJson()).doesNotContain("98765432").contains("\"other\":1");
  }

  // ── WD-681: BASIC·baseUrl 사용자 정보 ─────────────────────────────────────────────

  /** 사용자 정보가 든 baseUrl 의 연결(옛 연결)·BASIC 연결 응답 DTO. */
  private com.smartfirehub.apiconnection.dto.ApiConnectionResponse connWithBase(
      Long id, String authType, String baseUrl) {
    return new com.smartfirehub.apiconnection.dto.ApiConnectionResponse(
        id,
        "WD681",
        null,
        authType,
        java.util.Map.of(),
        baseUrl,
        null,
        null,
        null,
        null,
        null,
        1L,
        null,
        null);
  }

  /** BASIC 연결 미리보기는 Basic 헤더를 보내고, echo 응답의 Base64 덩어리·비밀번호는 rawJson·결과 행 어디에도 남지 않는다. */
  @Test
  void preview_basicConnection_sendsBasicHeader_andMasksEcho() {
    String b64 =
        java.util.Base64.getEncoder()
            .encodeToString("alice:Pw681prev".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(apiConnectionService.getByIdForCall(21L))
        .thenReturn(connWithBase(21L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(21L))
        .thenReturn(
            java.util.Map.of("authType", "BASIC", "username", "alice", "password", "Pw681prev"));
    wireMock.stubFor(
        get(urlEqualTo("/echo"))
            .withHeader("Authorization", equalTo("Basic " + b64))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"auth\":\"Basic " + b64 + "\",\"pw\":\"Pw681prev\"}]")));

    var response =
        service.preview(
            new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest(
                "/echo",
                "GET",
                null,
                null,
                null,
                "$",
                java.util.List.of(
                    new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest.FieldMappingPreview(
                        "auth", "auth", "TEXT", null, null),
                    new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest.FieldMappingPreview(
                        "pw", "pw", "TEXT", null, null)),
                21L,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rawJson()).doesNotContain(b64).doesNotContain("Pw681prev");
    assertThat(response.rows().toString()).doesNotContain(b64).doesNotContain("Pw681prev");
  }

  /**
   * 짧은 낱말 비밀번호(admin)도 결과 행을 망가뜨리지 않는다(WD-681 리뷰) — 예전에는 비밀번호를 API 키처럼 본문 전체에서 바꿔 「admin user」 같은
   * 값이 「****dmin user」 가 됐다. 이제 결과 행은 값 전체가 비밀번호인 칸만 가리고, 화면 원문(rawJson)은 SMTP 규칙으로 비밀번호를 지운다.
   */
  @Test
  void preview_basicShortWordPassword_keepsRowsIntact() {
    when(apiConnectionService.getByIdForCall(23L))
        .thenReturn(connWithBase(23L, "BASIC", "http://localhost:" + wireMock.port()));
    when(apiConnectionService.getDecryptedAuthConfig(23L))
        .thenReturn(java.util.Map.of("authType", "BASIC", "username", "bob", "password", "admin"));
    wireMock.stubFor(
        get(urlEqualTo("/roles"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"role\":\"admin user\",\"pw\":\"admin\"}]")));

    var response =
        service.preview(
            new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest(
                "/roles",
                "GET",
                null,
                null,
                null,
                "$",
                java.util.List.of(
                    new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest.FieldMappingPreview(
                        "role", "role", "TEXT", null, null),
                    new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest.FieldMappingPreview(
                        "pw", "pw", "TEXT", null, null)),
                23L,
                null,
                5000));

    assertThat(response.success()).isTrue();
    assertThat(response.rows()).hasSize(1);
    assertThat(response.rows().get(0))
        .containsEntry("role", "admin user")
        .containsEntry("pw", "****");
    assertThat(response.rawJson()).doesNotContain("admin");
  }

  /** 옛 연결(baseUrl 사용자 정보)의 미리보기 — 저장 원문으로 부르되 응답 resolvedUrl 에서는 비밀번호를 가린다. */
  @Test
  void preview_legacyUserInfoBaseUrl_resolvedUrlMasked() {
    String raw = "http://svc:LegacyPrev681@localhost:" + wireMock.port();
    when(apiConnectionService.getByIdForCall(22L)).thenReturn(connWithBase(22L, "BEARER", raw));
    when(apiConnectionService.getDecryptedAuthConfig(22L))
        .thenReturn(java.util.Map.of("authType", "BEARER", "token", "tok681prev"));
    wireMock.stubFor(
        get(urlEqualTo("/v1/legacy"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));

    var response =
        service.preview(
            new com.smartfirehub.pipeline.dto.ApiCallPreviewRequest(
                "/v1/legacy", "GET", null, null, null, "$", null, 22L, null, 5000));

    assertThat(response.success()).isTrue();
    assertThat(response.resolvedUrl())
        .isEqualTo("http://svc:****@localhost:" + wireMock.port() + "/v1/legacy");
  }

  // =========================================================================
  // WD-693: 미리보기도 실행과 같은 리다이렉트 경로(RedirectFollower)를 쓴다
  // =========================================================================

  /** 같은 WireMock 을 다른 출처(호스트가 다름)로 가리키는 주소 — localhost 와 127.0.0.1 은 출처가 다르다. */
  private String otherOrigin() {
    return "http://127.0.0.1:" + wireMock.port();
  }

  /** path 가 GET·POST 어느 쪽이든 행 하나를 주는 스텁. */
  private static void stubOneRowAnyMethod(String path) {
    wireMock.stubFor(
        any(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":693}]")));
  }

  /** method 의 path 가 status 로 location 에 리다이렉트하는 스텁. */
  private static void stubRedirect(String method, String path, int status, String location) {
    wireMock.stubFor(
        request(method, urlPathEqualTo(path))
            .willReturn(aResponse().withStatus(status).withHeader("Location", location)));
  }

  /** POST 미리보기 요청 — 본문·Bearer 인증을 싣는다(리다이렉트 홉에서 본문·인증이 어떻게 되는지 보려고). */
  private ApiCallPreviewResponse previewPost(String url) {
    return service.preview(
        new ApiCallPreviewRequest(
            url,
            "POST",
            null,
            null,
            "{\"k\":\"v693\"}",
            "$",
            null,
            null,
            Map.of("authType", "BEARER", "token", "tok693"),
            5000));
  }

  /**
   * 같은 출처 307 은 실행처럼 POST·본문·인증을 유지해 따라가고, 응답 표시 URL 은 처음 요청 주소다(WD-693 — 예전에는 3xx 본문을 미리보기로 보였다).
   */
  @Test
  void preview_sameOrigin307Post_keepsMethodBodyAndAuth() {
    stubRedirect("POST", "/p693/k1", 307, baseUrl() + "/p693/k2");
    stubOneRowAnyMethod("/p693/k2");

    ApiCallPreviewResponse response = previewPost(baseUrl() + "/p693/k1");

    assertThat(response.success()).isTrue();
    assertThat(response.totalExtractedRows()).isEqualTo(1);
    assertThat(response.resolvedUrl()).isEqualTo(baseUrl() + "/p693/k1");
    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/p693/k2"))
            .withRequestBody(equalTo("{\"k\":\"v693\"}"))
            .withHeader("Authorization", equalTo("Bearer tok693")));
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/p693/k2")));
  }

  /** 301 은 실행처럼 본문·본문 헤더 없는 GET 으로 따라간다(WD-693, 규칙은 RedirectLocations.keepsMethodAndBody). */
  @Test
  void preview_301Post_followsAsGetWithoutBody() {
    stubRedirect("POST", "/p693/m1", 301, baseUrl() + "/p693/m2");
    stubOneRowAnyMethod("/p693/m2");

    ApiCallPreviewResponse response = previewPost(baseUrl() + "/p693/m1");

    assertThat(response.success()).isTrue();
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/p693/m2")));
    List<com.github.tomakehurst.wiremock.verification.LoggedRequest> hops =
        wireMock.findAll(getRequestedFor(urlPathEqualTo("/p693/m2")));
    assertThat(hops).hasSize(1);
    assertThat(hops.get(0).getBodyAsString()).isEmpty();
    assertThat(hops.get(0).containsHeader("Content-Type")).isFalse();
  }

  /**
   * 다른 출처로 가는 307 은 인증 헤더를 떼고(본문도 버린 GET 으로) 따라간다(WD-693 — 실행과 같은 ApiCallAuth.isSameAuthOrigin 판정).
   */
  @Test
  void preview_crossOrigin307_dropsAuthorizationAndBody() {
    stubRedirect("POST", "/p693/x1", 307, otherOrigin() + "/p693/x2");
    stubOneRowAnyMethod("/p693/x2");

    ApiCallPreviewResponse response = previewPost(baseUrl() + "/p693/x1");

    assertThat(response.success()).isTrue();
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/p693/x2")));
    List<com.github.tomakehurst.wiremock.verification.LoggedRequest> hops =
        wireMock.findAll(getRequestedFor(urlPathEqualTo("/p693/x2")));
    assertThat(hops).hasSize(1);
    assertThat(hops.get(0).containsHeader("Authorization")).isFalse();
    assertThat(hops.get(0).getBodyAsString()).isEmpty();
  }

  /**
   * BASIC 인증(WD-681)도 미리보기 리다이렉트 경로(WD-693)에서 Bearer 와 같이 다룬다 — 같은 출처 홉에는 Basic 헤더를 다시 붙이고, 다른 출처
   * 홉에는 떼어 저장된 비밀번호가 다른 출처로 나가지 않는다.
   */
  @Test
  void preview_basicAuth_keptOnSameOriginHop_droppedOnCrossOriginHop() {
    String b64 =
        java.util.Base64.getEncoder()
            .encodeToString("alice:Pw693redir".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    stubRedirect("GET", "/p693/b1", 302, baseUrl() + "/p693/b2");
    stubRedirect("GET", "/p693/b2", 302, otherOrigin() + "/p693/b3");
    stubOneRowAnyMethod("/p693/b3");

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/b1",
                "GET",
                null,
                null,
                null,
                "$",
                null,
                null,
                Map.of("authType", "BASIC", "username", "alice", "password", "Pw693redir"),
                5000));

    assertThat(response.success()).isTrue();
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/p693/b2"))
            .withHeader("Authorization", equalTo("Basic " + b64)));
    List<com.github.tomakehurst.wiremock.verification.LoggedRequest> hops =
        wireMock.findAll(getRequestedFor(urlPathEqualTo("/p693/b3")));
    assertThat(hops).hasSize(1);
    assertThat(hops.get(0).containsHeader("Authorization")).isFalse();
  }

  /** 리다이렉트 대상(Location)이 내부망이면 홉마다 SSRF 검사로 막는다(WD-693) — 처음 주소(WireMock)만 검사를 건너뛴다. */
  @Test
  void preview_redirectToInternalAddress_blockedBySsrfCheck() {
    SsrfProtectionService guardRedirectsOnly =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            if (!url.startsWith(baseUrl())) {
              super.validateUrl(url);
            }
          }
        };
    ApiCallPreviewService guarded =
        new ApiCallPreviewService(
            guardRedirectsOnly,
            new JsonResponseParser(),
            apiConnectionService,
            WebClient.builder(),
            // WireMock(루프백)에 접속해야 하므로 접속 시점 주소 검사(WD-613)는 끈다 — 이 TC 는 홉마다 하는 사전 검사를 본다.
            new SsrfSafeHttpClientFactory(address -> {}));
    stubRedirect("GET", "/p693/s1", 302, "http://127.0.0.1:1/meta");

    ApiCallPreviewResponse response =
        guarded.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/s1", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isEqualTo("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
  }

  /** 리다이렉트가 최대 홉 수(5)를 넘으면 실행과 같은 문구로 실패한다(WD-693). */
  @Test
  void preview_tooManyRedirects_fails() {
    stubRedirect("GET", "/p693/loop", 302, baseUrl() + "/p693/loop");

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/loop", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isEqualTo(ApiCallErrorMessages.tooManyRedirects(5));
  }

  /**
   * 미리보기의 시간 제한은 홉마다가 아니라 리다이렉트 전체에 한 번 건다(WD-693) — 홉마다 걸면 한 번의 미리보기가 (최대 홉 수+1)×timeoutMs 동안
   * 붙잡힌다. 두 홉이 각각 제한보다 짧아도 합이 넘으면 예전과 같은 시간 초과 문구로 실패한다.
   */
  @Test
  void preview_timeoutIsOneDeadlineAcrossRedirectHops() {
    wireMock.stubFor(
        get(urlPathEqualTo("/p693/t1"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", baseUrl() + "/p693/t2")
                    .withFixedDelay(700)));
    wireMock.stubFor(
        get(urlPathEqualTo("/p693/t2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")
                    .withFixedDelay(700)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/t1", "GET", null, null, null, "$", null, null, null, 1000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isEqualTo(ApiCallErrorMessages.timeout(1000));
  }

  /** 리다이렉트 뒤 홉의 DNS 실패는 실패한 그 홉의 호스트를 문구에 쓴다(WD-693 — 예전에는 처음 호스트 localhost 를 보였다). */
  @Test
  void preview_unknownHostOnRedirectHop_namesThatHop() {
    stubRedirect("GET", "/p693/d1", 302, "http://wd693-no-such-host.invalid/x");

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/d1", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage())
        .isEqualTo(ApiCallErrorMessages.unknownHost("wd693-no-such-host.invalid"));
  }

  /** 재시도할 상태(503)는 예전과 같은 「대상 서버 오류(HTTP 503)」 문구다(WD-693 — RetryableApiCallException 만 그대로 쓴다). */
  @Test
  void preview_503_showsTargetServerErrorMessage() {
    wireMock.stubFor(get(urlPathEqualTo("/p693/e1")).willReturn(aResponse().withStatus(503)));

    ApiCallPreviewResponse response =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/p693/e1", "GET", null, null, null, "$", null, null, null, 5000));

    assertThat(response.success()).isFalse();
    assertThat(response.errorMessage()).isEqualTo("대상 서버 오류(HTTP 503)");
  }

  /**
   * Spring 컨텍스트가 닫혀 전역 루프 자원이 폐기·재생성된 뒤에도 미리보기의 접속 시점 SSRF 검사가 동작한다(WD-797).
   *
   * <p>서비스(와 그 팩토리)를 반복 <b>밖에서 한 번</b> 만든다 — 운영의 싱글턴 빈처럼 오래 사는 서비스를 재현하려는 것이다. 예전 미리보기는 생성자에서
   * WebClient 를 한 번 만들어, 그때 넣은 접속 검사 해석기가 첫 자원의 이벤트 루프에 묶였다. 자원이 바뀐 뒤에는 이름 해석이 종료된 루프에 걸려 막지도 접속하지도
   * 못하고 시간 초과로 끝났다. 매 반복에서 서비스를 새로 만들면 그 낡음이 드러나지 않는다.
   *
   * <p>URL 은 IP 리터럴이 아니라 호스트 이름({@code localhost})이다 — IP 리터럴은 검사 해석기가 delegate DNS 를 거치지 않고 바로
   * 검사하므로, 낡은 DNS 해석기 문제를 재현하지 못한다.
   *
   * <p>막는 쪽({@code blocking}): 사전 검사를 끄고 실제 판정의 접속 검사만 둔다 — 루프백이라 접속 검사가 막아야 한다. 허용 쪽({@code
   * allowing}): 접속 검사도 허용해 같은 이름이 실제로 풀려 응답을 받는지 본다. 자원을 세 번 갈아도 두 판정이 매번 같아야 하고, 막힌 요청은 서버에 닿지 않아야
   * 한다.
   */
  @Test
  void preview_keepsSsrfConnectCheckAfterGlobalReactorResourcesAreReplaced() throws Exception {
    wireMock.stubFor(
        get(urlEqualTo("/wd797"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[{\"id\":1}]")));
    String url = baseUrl() + "/wd797";

    // 반복 밖에서 한 번만 만든다 — 오래 사는 서비스(싱글턴 빈) 재현.
    ApiCallPreviewService blocking =
        new ApiCallPreviewService(
            noOpSsrf,
            new JsonResponseParser(),
            apiConnectionService,
            WebClient.builder(),
            new SsrfSafeHttpClientFactory(new SsrfProtectionService()));
    ApiCallPreviewService allowing =
        new ApiCallPreviewService(
            noOpSsrf,
            new JsonResponseParser(),
            apiConnectionService,
            WebClient.builder(),
            new SsrfSafeHttpClientFactory(address -> {}));

    for (int cycle = 0; cycle < 3; cycle++) {
      ReactorResourceFactory resources = new ReactorResourceFactory();
      resources.afterPropertiesSet();
      resources.start();
      try {
        ApiCallPreviewResponse blocked =
            blocking.preview(
                new ApiCallPreviewRequest(
                    url, "GET", null, null, null, "$", null, null, null, 3000));
        assertThat(blocked.success()).as("자원 교체 %d번째 — 막는 쪽은 실패해야 한다", cycle).isFalse();
        assertThat(blocked.errorMessage())
            .as("자원 교체 %d번째 — 시간 초과가 아니라 접속 검사로 막혀야 한다", cycle)
            .startsWith("내부망 주소는 호출할 수 없습니다(루프백): ");

        ApiCallPreviewResponse allowed =
            allowing.preview(
                new ApiCallPreviewRequest(
                    url, "GET", null, null, null, "$", null, null, null, 3000));
        assertThat(allowed.success())
            .as("자원 교체 %d번째 — 허용 쪽은 응답을 받아야 한다: %s", cycle, allowed.errorMessage())
            .isTrue();
      } finally {
        // 컨텍스트 종료와 같은 일 — 전역 루프·연결 풀을 폐기한다(다음 HttpResources.get() 이 새로 만든다).
        // WD-804: 전역(JVM 공용) 자원을 폐기해야만 재현된다 — 버그 경로(HttpClient.create() 의 풀·루프,
        // DefaultDns.current())가
        // 모두 HttpResources.get() 을 보므로 setUseGlobalResources(false) 로는 DefaultDns 교체가 일어나지 않는다.
        // 같은 포크 JVM 의 다른 시험에 미치는 영향: HttpClient.create() 기반 클라이언트는 접속 때 HttpResources.get() 을
        // 다시 봐서 회복한다. 그러나 Boot 자동 구성 WebClient.Builder 커넥터는 시작 때 잡은 루프를 계속 쥐어 회복하지 못한다
        // (캐시된 Spring 컨텍스트의 그 커넥터를 이 시험 뒤에 실제 HTTP 로 쓰면 깨질 수 있는 잠재 위험 — 컨텍스트 캐시 축출·JVM 종료
        // 때와 같은 종류). 별도 JVM 으로 분리하는 시험 인프라 변경은 WD-804 범위 밖이라 하지 않았다.
        resources.stop();
        resources.destroy();
      }
    }
    // 허용 쪽 3번만 서버에 닿고, 막힌 요청은 하나도 닿지 않는다.
    wireMock.verify(3, getRequestedFor(urlEqualTo("/wd797")));
  }

  // =========================================================================
  // WD-970: GET 미리보기의 메서드 변경 헤더·쿼리 차단
  // =========================================================================

  private ApiCallPreviewResponse previewGet(String url, Map<String, String> headers, Long connId) {
    return service.preview(
        new ApiCallPreviewRequest(url, "GET", headers, null, null, "$", null, connId, null, 5000));
  }

  private void assertRejected(ApiCallPreviewResponse r, String name) {
    assertThat(r.success()).isFalse();
    assertThat(r.errorMessage()).contains("메서드를 바꾸는").contains(name);
    assertThat(r.errorMessage()).doesNotContain("DELETE").doesNotContain("s3cr3t");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  private void stubConn(Long id, String authType, String base, Map<String, String> authConfig) {
    when(apiConnectionService.getByIdForCall(id)).thenReturn(connWithBase(id, authType, base));
    when(apiConnectionService.getDecryptedAuthConfig(id)).thenReturn(authConfig);
  }

  @Test
  void preview_get_connectionBaseUrlMethodQuery_rejectsWithoutCall() {
    stubConn(20L, "NONE", baseUrl() + "/v1?_method=DELETE", Map.of("authType", "NONE"));
    assertRejected(previewGet("/data", null, 20L), "_method");
  }

  @Test
  void preview_get_connectionBaseUrlSecondQueryPieceMethod_rejectsWithoutCall() {
    // ; 구분 쿼리는 요청 URL 조립 단계가 먼저 막아 서비스 시험으로는 닿지 않는다 — ; 분리 판정은 MethodOverrideNamesTest 가 덮는다.
    // 여기서는 & 로 나뉜 두 번째 조각의 이름도 잡는지 본다.
    stubConn(21L, "NONE", baseUrl() + "/v1?a=1&_method=DELETE", Map.of("authType", "NONE"));
    assertRejected(previewGet("/data", null, 21L), "_method");
  }

  @Test
  void preview_get_encodedMethodQueryInPath_rejectsWithoutCall() {
    assertRejected(previewGet(baseUrl() + "/x?%5Fmethod=DELETE", null, null), "_method");
  }

  @Test
  void preview_get_connectionApiKeyHeaderNameOverride_rejectsWithoutCall() {
    stubConn(
        22L,
        "API_KEY",
        baseUrl(),
        Map.of(
            "authType", "API_KEY",
            "placement", "header",
            "headerName", "X-HTTP-Method-Override",
            "apiKey", "s3cr3t-DELETE"));
    assertRejected(previewGet("/data", null, 22L), "X-HTTP-Method-Override");
  }

  @Test
  void preview_get_connectionApiKeyQueryParamMethod_rejectsWithoutCall() {
    stubConn(
        23L,
        "API_KEY",
        baseUrl(),
        Map.of(
            "authType", "API_KEY",
            "placement", "query",
            "paramName", "_method",
            "apiKey", "s3cr3t-DELETE"));
    assertRejected(previewGet("/data", null, 23L), "_method");
  }

  @Test
  void preview_get_connectionExtraHeaderOverride_rejectsWithoutCall() {
    stubConn(
        24L,
        "BEARER",
        baseUrl(),
        Map.of(
            "authType", "BEARER",
            "token", "s3cr3t-DELETE",
            "extraHeader:X-Method-Override", "s3cr3t-DELETE"));
    assertRejected(previewGet("/data", null, 24L), "X-Method-Override");
  }

  @Test
  void preview_get_requestHeaderOverride_rejectsWithoutCall() {
    assertRejected(
        previewGet(baseUrl() + "/x", Map.of("X-HTTP-Method", "DELETE"), null), "X-HTTP-Method");
  }

  @Test
  void preview_get_plainMethodParam_stillCalls() {
    wireMock.stubFor(
        get(urlEqualTo("/x?method=list")).willReturn(aResponse().withStatus(200).withBody("[]")));
    ApiCallPreviewResponse r =
        previewGet(baseUrl() + "/x?method=list", Map.of("X-Method-Name", "a"), null);
    assertThat(r.success()).isTrue();
    wireMock.verify(1, anyRequestedFor(anyUrl()));
  }

  @Test
  void preview_get_malformedPercentQueryName_notReportedAsOverride() {
    // 깨진 퍼센트 이름(%zz)은 메서드 변경 이름으로 판정하지 않는다. 홀로 있는 % 는 실행기(httpx)처럼 %25 로 인코딩돼 요청이 그대로
    // 나간다(WD-936 리뷰 후속의 RequestUrlEncoding) — 그래서 거절 문구가 아니라 실제로 한 번 불렸는지로 확인한다(WD-970·WD-936 겹침
    // 풀테스트 수정).
    wireMock.stubFor(
        get(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(200).withBody("[]")));
    ApiCallPreviewResponse r = previewGet(baseUrl() + "/x?%zz=1", null, null);
    assertThat(r.errorMessage() == null ? "" : r.errorMessage()).doesNotContain("메서드를 바꾸는");
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/x")));
  }

  @Test
  void preview_post_withMethodOverride_notRejected() {
    wireMock.stubFor(
        post(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(200).withBody("[]")));
    ApiCallPreviewResponse r =
        service.preview(
            new ApiCallPreviewRequest(
                baseUrl() + "/x?_method=DELETE",
                "POST",
                null,
                null,
                null,
                "$",
                null,
                null,
                null,
                5000));
    assertThat(r.success()).isTrue();
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/x")));
  }

  /** 리다이렉트 Location 으로 돌아온 메서드 변경 쿼리도 GET 미리보기에서는 따라가지 않는다(WD-970). */
  private ApiCallPreviewResponse previewMethod(String method, String path) {
    return service.preview(
        new ApiCallPreviewRequest(
            baseUrl() + path, method, null, null, null, "$", null, null, null, 5000));
  }

  private void stubHopRedirect(String method, String path, int status, String location) {
    wireMock.stubFor(
        request(method, urlPathEqualTo(path))
            .willReturn(aResponse().withStatus(status).withHeader("Location", location)));
  }

  @Test
  void preview_get_redirectToMethodOverrideQuery_rejectsWithoutFollowing() {
    stubHopRedirect("GET", "/redir", 302, "/data?_method=DELETE");
    ApiCallPreviewResponse r = previewMethod("GET", "/redir");
    assertThat(r.success()).isFalse();
    assertThat(r.errorMessage()).contains("메서드를 바꾸는").contains("_method");
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/data")));
  }

  @Test
  void preview_get_redirectWithInjectedTextInName_messageHasOnlyStandardName() {
    // 대상 서버가 Location 이름에 끼운 글(구분 문자로 판정을 피해 간 한글 지시문)은 안내 문구에 실리지 않는다 — 표준 이름만(WD-970 리뷰).
    stubHopRedirect(
        "GET", "/redir", 302, "/data?X-HTTP-Method-Override%20%EC%A7%80%EC%8B%9C%EB%AC%B8=1");
    ApiCallPreviewResponse r = previewMethod("GET", "/redir");
    assertThat(r.success()).isFalse();
    assertThat(r.errorMessage()).contains("메서드를 바꾸는").contains("X-HTTP-Method-Override");
    assertThat(r.errorMessage()).doesNotContain("지시문").doesNotContain("%20");
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/data")));
  }

  @Test
  void preview_get_requestHeaderWithInjectedText_messageHasOnlyStandardName() {
    // 저장 설정·요청 헤더 이름의 원문도 안내에 넣지 않는다.
    ApiCallPreviewResponse r =
        previewGet(baseUrl() + "/x", Map.of("x_http_method 지시문", "DELETE"), null);
    assertThat(r.success()).isFalse();
    assertThat(r.errorMessage()).contains("X-HTTP-Method").doesNotContain("지시문");
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void preview_get_redirectToPlainMethodQuery_follows() {
    stubHopRedirect("GET", "/redir", 302, "/data?method=list");
    wireMock.stubFor(
        get(urlPathEqualTo("/data")).willReturn(aResponse().withStatus(200).withBody("[]")));
    ApiCallPreviewResponse r = previewMethod("GET", "/redir");
    assertThat(r.success()).isTrue();
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/data")));
  }

  @Test
  void preview_post_303ToMethodOverrideQuery_rejectsBecauseNextHopIsGet() {
    stubHopRedirect("POST", "/p", 303, "/data?_method=DELETE");
    ApiCallPreviewResponse r = previewMethod("POST", "/p");
    assertThat(r.success()).isFalse();
    assertThat(r.errorMessage()).contains("메서드를 바꾸는").contains("_method");
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/data")));
  }

  @Test
  void preview_post_307ToMethodOverrideQuery_keepsPostAndFollows() {
    // 307 은 POST 를 유지한다 — POST 미리보기는 메서드 변경 검사 범위 밖이라 막지 않는다.
    stubHopRedirect("POST", "/p", 307, "/data?_method=DELETE");
    wireMock.stubFor(
        post(urlPathEqualTo("/data")).willReturn(aResponse().withStatus(200).withBody("[]")));
    ApiCallPreviewResponse r = previewMethod("POST", "/p");
    assertThat(r.success()).isTrue();
    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/data")));
  }
}
