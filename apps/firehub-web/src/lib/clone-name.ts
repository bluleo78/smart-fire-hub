/**
 * 「복제」로 만드는 사본 이름(WD-430).
 *
 * 저장 쿼리·스마트 작업·리포트 양식은 이름 한도가 200자(DB varchar(200))인데, 복제는 원래 이름 뒤에 「 (복사본)」 같은 접미사를 붙여
 * 194자를 넘는 이름은 복제할 수 없었다 — 사용자는 이름을 쓴 적이 없는데 「200자 이내」·「예기치 않은 오류」로 실패했다. 접미사가 들어가도록
 * 원래 이름 끝을 잘라 한도 안에 맞춘다. 글자 수는 DB 와 같게 코드 포인트로 센다(이모지 같은 서로게이트 쌍을 반으로 자르지 않게).
 */
export const NAME_MAX_LENGTH = 200;

export function cloneName(name: string, suffix: string, maxLength = NAME_MAX_LENGTH): string {
  const tail = ` ${suffix}`;
  const room = maxLength - Array.from(tail).length;
  const chars = Array.from(name);
  const base = chars.length > room ? chars.slice(0, room).join('').trimEnd() : name;
  return `${base}${tail}`;
}
