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
