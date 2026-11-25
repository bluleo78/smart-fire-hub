/**
 * 화면에 보이는 글자가 하나라도 있는지 판정한다 (WD-1114).
 *
 * 왜: String.trim() 은 일반 공백만 자르고 폭 없는 문자(U+200B·U+2060·U+FEFF 등, 유니코드 범주 Cf)는 남긴다.
 * 서버는 이런 값만인 이름·프롬프트를 빈 값으로 거절하는데 폼이 통과시키면 요청이 400 으로 돌아온다.
 * 그래서 공백(\s, NBSP·전각 공백 포함)과 서식 문자(\p{Cf})를 뺀 글자가 있어야 값이 있다고 본다.
 * U+0085(NEL)는 JS \s 에는 없지만 서버 (?U)\s(유니코드 White_Space)에는 있어 따로 넣는다 — 서버 TextRules.HAS_TEXT 와 같은 판정.
 * 값 자체는 바꾸지 않는다 — 전송 직전의 trim 은 그대로 두고 「비었는지」 판정에만 쓴다.
 */
export function hasVisibleText(value: string | null | undefined): boolean {
  return value != null && /[^\s\u0085\p{Cf}]/u.test(value);
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
