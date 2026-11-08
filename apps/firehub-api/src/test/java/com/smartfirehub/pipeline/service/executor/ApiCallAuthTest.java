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
            ApiCallAuth.describeMissingSecret(
                conn("BEARER"), Map.of("authType", "API_KEY", "token", "t")))
        .isNull();
    // 반대로 JSON 이 BEARER·토큰 있음이어도 컬럼이 API_KEY 면 키가 비었다
    assertThat(
            ApiCallAuth.describeMissingSecret(
                conn("API_KEY"), Map.of("authType", "BEARER", "token", "t")))
        .isEqualTo("API 연결 「공공 API」의 API 키가 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
  }

  /** 운영자용 문구 — 입력 칸 안내(「… 입력하세요」·authConfig 필드 경로)가 아니라 어디서 고치는지를 알린다. */
  @Test
  void messages_pointToWhereToFix_withoutFormFieldPath() {
    String connMessage =
        ApiCallAuth.describeMissingSecret(conn("BEARER"), Map.of("token", "Bearer "));
    String inlineMessage =
        ApiCallAuth.describeMissingSecret(null, Map.of("authType", "BEARER", "token", ""));

    assertThat(connMessage)
        .isEqualTo("API 연결 「공공 API」의 Bearer 토큰이 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.");
    assertThat(inlineMessage)
        .isEqualTo("스텝 인증 설정의 Bearer 토큰이 비어 있습니다 — 파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.");
    assertThat(connMessage + inlineMessage).doesNotContain("authConfig").doesNotContain("입력하세요");
    // 저장 연결인데 authConfig 가 통째로 없어도 빈 비밀이다
    assertThat(ApiCallAuth.describeMissingSecret(conn("BEARER"), null)).isNotNull();
  }

  /** 방식이 없거나 모르는 인라인 인증, 인증 없음, 다른 필드 문제는 검사하지 않는다(예전 동작 유지). */
  @Test
  void notChecked_whenNoKnownAuthTypeOrOnlyOtherFieldProblems() {
    assertThat(ApiCallAuth.describeMissingSecret(null, null)).isNull();
    assertThat(ApiCallAuth.describeMissingSecret(null, Map.of("token", ""))).isNull();
    assertThat(ApiCallAuth.describeMissingSecret(null, Map.of("authType", "NONE"))).isNull();
    assertThat(
            ApiCallAuth.describeMissingSecret(
                conn("API_KEY"), Map.of("headerName", "X Bad", "apiKey", "k")))
        .isNull();
  }

  /** 헤더 이름이 없으면 연결 테스트·Python 실행기와 같은 기본 헤더(X-API-Key)다. */
  @Test
  void apiKeyHeaderName_defaultsToXApiKey() {
    assertThat(ApiCallAuth.apiKeyHeaderName(Map.of("apiKey", "k"))).isEqualTo("X-API-Key");
    assertThat(ApiCallAuth.apiKeyHeaderName(Map.of("headerName", "X-Custom")))
        .isEqualTo("X-Custom");
  }
}
