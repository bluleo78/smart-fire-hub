package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** API_CALL 공용 인증 규칙(WD-639) — 빈 비밀 판정 기준·운영자용 문구·API_KEY 기본 헤더. */
class ApiCallAuthTest {

  private static ApiConnectionResponse conn(String authType) {
    return new ApiConnectionResponse(
        1L,
        "공공 API",
        null,
        authType,
        Map.of(),
        "https://a.example.com",
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
   * 저장 연결은 연결의 auth_type 컬럼으로 판정한다 — JSON 안의 authType 키(API_KEY)가 아니라 컬럼(BEARER) 기준이라 토큰이 있으면 문제없다.
   */
  @Test
  void connection_usesColumnAuthType_notJsonAuthTypeKey() {
    assertThat(
            ApiCallAuth.describeCallProblem(
                conn("BEARER"), Map.of("authType", "API_KEY", "token", "t")))
        .isNull();
    // 반대로 JSON 이 BEARER·토큰 있음이어도 컬럼이 API_KEY 면 키가 비었다
    assertThat(
            ApiCallAuth.describeCallProblem(
                conn("API_KEY"), Map.of("authType", "BEARER", "token", "t")))
        .isEqualTo("API 연결 「공공 API」의 API 키가 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
  }

  /** 운영자용 문구 — 입력 칸 안내(「… 입력하세요」·authConfig 필드 경로)가 아니라 어디서 고치는지를 알린다. */
  @Test
  void messages_pointToWhereToFix_withoutFormFieldPath() {
    String connMessage =
        ApiCallAuth.describeCallProblem(conn("BEARER"), Map.of("token", "Bearer "));
    String inlineMessage =
        ApiCallAuth.describeCallProblem(null, Map.of("authType", "BEARER", "token", ""));

    assertThat(connMessage)
        .isEqualTo("API 연결 「공공 API」의 Bearer 토큰이 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
    assertThat(inlineMessage)
        .isEqualTo("스텝 인증 설정의 Bearer 토큰이 비어 있습니다 — 파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.");
    assertThat(connMessage + inlineMessage).doesNotContain("authConfig").doesNotContain("입력하세요");
    // 저장 연결인데 authConfig 가 통째로 없어도 빈 비밀이다
    assertThat(ApiCallAuth.describeCallProblem(conn("BEARER"), null)).isNotNull();
  }

  /** 방식이 없거나 모르는 인라인 인증, 인증 없음은 검사하지 않는다(예전 동작 유지). */
  @Test
  void notChecked_whenNoKnownAuthType() {
    assertThat(ApiCallAuth.describeCallProblem(null, null)).isNull();
    assertThat(ApiCallAuth.describeCallProblem(null, Map.of("token", ""))).isNull();
    assertThat(ApiCallAuth.describeCallProblem(null, Map.of("authType", "NONE"))).isNull();
    // 쿼리 인증이면 헤더 이름은 보지 않는다
    assertThat(
            ApiCallAuth.describeCallProblem(
                conn("API_KEY"),
                Map.of("placement", "query", "headerName", "X Bad", "apiKey", "k")))
        .isNull();
  }

  /**
   * WD-647 코드리뷰 — API_KEY 헤더 이름은 저장 연결과 같은 규칙(형식·예약 헤더)으로 호출 전에 본다. 인라인 key_name 은 저장 검사를 거치지 않아
   * Host 같은 예약 헤더가 그대로 나갈 수 있었다. 빈 비밀이 헤더 이름보다 먼저다.
   */
  @Test
  void headerName_validatedLikeSavedConnection() {
    assertThat(
            ApiCallAuth.describeCallProblem(
                null, Map.of("authType", "API_KEY", "key_name", "Host", "key_value", "k")))
        .startsWith("스텝 인증 설정의 API 키 헤더 이름을 쓸 수 없습니다(")
        .contains("Host")
        .endsWith("파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.");
    assertThat(
            ApiCallAuth.describeCallProblem(
                conn("API_KEY"), Map.of("headerName", "X Bad", "apiKey", "k")))
        .startsWith("API 연결 「공공 API」의 API 키 헤더 이름을 쓸 수 없습니다(")
        .endsWith("API 연결 화면에서 인증 정보를 저장하세요.");
    assertThat(
            ApiCallAuth.describeCallProblem(
                null, Map.of("authType", "API_KEY", "key_name", "Host", "key_value", "")))
        .contains("API 키가 비어 있습니다");
  }

  /** WD-647 코드리뷰 — 보낼 Bearer 토큰은 검사와 같은 정리를 거친 값이다(「Bearer 」 접두어·앞뒤 공백 뗌). */
  @Test
  void resolve_bearerSecretIsNormalized() {
    assertThat(ApiCallAuth.resolve(Map.of("authType", "BEARER", "value", " Bearer abc ")).secret())
        .isEqualTo("abc");
    assertThat(ApiCallAuth.resolve(Map.of("authType", "BEARER", "token", "Bearer")).secret())
        .isEmpty();
    // API 키는 원문 그대로(Python 과 같다)
    assertThat(ApiCallAuth.resolve(Map.of("authType", "API_KEY", "apiKey", " k ")).secret())
        .isEqualTo(" k ");
  }

  /**
   * WD-647 코드리뷰 — 방식은 연결 컬럼이 있으면 컬럼, 없으면(NULL) 설정 안의 authType 이다. 예전에는 컬럼이 NULL 이면 검사를 건너뛰는데 전송은
   * JSON 방식으로 「Authorization: Bearer 」 를 보낼 수 있었다.
   */
  @Test
  void connectionWithNullColumn_usesJsonAuthTypeForCheckAndSend() {
    Map<String, String> json = Map.of("authType", "BEARER", "token", "");
    assertThat(ApiCallAuth.describeCallProblem(conn(null), json)).contains("Bearer 토큰이 비어 있습니다");
    assertThat(ApiCallAuth.resolve(conn(null), json).authType()).isEqualTo("BEARER");
    // 컬럼이 있으면 컬럼이 이긴다
    assertThat(ApiCallAuth.resolve(conn("API_KEY"), json).authType()).isEqualTo("API_KEY");
  }

  /** 헤더 이름이 없으면 연결 테스트·Python 실행기와 같은 기본 헤더(X-API-Key)다. */
  @Test
  void resolve_headerNameDefaultsToXApiKey() {
    assertThat(ApiCallAuth.resolve(Map.of("apiKey", "k")).headerName()).isEqualTo("X-API-Key");
    assertThat(ApiCallAuth.resolve(Map.of("headerName", "X-Custom")).headerName())
        .isEqualTo("X-Custom");
  }

  // ── WD-647: Python 실행기(_apply_auth)와 같은 대체 키·빈 이름 규칙 ─────────────────────────────

  /**
   * 헤더 이름이 빈 문자열·공백이면 없는 것으로 보고 key_name, 그다음 기본 헤더(X-API-Key)를 쓴다. 예전에는 getOrDefault 가 키가 없을 때만
   * 기본값을 줘 「""」 이름의 헤더를 보내려 했다.
   */
  @Test
  void resolve_blankHeaderNameFallsBackToKeyNameThenDefault() {
    assertThat(ApiCallAuth.resolve(Map.of("headerName", "")).headerName()).isEqualTo("X-API-Key");
    assertThat(ApiCallAuth.resolve(Map.of("headerName", "  ")).headerName()).isEqualTo("X-API-Key");
    assertThat(ApiCallAuth.resolve(Map.of("headerName", "", "key_name", "X-Alt")).headerName())
        .isEqualTo("X-Alt");
  }

  /**
   * 인라인 인증의 비밀은 Python 실행기와 같은 순서로 찾는다 — BEARER: token → value, API_KEY: apiKey → key_value →
   * value. AI 에이전트가 {@code value} 로 저장한 파이프라인이 빈 비밀로 막히면 안 된다.
   */
  @Test
  void inlineAlternateSecretKeys_areNotMissing() {
    assertThat(ApiCallAuth.describeCallProblem(null, Map.of("authType", "BEARER", "value", "abc")))
        .isNull();
    assertThat(
            ApiCallAuth.describeCallProblem(null, Map.of("authType", "API_KEY", "key_value", "k")))
        .isNull();
    assertThat(ApiCallAuth.describeCallProblem(null, Map.of("authType", "API_KEY", "value", "k")))
        .isNull();
    // type·소문자 방식도 Python 처럼 받는다
    assertThat(ApiCallAuth.describeCallProblem(null, Map.of("type", "bearer", "token", "")))
        .isNotNull();
  }

  /** 우선순위는 Python {@code or} 와 같다 — null·"" 만 건너뛰고, 공백 값은 그대로 골라 빈 비밀로 판정한다. */
  @Test
  void resolve_secretPrecedenceMatchesPython() {
    assertThat(
            ApiCallAuth.resolve(Map.of("authType", "API_KEY", "apiKey", "", "value", "v")).secret())
        .isEqualTo("v");
    assertThat(
            ApiCallAuth.resolve(
                    Map.of("authType", "API_KEY", "apiKey", "a", "key_value", "kv", "value", "v"))
                .secret())
        .isEqualTo("a");
    assertThat(
            ApiCallAuth.resolve(Map.of("authType", "API_KEY", "key_value", "kv", "value", "v"))
                .secret())
        .isEqualTo("kv");
    assertThat(
            ApiCallAuth.resolve(Map.of("authType", "BEARER", "token", "t", "value", "v")).secret())
        .isEqualTo("t");
    assertThat(
            ApiCallAuth.describeCallProblem(
                null, Map.of("authType", "API_KEY", "apiKey", " ", "value", "v")))
        .isNotNull();
  }

  /** 쿼리 인증: placement → location(대소문자 무관), paramName → key_name → api_key. */
  @Test
  void resolve_queryPlacementAndParamNameAlternates() {
    ApiCallAuth.ResolvedAuth r =
        ApiCallAuth.resolve(Map.of("type", "api_key", "location", "Query", "key_value", "k"));
    assertThat(r.isQueryApiKey()).isTrue();
    assertThat(r.paramName()).isEqualTo("api_key");
    assertThat(
            ApiCallAuth.resolve(
                    Map.of("authType", "API_KEY", "placement", "query", "key_name", "svc"))
                .paramName())
        .isEqualTo("svc");
  }
}
