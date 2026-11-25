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
 *
 * <p>{@code @NotBlank}(String.isBlank) 와 {@code (?U)\S} 는 Unicode White_Space 만 공백으로 보아, 폭 없는
 * 문자(U+200B·U+2060·U+FEFF)만인 이름·SQL 이 글자로 통과했다(WD-1086) — {@code Cf} 도 제외한다. 이 값을 바꾸면 이름·SQL·프롬프트
 * 검증이 함께 바뀐다.
 */
public final class TextRules {

  /** 유니코드 공백·Cf 가 아닌 글자가 하나 이상 있다 — {@code @Pattern(regexp = HAS_TEXT)} 로 쓴다(null 은 통과). */
  public static final String HAS_TEXT = "(?U)(?s).*[^\\s\\p{Cf}].*";

  /** 이름이 {@link #HAS_TEXT} 를 어겼을 때의 문구 — 만들기·수정 DTO 가 같은 문구를 쓰게 한 곳에 둔다(WD-1081). */
  public static final String NAME_BLANK_MESSAGE = "이름을 비울 수 없습니다.";

  /** 프롬프트가 {@link #HAS_TEXT} 를 어겼을 때의 문구 — 스마트 작업 만들기·수정 DTO 가 같은 문구를 쓰게 한 곳에 둔다(WD-1104). */
  public static final String PROMPT_BLANK_MESSAGE = "프롬프트를 비울 수 없습니다.";

  /** SQL 이 {@link #HAS_TEXT} 를 어겼을 때의 문구(WD-1081). */
  public static final String SQL_BLANK_MESSAGE = "SQL을 비울 수 없습니다.";

  /**
   * 폴더 이름이 {@link #HAS_TEXT_OR_EMPTY} 를 어겼을 때의 문구(WD-1086) — 선택 필드라 @Pattern 만 쓰는 folder 가 이 문구를
   * 쓴다. "" 는 「값 없음」이라 통과하므로 거절되는 값은 공백·NBSP·Cf 만인 비어 있지 않은 값이다.
   */
  public static final String FOLDER_BLANK_MESSAGE = "폴더 이름을 비울 수 없습니다.";

  /**
   * 선택 필드용 — 빈 문자열은 「값 없음」이라 허용하고, 비어 있지 않으면서 공백·NBSP·Cf 만인 값만 거절한다(WD-1086).
   *
   * <p>update 의 folder "" 는 「폴더 없음」 신호(WD-197)라 통과해야 한다 — {@link #HAS_TEXT} 는 글자를 하나 이상 요구해 "" 를
   * 거절한다. 끝을 {@code \A\z} 로 잡는 건 Java 정규식의 {@code $} 가 마지막 줄바꿈 앞에서도 맞아, "^$" 로는 "\n" 만인 값이 새어 들어가기
   * 때문이다.
   */
  public static final String HAS_TEXT_OR_EMPTY = "(?U)(?s)(\\A\\z|.*[^\\s\\p{Cf}].*)";

  /** {@link #HAS_TEXT} 를 한 번 컴파일한 정규식 — 매번 컴파일하지 않도록 static final 로 둔다. */
  private static final Pattern HAS_TEXT_PATTERN = Pattern.compile(HAS_TEXT);

  /** 앞뒤 유니코드 공백(전각 U+3000·NBSP 포함) — {@link #strip} 용. */
  private static final Pattern EDGE_SPACE = Pattern.compile("(?U)^\\s+|\\s+$");

  /**
   * 앞뒤 유니코드 공백을 걷어낸다(WD-1085). {@code String.strip()} 은 NBSP(U+00A0) 를 공백으로 보지 않아 {@link #HAS_TEXT}
   * 와 판정이 갈라지므로 같은 {@code (?U)\s} 기준을 쓴다. null 은 null(수정 요청의 「변경 없음」).
   */
  public static String strip(String value) {
    return value == null ? null : EDGE_SPACE.matcher(value).replaceAll("");
  }

  /**
   * 값에 공백(전각·NBSP 포함)·Cf 가 아닌 글자가 하나 이상 있는지(WD-1103).
   *
   * <p>{@link #HAS_TEXT} 와 같은 판정을 코드에서 쓰게 한다 — 저장소의 「빈 값이면 NULL」, 폴더 목록 걸러 내기가 각자 {@code
   * String.isBlank()} 를 쓰면 Cf(폭 없는 문자)만인 값을 서로 다르게 본다(WD-1086 후속). null 은 글자가 없으므로 false.
   */
  public static boolean hasText(String value) {
    return value != null && HAS_TEXT_PATTERN.matcher(value).matches();
  }

  private TextRules() {}
}
