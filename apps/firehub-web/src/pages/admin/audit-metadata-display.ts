/**
 * WD-1031: 감사 로그 상세 Metadata(JSON) 표시용 숨은 글자 이스케이프.
 *
 * 보이지 않거나 글자 순서를 뒤집는 문자(RLO·ZWSP·BOM·NBSP·변이 선택자·태그 문자 등)가 그대로 보이면
 * 운영자가 값을 잘못 읽는다. 표시 문자열에서만 해당 코드 단위를 `\uXXXX` 로 바꾼다(저장 값은 원문 유지 — WD-1014).
 *
 * 손으로 쓴 문자 목록은 변이 선택자(U+FE0F·U+E0100 등)·U+034F·U+180B 같은 것을 놓치므로,
 * 유니코드 속성 클래스(`u` 플래그)로 판정한다: Default_Ignorable_Code_Point, Cf(서식), Zs/Zl/Zp(공백·구분자),
 * Cc(제어).
 * 입력은 `JSON.stringify(x, null, 2)` 로 정렬된 문자열이므로 그 자체의 줄바꿈(U+000A)과
 * 들여쓰기 공백(U+0020)은 제외한다(앞선 부정 전방탐색). 그 밖의 C0 는 JSON.stringify 가 이스케이프하므로 나타나지 않는다.
 * BMP 밖 문자(예: U+E0100)는 서로게이트 쌍이므로 각 코드 단위를 JSON 표기 그대로 바꾼다.
 */
const INVISIBLE_DISPLAY =
  /(?![ \n])[\p{Default_Ignorable_Code_Point}\p{Cf}\p{Zs}\p{Zl}\p{Zp}\p{Cc}]/gu;

/** 코드 단위 하나를 `\uXXXX`(대문자 16진 4자리)로 만든다. */
function toUnicodeEscape(unit: string): string {
  return '\\u' + unit.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0');
}

/** 숨은 글자를 `\uXXXX` 텍스트로 바꿔 화면에서 보이게 한다. 그 밖의 글자는 그대로 둔다. */
export function escapeInvisibleForDisplay(text: string): string {
  // 서로게이트 쌍은 코드 포인트가 아니라 코드 단위(UTF-16)별로 바꿔야 JSON 표기와 같다 — split('') 은 코드 단위로 나눈다.
  return text.replace(INVISIBLE_DISPLAY, (match) => match.split('').map(toUnicodeEscape).join(''));
}
