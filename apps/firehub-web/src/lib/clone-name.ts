/**
 * 「복제」로 만드는 사본 이름(WD-430).
 *
 * 저장 쿼리·스마트 작업·리포트 양식은 이름 한도가 200자(DB varchar(200))인데, 복제는 원래 이름 뒤에 「 (복사본)」 같은 접미사를 붙여
 * 194자를 넘는 이름은 복제할 수 없었다 — 사용자는 이름을 쓴 적이 없는데 「200자 이내」·「예기치 않은 오류」로 실패했다. 접미사가 들어가도록
 * 원래 이름 끝을 잘라 한도 안에 맞춘다.
 *
 * 글자 수는 UTF-16 단위(String.length)로 센다(WD-699). 웹 zod `.max()` 와 서버 `@Size` 가 모두 UTF-16 으로 세므로, 코드 포인트로 자르면
 * 이모지가 든 이름의 기본 사본 이름이 손대지 않았는데도 검증에 걸렸다. DB VARCHAR 는 코드 포인트로 세지만 UTF-16 길이 ≤ N 이면
 * 코드 포인트 수도 ≤ N 이라 늘 들어간다(더 엄격한 쪽에 맞춤). 자르는 자리에 서로게이트 쌍이 걸리면 쌍을 통째로 빼 깨진 글자가 남지 않게 한다.
 */
export const NAME_MAX_LENGTH = 200;

/** 문자열을 UTF-16 `maxUnits` 단위 안으로 자른다 — 끝이 서로게이트 쌍의 앞쪽(high surrogate)이면 그것까지 빼 쌍을 가르지 않는다. */
function truncateUtf16(text: string, maxUnits: number): string {
  if (text.length <= maxUnits) return text;
  let end = Math.max(0, maxUnits);
  const last = text.charCodeAt(end - 1);
  if (end > 0 && last >= 0xd800 && last <= 0xdbff) end -= 1;
  return text.slice(0, end);
}

/**
 * @param separator 원래 이름과 접미사 사이 글자 — 기본은 공백(「이름 (복사본)」), 데이터셋 복제는 빈 문자열(「이름_copy」, WD-523).
 */
export function cloneName(name: string, suffix: string, maxLength = NAME_MAX_LENGTH, separator = ' '): string {
  const tail = `${separator}${suffix}`;
  const room = maxLength - tail.length;
  const base = name.length > room ? truncateUtf16(name, room).trimEnd() : name;
  return `${base}${tail}`;
}
