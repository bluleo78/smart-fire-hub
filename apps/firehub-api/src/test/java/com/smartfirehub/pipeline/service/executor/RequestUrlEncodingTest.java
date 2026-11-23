package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** WD-936: 직접 URL 의 경로·쿼리 인코딩 유틸 단위 시험. */
class RequestUrlEncodingTest {

  private static String enc(String url) {
    return RequestUrlEncoding.encodePathAndQuery(url);
  }

  /** 참조 인코딩(WD-991) — 상대 참조·null·//authority·절대 URL. */
  @Test
  void encodeReference_variants() {
    assertThat(RequestUrlEncoding.encodeReference("?q=가")).isEqualTo("?q=%EA%B0%80");
    assertThat(RequestUrlEncoding.encodeReference(null)).isNull();
    assertThat(RequestUrlEncoding.encodeReference("//한글.com/가 나"))
        .isEqualTo("//한글.com/%EA%B0%80%20%EB%82%98");
    assertThat(RequestUrlEncoding.encodeReference("https://x.com/가#f"))
        .isEqualTo("https://x.com/%EA%B0%80");
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
}
