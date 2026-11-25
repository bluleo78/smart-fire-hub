/**
 * 화면에 보이는 글자가 하나라도 있는지 판정한다 (WD-1114, WD-1124).
 *
 * 왜: String.trim() 은 일반 공백만 자르고 폭 없는·안 보이는 문자는 남긴다.
 * 서버(TextRules.NON_VISIBLE, WD-1105)는 이런 값만인 이름·프롬프트를 빈 값으로 거절하는데 폼이 통과시키면 요청이 400 으로 돌아온다.
 * 그래서 서버와 같은 「글자 아닌」 집합을 뺀 글자가 있어야 값이 있다고 본다. 서버 집합을 바꾸면 여기도 함께 바꾼다.
 *  - 공백: \s(NBSP·전각 공백 포함) + U+0085(NEL, JS \s 에는 없지만 서버 (?U)\s 에는 있다)
 *  - \p{Cf} 서식, \p{Cc} 제어(NUL 포함), \p{Mn}·\p{Me} 홀로 쓰인 결합 문자·변이 선택자·둘러싸는 기호
 *    (Mc 는 글자와 함께 보이는 모음 기호라 글자로 둔다)
 *  - 한글 채움 문자 U+115F·U+1160·U+3164·U+FFA0, 점자 빈칸 U+2800 (Default_Ignorable 이지만 범주로는 못 잡는다)
 *  - \p{Cs} 홀로 쓰인 서로게이트(u 플래그에서 정상 쌍은 한 코드 포인트라 글자로 남는다),
 *    배정 안 된 무시 코드 포인트 U+2065·U+FFF0-FFF8
 *  - 14평면 무시 코드 포인트 블록 U+E0000-E0FFF(태그 문자 등) — 서버는 WD-1126 에서 같은 범위를 넣는 중이다. 그 전까지는 웹이 이 범위만 더 엄격하다
 *    (배정 안 된 무시 코드 포인트라 보이는 이름이 막히지 않는다)
 * 값 자체는 바꾸지 않는다 — 전송 직전의 trim 은 그대로 두고 「비었는지」 판정에만 쓴다.
 */
export function hasVisibleText(value: string | null | undefined): boolean {
  return (
    value != null &&
    /[^\s\u0085\p{Cf}\p{Cc}\p{Mn}\p{Me}\u115F\u1160\u3164\uFFA0\u2800\p{Cs}\u2065\uFFF0-\uFFF8\u{E0000}-\u{E0FFF}]/u.test(value)
  );
}

/**
 * 앞뒤 유니코드 공백을 걷어낸다 (WD-1132) — 서버 {@code TextRules.strip} 과 같은 기준(유니코드 White_Space: NBSP·전각 공백·U+0085 포함).
 *
 * 왜: JS String.trim() 은 U+FEFF 를 걷고 U+0085 는 못 걷는 반면 Java String.trim() 은 U+0020 이하만 걷는다. 이름 확인 입력을 한쪽만 다르게
 * 걷으면 저장 이름 "Acme"+U+3000 와 입력 "Acme" 의 판정이 웹·서버에서 갈린다. 양쪽이 같은 집합(\p{White_Space})을 쓰게 한다.
 */
export function stripEdgeSpace(value: string): string {
  return value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');
}
