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
/** 숨은 글자 판정 문자 클래스 — 두 이스케이프(JSON 용·원문용)가 같은 범위를 쓰도록 한 곳에 둔다. */
const INVISIBLE_CLASS = String.raw`[\p{Default_Ignorable_Code_Point}\p{Cf}\p{Zs}\p{Zl}\p{Zp}\p{Cc}]`;
const INVISIBLE_DISPLAY = new RegExp(String.raw`(?![ \n])` + INVISIBLE_CLASS, 'gu');

/** 코드 단위 하나를 `\uXXXX`(대문자 16진 4자리)로 만든다. */
function toUnicodeEscape(unit: string): string {
  return '\\u' + unit.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0');
}

/** 매치(코드 포인트)를 코드 단위마다 `\uXXXX` 로 바꾼다 — 서로게이트 쌍은 JSON 표기처럼 두 단위로 나온다(split('') 은 코드 단위로 나눈다). */
function escapeUnits(match: string): string {
  return match.split('').map(toUnicodeEscape).join('');
}

/** 숨은 글자를 `\uXXXX` 텍스트로 바꿔 화면에서 보이게 한다. 그 밖의 글자는 그대로 둔다. */
export function escapeInvisibleForDisplay(text: string): string {
  return text.replace(INVISIBLE_DISPLAY, escapeUnits);
}

/**
 * WD-1056: 원문 문자열(사용자·설명·오류 메시지·User-Agent·리소스 ID)용 숨은 글자 이스케이프.
 *
 * `escapeInvisibleForDisplay` 는 JSON.stringify 결과 전용이라 공백·줄바꿈만 남긴다. 원문에는 탭(U+0009)·CR(U+000D)이
 * 정상적으로 들어 있고(스택 트레이스·SQL), 이모지는 ZWJ(U+200D)·변이 선택자(U+FE0E/U+FE0F)를 글자의 일부로 쓴다.
 * 이것들까지 `\uXXXX` 로 바꾸면 여러 줄 오류가 한 줄로 망가지고 하트(VS16)·가족(ZWJ) 이모지가 코드로 보이므로,
 * 원문에서는 공백류 제어(탭·CR·LF·공백)와 이모지를 잇는 ZWJ·변이 선택자를 그대로 둔다.
 * 그 밖의 숨은 글자(RLO·ZWSP·BOM 등)는 `escapeInvisibleForDisplay` 와 같은 표기로 바꾼다.
 */
const INVISIBLE_RAW_TEXT = new RegExp(String.raw`(?![ \n\t\r])` + INVISIBLE_CLASS, 'gu');
/** ZWJ 앞에 올 수 있는 이모지 구성 글자(그림 글자·피부색 수식자·VS16). */
const EMOJI_BEFORE_ZWJ = /^[\p{Extended_Pictographic}\p{Emoji_Modifier}\uFE0F]$/u;
/** 변이 선택자가 붙을 수 있는 글자(이모지 속성 — 숫자·#·* 키캡 포함). */
const EMOJI_BASE = /^\p{Emoji}$/u;
/** ZWJ 뒤에 와야 하는 그림 글자. */
const PICTOGRAPHIC = /^\p{Extended_Pictographic}$/u;

/** text 의 index 바로 앞 코드 포인트(서로게이트 쌍이면 두 단위)를 돌려준다. */
function codePointBefore(text: string, index: number): string {
  if (index <= 0) return '';
  const low = text.charCodeAt(index - 1);
  if (low >= 0xdc00 && low <= 0xdfff && index >= 2) return text.slice(index - 2, index);
  return text[index - 1];
}

/** text 의 index 에서 시작하는 코드 포인트를 돌려준다. */
function codePointAt(text: string, index: number): string {
  const cp = text.codePointAt(index);
  return cp === undefined ? '' : String.fromCodePoint(cp);
}

/** match(offset 위치)가 이모지 시퀀스를 잇는 ZWJ·변이 선택자인지 판정한다. */
function isEmojiJoiner(match: string, offset: number, text: string): boolean {
  const before = codePointBefore(text, offset);
  if (match === '\u200D') {
    return EMOJI_BEFORE_ZWJ.test(before) && PICTOGRAPHIC.test(codePointAt(text, offset + 1));
  }
  if (match === '\uFE0F' || match === '\uFE0E') return EMOJI_BASE.test(before);
  return false;
}

/** 원문 문자열의 숨은 글자를 `\uXXXX` 텍스트로 바꾼다 — 탭·CR·LF·공백과 이모지 구성 글자는 그대로 둔다. */
export function escapeInvisibleInText(text: string): string {
  return text.replace(INVISIBLE_RAW_TEXT, (match: string, offset: number) =>
    isEmojiJoiner(match, offset, text) ? match : escapeUnits(match),
  );
}
