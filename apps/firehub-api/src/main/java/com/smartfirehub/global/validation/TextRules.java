package com.smartfirehub.global.validation;

/**
 * 수정 요청 문자열 필드의 「생략은 되지만 비울 수는 없다」 검증 정규식의 단일 원본(WD-1079).
 *
 * <p>수정 DTO 는 null 을 「변경 없음」으로 받으므로 {@code @NotBlank} 를 쓸 수 없고, null 을 통과시키는 {@code @Pattern} 으로 빈
 * 값만 거른다. {@code (?U)} 가 없으면 Java 의 {@code \S} 가 ASCII 공백만 공백으로 보아, 한국어 입력기가 넣는 전각 공백(U+3000)·줄바꿈
 * 없는 공백(U+00A0)만으로 된 이름이 「글자가 있는」 값으로 통과했다(WD-1079 코드 리뷰). 저장 쿼리(WD-446)·차트·대시보드 수정이 같은 값을 쓴다 —
 * 엔드포인트마다 공백 판정이 갈라지지 않게 하기 위해서다.
 */
public final class TextRules {

  /** 유니코드 공백이 아닌 글자가 하나 이상 있다 — {@code @Pattern(regexp = HAS_TEXT)} 로 쓴다(null 은 통과). */
  public static final String HAS_TEXT = "(?U)(?s).*\\S.*";

  private TextRules() {}
}
