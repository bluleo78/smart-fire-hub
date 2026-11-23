import { describe, expect, it } from 'vitest';

import { escapeInvisibleForDisplay } from './audit-metadata-display';

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
