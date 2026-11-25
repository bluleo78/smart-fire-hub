/**
 * 앞뒤 유니코드 공백을 걷어낸다 (WD-1132) — 서버 {@code TextRules.strip}·웹 stripEdgeSpace 와 같은 기준(유니코드 White_Space: NBSP·전각 공백·U+0085 포함).
 *
 * 왜: JS String.trim() 은 U+FEFF 를 걷고 U+0085 는 못 걷는 반면 Java String.trim() 은 U+0020 이하만 걷는다. 확인 이름 입력을 한쪽만 다르게
 * 걷으면 저장 이름 "Acme"+U+3000 와 입력 "Acme" 의 판정이 콘솔·서버에서 갈린다. 양쪽이 같은 집합(\p{White_Space})을 쓰게 한다.
 */
export function stripEdgeSpace(value: string): string {
  return value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');
}
