package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * WD-150: API 호출 오류 문구 헬퍼. 문자열은 Python 실행기 {@code app/services/api_call_errors.py} 와 같아야 한다 — 같은
 * 문장을 {@code tests/test_api_call_errors.py} 가 단언한다(한쪽만 바꾸면 이 테스트나 그쪽이 깨진다).
 */
class ApiCallErrorMessagesTest {

  /** 재시도 판정: 5xx·408·429 만 재시도, 나머지 4xx 는 즉시 실패. */
  @ParameterizedTest
  @CsvSource({
    "400,false",
    "401,false",
    "403,false",
    "404,false",
    "405,false",
    "409,false",
    "422,false",
    "408,true",
    "429,true",
    "500,true",
    "502,true",
    "503,true",
    "504,true"
  })
  void isRetryableStatus(int status, boolean expected) {
    assertThat(ApiCallErrorMessages.isRetryableStatus(status)).isEqualTo(expected);
  }

  /** Python 쪽과 같은 문자열 — 대표 사례를 그대로 못 박는다. */
  @Test
  void sharedLiterals_matchPythonExecutor() {
    assertThat(ApiCallErrorMessages.httpStatus(401))
        .isEqualTo("인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요");
    assertThat(ApiCallErrorMessages.httpStatus(405))
        .isEqualTo("허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요");
    assertThat(ApiCallErrorMessages.httpStatus(418)).isEqualTo("요청이 거부되었습니다(HTTP 418)");
    assertThat(ApiCallErrorMessages.httpStatus(503)).isEqualTo("대상 서버 오류(HTTP 503)");
    assertThat(ApiCallErrorMessages.timeout(30_000))
        .isEqualTo("응답 시간 초과(30초) — 대상 서버 상태나 시간 제한을 확인하세요");
    assertThat(ApiCallErrorMessages.retriesExhausted(3, ApiCallErrorMessages.connectionRefused()))
        .isEqualTo("API 호출이 3회 재시도 후에도 실패했습니다 — 마지막 오류: 연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요");
    assertThat(ApiCallErrorMessages.retriesExhausted(0, "대상 서버 오류(HTTP 500)"))
        .isEqualTo("API 호출에 실패했습니다: 대상 서버 오류(HTTP 500)");
    assertThat(ApiCallErrorMessages.responseTooLarge(10))
        .isEqualTo("응답 크기가 제한(10MB)을 넘었습니다 — 응답 크기 제한을 늘리거나 페이지 크기를 줄이세요");
    assertThat(ApiCallErrorMessages.tooManyRedirects(5)).isEqualTo("리다이렉트가 너무 많습니다(최대 5회)");
    // WD-212: 데이터 경로 문구 — Python 실행기 test_api_call_errors.py 가 같은 문자열을 단언한다.
    assertThat(ApiCallErrorMessages.dataPathNotFound("$.data.items"))
        .isEqualTo("응답에서 데이터 경로를 찾을 수 없습니다: $.data.items");
    assertThat(ApiCallErrorMessages.dataPathInvalid("$.data["))
        .isEqualTo("데이터 경로(JSONPath) 형식이 올바르지 않습니다: $.data[ — 예: $.data.items");
  }

  @ParameterizedTest
  @CsvSource({"30000,30", "300,0.3", "1500,1.5", "1000,1", "50,0.05"})
  void seconds_format(int ms, String expected) {
    assertThat(ApiCallErrorMessages.seconds(ms)).isEqualTo(expected);
  }

  /** 문구에 넣는 URL 은 쿼리·프래그먼트·userinfo 를 버린다(API_KEY query 인증의 키 보호). */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "https://api.example.com/v1/data?serviceKey=SECRET123|https://api.example.com/v1/data",
        "http://user:pass@host.example:8080/p?x=1#frag|http://host.example:8080/p",
        "https://api.example.com|https://api.example.com",
        "http://exa mple.com/a?serviceKey=SECRET123|http://exa mple.com/a",
        "example.com/x?k=SECRET123|example.com/x",
      })
  void redactUrl_dropsQueryAndUserinfo(String input, String expected) {
    assertThat(ApiCallErrorMessages.redactUrl(input)).isEqualTo(expected);
  }

  /** 원인 사슬 분류 — 타임아웃이 연결 오류보다 먼저(netty 연결 타임아웃은 ConnectException 하위다). */
  @Test
  void describe_classifiesCauseChain() {
    RuntimeException wrappedTimeout =
        new RuntimeException("x", new TimeoutException("Did not observe"));
    assertThat(ApiCallErrorMessages.describe(wrappedTimeout, 2000, "h"))
        .isEqualTo(ApiCallErrorMessages.timeout(2000));

    RuntimeException connectTimeout =
        new RuntimeException(new io.netty.channel.ConnectTimeoutException("connection timed out"));
    assertThat(ApiCallErrorMessages.describe(connectTimeout, 1000, "h"))
        .isEqualTo(ApiCallErrorMessages.timeout(1000));

    RuntimeException refused =
        new RuntimeException(new ConnectException("Connection refused: localhost/127.0.0.1:1"));
    assertThat(ApiCallErrorMessages.describe(refused, 1000, "h"))
        .isEqualTo(ApiCallErrorMessages.connectionRefused());

    RuntimeException dns = new RuntimeException(new UnknownHostException("nope.invalid"));
    assertThat(ApiCallErrorMessages.describe(dns, 1000, "nope.invalid"))
        .isEqualTo("호스트 이름을 찾을 수 없습니다: nope.invalid");

    // 분류 못 한 원문도 URL 의 쿼리는 지운다
    RuntimeException other =
        new RuntimeException(
            new IllegalStateException("bad thing at https://a.example/p?serviceKey=SECRET123 now"));
    assertThat(ApiCallErrorMessages.describe(other, 1000, "h"))
        .isEqualTo("API 호출 중 오류가 발생했습니다: bad thing at https://a.example/p now");
  }
}
