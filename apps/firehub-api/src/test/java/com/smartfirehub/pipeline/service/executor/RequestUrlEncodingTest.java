package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** WD-936: 직접 URL 의 경로·쿼리 인코딩 유틸 단위 시험. */
class RequestUrlEncodingTest {

  private static String enc(String url) {
    return RequestUrlEncoding.encodePathAndQuery(url);
  }

  @Test
  void spaceInQuery() {
    assertThat(enc("https://example.com/search?q=seoul city"))
        .isEqualTo("https://example.com/search?q=seoul%20city");
  }

  @Test
  void spaceInPath() {
    assertThat(enc("https://example.com/a b/c?x=1")).isEqualTo("https://example.com/a%20b/c?x=1");
  }

  @Test
  void korean() {
    assertThat(enc("https://example.com/검색?q=서울"))
        .isEqualTo("https://example.com/%EA%B2%80%EC%83%89?q=%EC%84%9C%EC%9A%B8");
  }

  @Test
  void quotesAndBrackets() {
    assertThat(enc("https://example.com/p?q=\"x\"<y>"))
        .isEqualTo("https://example.com/p?q=%22x%22%3Cy%3E");
  }

  @Test
  void squareBracketsEncoded() {
    assertThat(enc("https://example.com/p?a[0]=1")).isEqualTo("https://example.com/p?a%5B0%5D=1");
  }

  @Test
  void alreadyEncoded_unchanged() {
    String u = "https://example.com/p?q=a%20b&k=rP1x%2fAbC%2B";
    assertThat(enc(u)).isEqualTo(u);
  }

  @Test
  void plusAndSubDelims_unchanged() {
    String u = "https://example.com/p?q=a+b&r=(x),y;z!$'*";
    assertThat(enc(u)).isEqualTo(u);
  }

  @Test
  void lonePercentAndPipe_encoded() {
    assertThat(enc("https://example.com/p?q=100%&r=x|y"))
        .isEqualTo("https://example.com/p?q=100%25&r=x%7Cy");
  }

  @Test
  void fragmentDropped() {
    assertThat(enc("https://example.com/p?q=1#frag ment")).isEqualTo("https://example.com/p?q=1");
  }

  @Test
  void authorityUntouched() {
    assertThat(enc("http://exa mple.com/a b")).isEqualTo("http://exa mple.com/a%20b");
    assertThat(enc("http://[::1]:8080/a b")).isEqualTo("http://[::1]:8080/a%20b");
    assertThat(enc("https://u:p@host:8443?q=a b")).isEqualTo("https://u:p@host:8443?q=a%20b");
  }

  @Test
  void noScheme_returnedAsIs() {
    assertThat(enc("example.com/a b")).isEqualTo("example.com/a b");
    assertThat(enc(null)).isNull();
  }

  @Test
  void idempotent() {
    List<String> cases =
        List.of(
            "https://example.com/search?q=seoul city",
            "https://example.com/검색?q=서울",
            "https://example.com/p?q=\"x\"<y>",
            "https://example.com/p?q=100%&r=x|y",
            "https://example.com/p?q=a%20b&k=rP1x%2fAbC%2B",
            "https://example.com/p?q=1#frag ment",
            "http://[::1]:8080/a b");
    for (String input : cases) {
      String once = enc(input);
      assertThat(enc(once)).as(input).isEqualTo(once);
    }
  }

  /** 이모지(서로게이트 쌍)는 UTF-8 4바이트로 인코딩한다(WD-936). */
  @Test
  void emoji_encodedAsUtf8() {
    assertThat(enc("https://h/p?q=😀")).isEqualTo("https://h/p?q=%F0%9F%98%80");
  }

  /** 끝의 % 와 16진 한 자리만 남은 %2 는 리터럴이므로 %25 로 바꾼다. */
  @Test
  void trailingPercent_encoded() {
    assertThat(enc("https://h/p?q=a%")).isEqualTo("https://h/p?q=a%25");
    assertThat(enc("https://h/p?q=a%2")).isEqualTo("https://h/p?q=a%252");
  }

  /** 스킴은 대소문자 무관하게 인식한다. */
  @Test
  void uppercaseScheme_recognized() {
    assertThat(enc("HTTP://h/p?q=a b")).isEqualTo("HTTP://h/p?q=a%20b");
  }

  /** 백슬래시·캐럿·백틱·중괄호는 Java URI 가 날것으로 못 실어 인코딩한다. */
  @Test
  void backslashCaretBacktickBraces_encoded() {
    assertThat(enc("https://h/p?q=\\^`{}")).isEqualTo("https://h/p?q=%5C%5E%60%7B%7D");
  }

  /**
   * 경로·쿼리의 제어 문자(탭·개행·DEL)는 실행기 httpx 가 InvalidURL 로 거절하므로 테스트 호출도 거절되도록 원문을 그대로 돌려 기존 검사가 거절하게
   * 한다(WD-936).
   */
  @Test
  void controlCharacters_returnedAsIs() {
    assertThat(enc("https://h/p?q=a\tb")).isEqualTo("https://h/p?q=a\tb");
    assertThat(enc("https://h/p\nx")).isEqualTo("https://h/p\nx");
    assertThat(enc("https://h/p?q=a\u007Fb")).isEqualTo("https://h/p?q=a\u007Fb");
  }

  /** 짝 없는 서로게이트는 UTF-8 로 옮길 수 없어 %3F 로 바꾸지 않고 원문을 돌려 이후 조립이 거절하게 한다(WD-936 리뷰 후속). */
  @Test
  void loneSurrogate_returnedAsIs() {
    assertThat(enc("https://h/p?q=a\uD800b")).isEqualTo("https://h/p?q=a\uD800b");
    assertThat(enc("https://h/p\uDC00 x")).isEqualTo("https://h/p\uDC00 x");
  }

  /** 표시용: 한글의 UTF-8 묶음만 글자로 되돌리고 ASCII 이스케이프는 그대로 둔다(WD-936 리뷰 후속). */
  @Test
  void decodeForDisplay_decodesNonAsciiOnly() {
    assertThat(RequestUrlEncoding.decodeForDisplay(enc("https://h/검색?q=서울 시&r=a/b")))
        .isEqualTo("https://h/검색?q=서울%20시&r=a/b");
    assertThat(RequestUrlEncoding.decodeForDisplay("https://h/p?q=%2F%3D%26%20x%25"))
        .isEqualTo("https://h/p?q=%2F%3D%26%20x%25");
    assertThat(RequestUrlEncoding.decodeForDisplay("https://h/p?q=%f0%9f%98%80"))
        .isEqualTo("https://h/p?q=😀");
  }

  /** 잘린·잘못된 UTF-8 묶음과 가린 키(****)는 바꾸지 않는다. null·% 없는 값은 그대로. */
  @Test
  void decodeForDisplay_keepsInvalidSequences() {
    assertThat(RequestUrlEncoding.decodeForDisplay("https://h/p?q=%EA%B2&r=%FF"))
        .isEqualTo("https://h/p?q=%EA%B2&r=%FF");
    assertThat(RequestUrlEncoding.decodeForDisplay("https://h/p?k=****Ab12"))
        .isEqualTo("https://h/p?k=****Ab12");
    assertThat(RequestUrlEncoding.decodeForDisplay(null)).isNull();
  }
}
