package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * {@link RedirectLocations} 단위 테스트(WD-676). 정상 해석 결과는 Python 과 함께 쓰는 공용 사례 표({@code
 * ApiCallAuthVectorsTest})가 보고, 여기서는 표에 담기 어려운 실패 문구와 점 구간 제거만 본다.
 */
class RedirectLocationsTest {

  /** 문법 오류 Location(공백)은 재시도하지 않는 ApiCallException·고정 문구 — URL 은 싣지 않는다. */
  @Test
  void resolve_invalidLocation_throwsNonRetryableFixedMessage() {
    // 공백·한글은 이제 인코딩해 따라가므로(WD-991) 제어 문자(U+0001)로 문법 오류를 지킨다.
    assertThatThrownBy(
            () ->
                RedirectLocations.resolve(
                    URI.create("https://api.example.com/v1"), "/next\u0001page?serviceKey=SECRET"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation())
        .hasMessageNotContaining("SECRET");
  }

  /**
   * 공백·한글이 든 Location 은 실행기(urljoin+httpx)처럼 인코딩해 따라간다(WD-991) — 한 번만 인코딩하고 이미 있는 %XX 는 그대로 둔다.
   *
   * <p>공용 사례 표(api-call-auth-vectors.json 의 redirectLocation)에는 한글·공백 사례를 넣지 않는다: Python resolver 는
   * 인코딩 전 문자열을 돌려주고 httpx 가 보낼 때 인코딩하므로 표의 기대값이 두 쪽에서 갈린다. 그래서 Java 쪽 단위 시험으로만 지킨다.
   */
  @Test
  void resolve_koreanSpaceLocation_encoded() {
    URI base = URI.create("https://api.example.com/a/b");
    assertThat(RedirectLocations.resolve(base, "/검색 결과?q=서울"))
        .hasToString(
            "https://api.example.com/%EA%B2%80%EC%83%89%20%EA%B2%B0%EA%B3%BC?q=%EC%84%9C%EC%9A%B8");
    assertThat(RedirectLocations.resolve(base, "결과 2"))
        .hasToString("https://api.example.com/a/%EA%B2%B0%EA%B3%BC%202");
    assertThat(RedirectLocations.resolve(base, "https://x.com/가 나"))
        .hasToString("https://x.com/%EA%B0%80%20%EB%82%98");
    assertThat(RedirectLocations.resolve(base, "//x.com/가")).hasToString("https://x.com/%EA%B0%80");
    assertThat(RedirectLocations.resolve(base, "/%EA%B2%80"))
        .hasToString("https://api.example.com/%EA%B2%80");
  }

  /**
   * 앞쪽 공백·제어 문자(U+0000–U+0020)만 떼고, 탭·CR·LF 는 어디에 있든 지운다 — Python urlsplit 과 같다(WD-1006). 끝의 공백은 떼지
   * 않고 %20 으로 인코딩한다.
   */
  @Test
  void resolve_whitespace_matchesUrlsplit() {
    URI base = URI.create("https://api.example.com/v1");
    assertThat(RedirectLocations.resolve(base, " //cdn.example.com/data"))
        .hasToString("https://cdn.example.com/data");
    assertThat(RedirectLocations.resolve(base, "/next \t\n"))
        .hasToString("https://api.example.com/next%20");
    assertThat(RedirectLocations.resolve(base, "/next\tpage"))
        .hasToString("https://api.example.com/nextpage");
    assertThat(RedirectLocations.resolve(base, "/a\r\nb?x=1\t"))
        .hasToString("https://api.example.com/ab?x=1");
    assertThat(RedirectLocations.resolve(base, "\thttps://cdn.example.com/x"))
        .hasToString("https://cdn.example.com/x");
    assertThat(RedirectLocations.resolve(base, " \t")).hasToString("https://api.example.com/v1");
  }

  /** 탭·CR·LF 를 뺀 그 밖의 제어 문자는 끝에 있어도 안쪽에 있어도 거절한다(WD-1006). */
  @Test
  void resolve_otherControlChars_rejected() {
    URI base = URI.create("https://api.example.com/v1");
    assertThatThrownBy(() -> RedirectLocations.resolve(base, "/next\u0001"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
    assertThatThrownBy(() -> RedirectLocations.resolve(base, "/next\u0001page"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
  }

  /**
   * Python urljoin 은 Location 스킴이 base 와 다르면 원문을 그대로 돌려주고 httpx 가 제어 문자를 거절한다 — 같은 스킴·상대 Location 만
   * 탭·CR·LF 를 지우고 앞쪽을 떼며, 스킴이 다르면 거절한다(WD-1006).
   */
  @Test
  void resolve_differentScheme_notCleaned() {
    URI http = URI.create("http://api.example.com/v1");
    assertThatThrownBy(() -> RedirectLocations.resolve(http, "https://x.com/p\tq"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
    assertThatThrownBy(() -> RedirectLocations.resolve(http, "\u0001https://x.com/p"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
    assertThatThrownBy(() -> RedirectLocations.resolve(http, " https://x.com/p"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
  }

  /** 스킴이 달라도 Location 에 「#」 이 있으면 Python urldefrag 가 다시 파싱하며 정리하므로 정리해서 따라간다(WD-1006). */
  @Test
  void resolve_differentSchemeWithFragment_cleaned() {
    URI http = URI.create("http://api.example.com/v1");
    assertThat(RedirectLocations.resolve(http, "https://x.com/p\tq#f"))
        .hasToString("https://x.com/pq");
    assertThat(RedirectLocations.resolve(http, " https://x.com/p#f"))
        .hasToString("https://x.com/p");
    assertThat(RedirectLocations.resolve(http, "\u0001https://x.com/p#f"))
        .hasToString("https://x.com/p");
  }

  /** 같은 스킴(대소문자 무시)이면 탭을 지우고 따라간다(WD-1006). */
  @Test
  void resolve_sameScheme_cleaned() {
    URI https = URI.create("https://api.example.com/v1");
    assertThat(RedirectLocations.resolve(https, "https://x.com/p\tq"))
        .hasToString("https://x.com/pq");
    assertThat(RedirectLocations.resolve(https, "HTTPS://x.com/p\tq"))
        .hasToString("https://x.com/pq");
  }

  /** RFC 3986 §5.2.4 예시 — 루트 위로 올라가는 「..」 는 버린다. */
  @Test
  void removeDotSegments_rfcExamples() {
    assertThat(RedirectLocations.removeDotSegments("/a/b/c/./../../g")).isEqualTo("/a/g");
    assertThat(RedirectLocations.removeDotSegments("mid/content=5/../6")).isEqualTo("mid/6");
    assertThat(RedirectLocations.removeDotSegments("/../../x")).isEqualTo("/x");
    assertThat(RedirectLocations.removeDotSegments("/a/..")).isEqualTo("/");
  }

  /**
   * Location 의 프래그먼트는 떼고 따라간다(WD-686) — 요청 줄에는 의미가 없고, 로그·SSRF 검사·출처 판정에 섞이지 않게 한다. base 의 프래그먼트도
   * 물려받지 않는다.
   */
  @Test
  void resolve_dropsFragment() {
    URI base = URI.create("https://api.example.com/v1/items?k=v#old");
    assertThat(RedirectLocations.resolve(base, "/v2?page=2#frag"))
        .hasToString("https://api.example.com/v2?page=2");
    assertThat(RedirectLocations.resolve(base, "#only"))
        .hasToString("https://api.example.com/v1/items?k=v");
    assertThat(RedirectLocations.resolve(base, "https://other.example/p#x").getRawFragment())
        .isNull();
    // 떼는 곳은 resolve() 한 곳이다 — 문자열 해석 자체는 RFC 3986 대로 참조의 프래그먼트를 붙인다.
    assertThat(RedirectLocations.resolveString(base.toString(), "/v2#frag"))
        .isEqualTo("https://api.example.com/v2#frag");
  }

  /**
   * 프래그먼트는 문법 검사보다 먼저 뗀다(WD-686) — 따라갈 주소와 상관없는 프래그먼트의 문법 오류(「#」 두 번·공백)로 Java 만 실패하고 Python
   * (urldefrag)은 따라가던 차이를 없앤다. 프래그먼트 앞부분의 문법 오류는 지금처럼 고정 문구로 실패한다.
   */
  @Test
  void resolve_malformedFragment_isDroppedBeforeSyntaxCheck() {
    URI base = URI.create("https://api.example.com/v1/items");
    assertThat(RedirectLocations.resolve(base, "/v2/items?page=2#a#b"))
        .hasToString("https://api.example.com/v2/items?page=2");
    assertThat(RedirectLocations.resolve(base, "next#my section"))
        .hasToString("https://api.example.com/v1/next");
    assertThatThrownBy(() -> RedirectLocations.resolve(base, "/next\u0001page#ok"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.invalidRedirectLocation());
  }

  /**
   * authority 가 빈 문자열인 Location(「https:///abs」「///abs」「//」)은 authority 가 없는 것으로 보고 base authority 를 쓴다
   * (WD-1055) — Python urljoin 이 빈 netloc 을 없음으로 보기 때문이다. 다른 스킴은 그대로 둔다.
   */
  @Test
  void resolve_emptyAuthority_usesBaseAuthority() {
    URI base = URI.create("https://api.example.com/v1/items?k=v");
    // 공용 사례 표(api-call-auth-vectors.json)에 있는 사례는 거기서 Java·Python 이 함께 본다 — 여기는 표에 없는 모양만 둔다.
    assertThat(RedirectLocations.resolve(base, "///")).hasToString("https://api.example.com/");
    assertThat(RedirectLocations.resolve(base, "///abs?x#f"))
        .hasToString("https://api.example.com/abs?x");
    assertThat(RedirectLocations.resolve(base, " ///abs"))
        .hasToString("https://api.example.com/abs");
    assertThat(RedirectLocations.resolve(base, "HTTPS:///abs"))
        .hasToString("https://api.example.com/abs");
  }

  /** 문자열 해석도 빈 authority 를 없음으로 본다(WD-1055). */
  @Test
  void resolveString_emptyAuthority_isAbsent() {
    assertThat(RedirectLocations.resolveString("https://h/a/b?k=v", "///c"))
        .isEqualTo("https://h/c");
    assertThat(RedirectLocations.resolveString("https://h/a/b?k=v", "//?q=1"))
        .isEqualTo("https://h/a/b?q=1");
  }
}
