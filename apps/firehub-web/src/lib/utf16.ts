/**
 * UTF-16 문자열 자르기 도우미 (WD-1143)
 * 여러 검증·이름 모듈이 같이 쓰므로 다른 lib 모듈을 가져오지 않는 독립 모듈로 둔다.
 * 순환 import 를 막고, 검색어 입력처럼 가벼운 경로가 무거운 이름 모듈을 끌어오지 않게 하려는 목적이다.
 */

/**
 * 문자열을 UTF-16 `maxUnits` 단위 안으로 자른다 — 끝이 서로게이트 쌍의 앞쪽(high surrogate)이면 그것까지 빼 쌍을 가르지 않는다.
 * 서버·zod 의 길이 계산이 UTF-16 코드 단위라 같은 단위로 자른다. 쌍을 가르면 깨진 글자가 남으므로 그 반쪽을 버린다.
 */
export function truncateUtf16(text: string, maxUnits: number): string {
  // 이미 상한 이내면 자를 필요가 없어 그대로 돌려준다
  if (text.length <= maxUnits) return text;
  let end = Math.max(0, maxUnits);
  // 자르는 자리 바로 앞 글자가 high surrogate 면 쌍이 갈리므로 한 칸 더 뺀다
  const last = text.charCodeAt(end - 1);
  if (end > 0 && last >= 0xd800 && last <= 0xdbff) end -= 1;
  return text.slice(0, end);
}
