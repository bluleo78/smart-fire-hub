package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** MethodOverrideNames 단위 시험(WD-970) — Spring 없이 이름 판정만 본다. */
class MethodOverrideNamesTest {

  @Test
  void header_matches() {
    assertThat(MethodOverrideNames.isOverrideHeader("X-HTTP-Method-Override")).isTrue();
    assertThat(MethodOverrideNames.isOverrideHeader("x_http_method")).isTrue();
    assertThat(MethodOverrideNames.isOverrideHeader(" X-Method-Override ")).isTrue();
  }

  @Test
  void header_nonMatches() {
    assertThat(MethodOverrideNames.isOverrideHeader("X-Method-Override2")).isFalse();
    assertThat(MethodOverrideNames.isOverrideHeader("X-Method-Name")).isFalse();
    assertThat(MethodOverrideNames.isOverrideHeader(null)).isFalse();
    assertThat(MethodOverrideNames.isOverrideHeader("")).isFalse();
    // 전각 글자가 섞인 이름은 ASCII 로 줄여도 같아지지 않는다(글자 판정은 ASCII 만).
    assertThat(MethodOverrideNames.isOverrideHeader("X-HTTP-Ｍethod-Override")).isFalse();
  }

  @Test
  void queryName_matches() {
    assertThat(MethodOverrideNames.isOverrideQueryName("_method")).isTrue();
    assertThat(MethodOverrideNames.isOverrideQueryName("%5Fmethod")).isTrue();
    assertThat(MethodOverrideNames.isOverrideQueryName("%5fMETHOD")).isTrue();
    assertThat(MethodOverrideNames.isOverrideQueryName("X-HTTP-Method-Override")).isTrue();
  }

  @Test
  void queryName_nonMatches() {
    assertThat(MethodOverrideNames.isOverrideQueryName("method")).isFalse();
    assertThat(MethodOverrideNames.isOverrideQueryName("%zz")).isFalse();
  }

  @Test
  void findOverrideQuery_splitsOnAmpersandAndSemicolon() {
    assertThat(MethodOverrideNames.findOverrideQuery(URI.create("http://h/p?a=1;_method=DELETE")))
        .isEqualTo(Optional.of("_method"));
    assertThat(MethodOverrideNames.findOverrideQuery(URI.create("http://h/p?method=list&page=2")))
        .isEmpty();
    assertThat(MethodOverrideNames.findOverrideQuery(URI.create("http://h/p"))).isEmpty();
  }

  @Test
  void findOverrideHeader_returnsFirst() {
    assertThat(MethodOverrideNames.findOverrideHeader(List.of("Accept", "X-HTTP-Method")))
        .isEqualTo(Optional.of("X-HTTP-Method"));
  }

  @Test
  void findOverrideHeader_returnsStandardNameNotRaw() {
    // 저장 설정의 원문(구분자·대소문자·끼운 글)이 아니라 표준 이름만 돌려준다 — 안내 문구에 원문이 실리지 않게(WD-970 리뷰).
    assertThat(MethodOverrideNames.findOverrideHeader(List.of("x_http_method_override 지시문")))
        .isEqualTo(Optional.of("X-HTTP-Method-Override"));
  }

  @Test
  void findOverrideQuery_returnsStandardNameNotRaw() {
    // 대상 서버 Location 에 끼운 글(퍼센트 인코딩된 한글 지시문)은 판정에서 지워지지만 돌려주는 이름에도 실리지 않는다.
    assertThat(
            MethodOverrideNames.findOverrideQuery(
                URI.create("http://h/d?X-HTTP-Method-Override%20%EC%A7%80%EC%8B%9C%EB%AC%B8=1")))
        .isEqualTo(Optional.of("X-HTTP-Method-Override"));
    assertThat(MethodOverrideNames.findOverrideQuery(URI.create("http://h/d?%5FMETHOD=DELETE")))
        .isEqualTo(Optional.of("_method"));
  }
}
