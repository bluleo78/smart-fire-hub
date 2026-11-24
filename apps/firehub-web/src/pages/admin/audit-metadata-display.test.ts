import { describe, expect, it } from 'vitest';

import { escapeInvisibleForDisplay, escapeInvisibleInText } from './audit-metadata-display';

/** WD-1031: 감사 로그 metadata 표시용 숨은 글자 이스케이프 */
describe('escapeInvisibleForDisplay', () => {
  // [설명, 입력, 기대] — 숨은 글자는 코드 단위마다 \uXXXX(대문자 4자리), 그 밖은 그대로.
  it.each([
    ['RLO', 'a‮b', 'a\\u202Eb'],
    ['ZWSP', '​', '\\u200B'],
    ['BOM', '﻿', '\\uFEFF'],
    ['NBSP', ' ', '\\u00A0'],
    ['변이 선택자 U+FE0F', '️', '\\uFE0F'],
    ['U+034F', '͏', '\\u034F'],
    ['U+180B', '᠋', '\\u180B'],
    ['경계 U+007F', '\u007F', '\\u007F'],
    ['태그 문자(BMP 밖) — 서로게이트 쌍', '\u{E0041}', '\\uDB40\\uDC41'],
    ['변이 선택자 보충 U+E0100 — 서로게이트 쌍', '\u{E0100}', '\\uDB40\\uDD00'],
    ['한글·영문·이모지는 그대로', '한글 abc 😀', '한글 abc 😀'],
    ['경계 U+007E(~)는 그대로', '~', '~'],
    ['U+00A1(¡)는 그대로', '¡', '¡'],
  ])('%s', (_name, input, expected) => {
    expect(escapeInvisibleForDisplay(input)).toBe(expected);
  });

  it('이미 이스케이프된 JSON 표기(글자 그대로의 백슬래시)는 건드리지 않는다', () => {
    const json = JSON.stringify({ a: '\\u202E' });
    expect(escapeInvisibleForDisplay(json)).toBe(json);
  });

  it('들여쓴 JSON 의 줄바꿈·공백은 유지한다', () => {
    const json = JSON.stringify({ a: 'x' }, null, 2);
    expect(escapeInvisibleForDisplay(json)).toBe(json);
  });
});

/** WD-1056: 감사 로그 원문 문자열(사용자·설명·오류·User-Agent·리소스 ID)용 이스케이프 */
describe('escapeInvisibleInText', () => {
  // [설명, 입력, 기대]
  it.each([
    ['RLO·ZWSP 는 바꾼다', 'a\u202Eb\u200Bc', 'a\\u202Eb\\u200Bc'],
    ['BOM 은 바꾼다', '\uFEFFx', '\\uFEFFx'],
    ['탭·CR·LF·공백은 그대로(스택 트레이스)', 'x\r\n\tat Foo (Bar.java:1)', 'x\r\n\tat Foo (Bar.java:1)'],
    ['NUL 등 그 밖의 제어는 바꾼다', 'a\u0000b', 'a\\u0000b'],
    ['VS16 이모지(❤\uFE0F)는 그대로', '❤\uFE0F 매출', '❤\uFE0F 매출'],
    ['키캡(1\uFE0F\u20E3)은 그대로', '1\uFE0F\u20E3', '1\uFE0F\u20E3'],
    ['ZWJ 가족 이모지는 그대로', '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}', '\u{1F468}\u200D\u{1F469}\u200D\u{1F467}'],
    ['피부색 + ZWJ 이모지는 그대로', '\u{1F469}\u{1F3FD}\u200D\u{1F4BB}', '\u{1F469}\u{1F3FD}\u200D\u{1F4BB}'],
    ['VS16 + ZWJ(❤\uFE0F\u200D🔥)는 그대로', '❤\uFE0F\u200D\u{1F525}', '❤\uFE0F\u200D\u{1F525}'],
    ['글자 사이 ZWJ 는 바꾼다', 'ad\u200Dmin', 'ad\\u200Dmin'],
    ['이모지 뒤라도 다음이 이모지가 아니면 ZWJ 는 바꾼다', '\u{1F600}\u200Dx', '\u{1F600}\\u200Dx'],
    ['글자 뒤 변이 선택자는 바꾼다', 'a\uFE0F', 'a\\uFE0F'],
    ['맨 앞 변이 선택자는 바꾼다', '\uFE0Fa', '\\uFE0Fa'],
  ])('%s', (_label, input, expected) => {
    expect(escapeInvisibleInText(input)).toBe(expected);
  });
});
