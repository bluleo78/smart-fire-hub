package com.smartfirehub.global.validation;

import java.util.regex.Pattern;

/**
 * 수정 요청 문자열 필드의 「생략은 되지만 비울 수는 없다」 검증 정규식의 단일 원본(WD-1079).
 *
 * <p>수정 DTO 는 null 을 「변경 없음」으로 받으므로 {@code @NotBlank} 를 쓸 수 없고, null 을 통과시키는 {@code @Pattern} 으로 빈
 * 값만 거른다. {@code (?U)} 가 없으면 Java 의 {@code \S} 가 ASCII 공백만 공백으로 보아, 한국어 입력기가 넣는 전각 공백(U+3000)·줄바꿈
 * 없는 공백(U+00A0)만으로 된 이름이 「글자가 있는」 값으로 통과했다(WD-1079 코드 리뷰). 저장 쿼리(WD-446)·차트·대시보드·API 연결·스마트 작업
 * 수정(WD-1080)이 같은 값을 쓴다 — 엔드포인트마다 공백 판정이 갈라지지 않게 하기 위해서다.
 *
 * <p>만들기 DTO(차트·대시보드·저장 쿼리, WD-1081)도 {@code @NotBlank} 옆에 같은 값을 붙인다 —
 * {@code @NotBlank}(String.isBlank) 는 U+3000 은 거르지만 NBSP 계열(U+00A0·U+2007·U+202F)은 글자로 보아, 만들기에서 받은
 * 이름을 수정에서 거르는 어긋남이 생겼다. 이 값을 바꾸면 만들기·수정 검증이 함께 바뀐다.
 */
public final class TextRules {

  /** 유니코드 공백이 아닌 글자가 하나 이상 있다 — {@code @Pattern(regexp = HAS_TEXT)} 로 쓴다(null 은 통과). */
  public static final String HAS_TEXT = "(?U)(?s).*\\S.*";

  /** 이름이 {@link #HAS_TEXT} 를 어겼을 때의 문구 — 만들기·수정 DTO 가 같은 문구를 쓰게 한 곳에 둔다(WD-1081). */
  public static final String NAME_BLANK_MESSAGE = "이름을 비울 수 없습니다.";

  /** SQL 이 {@link #HAS_TEXT} 를 어겼을 때의 문구(WD-1081). */
  public static final String SQL_BLANK_MESSAGE = "SQL을 비울 수 없습니다.";

  /** 앞뒤 유니코드 공백(전각 U+3000·NBSP 포함) — {@link #strip} 용. */
  private static final Pattern EDGE_SPACE = Pattern.compile("(?U)^\\s+|\\s+$");

  /**
   * 앞뒤 유니코드 공백을 걷어낸다(WD-1085). {@code String.strip()} 은 NBSP(U+00A0) 를 공백으로 보지 않아 {@link #HAS_TEXT}
   * 와 판정이 갈라지므로 같은 {@code (?U)\s} 기준을 쓴다. null 은 null(수정 요청의 「변경 없음」).
   */
  public static String strip(String value) {
    return value == null ? null : EDGE_SPACE.matcher(value).replaceAll("");
  }

  private TextRules() {}
}
