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
 *
 * <p>WD-1105: 「글자 없음」 집합을 더 넓혔다 — 화면엔 빈 이름인데 저장되던 값을 막는다. 공백({@code (?U)\s}) · Cf(서식) · Cc(제어 문자,
 * NUL 포함) · Mn/Me(홀로 쓰인 결합 문자·변이 선택자·둘러싸는 기호), 그리고 한글 채움 문자(U+115F·U+1160·U+3164·U+FFA0)와 점자
 * 빈칸(U+2800), 홀로 쓰인 서로게이트·배정 안 된 무시 코드 포인트다(집합과 이유는 {@link #NON_VISIBLE}). Mc(간격 결합
 * 문자)는 글자와 함께 실제로 보이는 모음 기호 등이므로 글자로 둔다. 웹 apps/firehub-web/src/lib/text-rules.ts 의
 * hasVisibleText 가 같은 판정을 따라야 한다 — 이 집합을 바꾸면 웹도 함께 바꾼다(WD-1124).
 */
public final class TextRules {

  /**
   * 「보이는 글자가 아닌」 문자 모음(문자 클래스 안에 넣을 조각, WD-1105). 어노테이션 값에 쓰므로 컴파일 시간 상수여야 한다.
   * 백슬래시+u+16진수 4자리 표기는 정규식 엔진이 해석한다. Java 정규식에는 Default_Ignorable_Code_Point 속성이 없어 한글 채움
   * 문자(U+115F·U+1160·U+3164·U+FFA0)·점자 빈칸(U+2800)은 코드 포인트로 적었다.
   *
   * <p>{@code \p{Cs}}(홀로 쓰인 서로게이트 — DB 에 저장도 못 한다)와 배정 안 된 Default_Ignorable 코드 포인트 U+2065·U+FFF0-FFF8 도
   * 뺀다 — Cn 이라 Cf 에 안 걸리고, {@code \p{Cn}} 전체는 JDK 가 아직 모르는 새 글자를 거절할 수 있어 쓰지 않는다. 정상 서로게이트 쌍(이모지)은 한
   * 코드 포인트로 읽혀 글자로 남는다.
   */
  private static final String NON_VISIBLE =
      "\\s\\p{Cf}\\p{Cc}\\p{Mn}\\p{Me}\\u115F\\u1160\\u3164\\uFFA0\\u2800"
          + "\\p{Cs}\\u2065\\uFFF0-\\uFFF8";

  /** 보이는 글자가 하나 이상 있는 값의 본문 — {@link #HAS_TEXT}·{@link #HAS_TEXT_OR_EMPTY} 가 같은 판정을 쓰게 한 곳에 둔다. */
  private static final String HAS_VISIBLE = ".*[^" + NON_VISIBLE + "].*";

  /**
   * 보이는 글자가 하나 이상 있다(공백·서식·제어·결합·채움 문자만이 아니다) — {@code @Pattern(regexp = HAS_TEXT)} 로 쓴다(null 은
   * 통과).
   */
  public static final String HAS_TEXT = "(?U)(?s)" + HAS_VISIBLE;

  /** 이름이 {@link #HAS_TEXT} 를 어겼을 때의 문구 — 만들기·수정 DTO 가 같은 문구를 쓰게 한 곳에 둔다(WD-1081). */
  public static final String NAME_BLANK_MESSAGE = "이름을 비울 수 없습니다.";

  /** SQL 이 {@link #HAS_TEXT} 를 어겼을 때의 문구(WD-1081). */
  public static final String SQL_BLANK_MESSAGE = "SQL을 비울 수 없습니다.";

  /**
   * 폴더 이름이 {@link #HAS_TEXT_OR_EMPTY} 를 어겼을 때의 문구(WD-1086) — 선택 필드라 @Pattern 만 쓰는 folder 가 이 문구를
   * 쓴다. "" 는 「값 없음」이라 통과하므로 거절되는 값은 보이는 글자가 없는 비어 있지 않은 값이다.
   */
  public static final String FOLDER_BLANK_MESSAGE = "폴더 이름을 비울 수 없습니다.";

  /**
   * 선택 필드용 — 빈 문자열은 「값 없음」이라 허용하고, 비어 있지 않으면서 보이는 글자가 없는 값만 거절한다(WD-1086, WD-1105).
   *
   * <p>update 의 folder "" 는 「폴더 없음」 신호(WD-197)라 통과해야 한다 — {@link #HAS_TEXT} 는 글자를 하나 이상 요구해 "" 를
   * 거절한다. 끝을 {@code \A\z} 로 잡는 건 Java 정규식의 {@code $} 가 마지막 줄바꿈 앞에서도 맞아, "^$" 로는 "\n" 만인 값이 새어 들어가기
   * 때문이다.
   */
  public static final String HAS_TEXT_OR_EMPTY = "(?U)(?s)(\\A\\z|" + HAS_VISIBLE + ")";

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
