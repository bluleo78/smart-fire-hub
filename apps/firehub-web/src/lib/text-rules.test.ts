import { describe, expect, it } from 'vitest';

import { hasVisibleText } from './text-rules';

/**
 * hasVisibleText — 공백·서식(Cf)·제어·홀로 결합 문자·한글 채움·점자 빈칸·홀로 서로게이트·무시 코드 포인트만인 값을
 * 빈 값으로 보는 판정 (WD-1114, WD-1124)
 */
describe('hasVisibleText', () => {
  it.each([
    // 빈 값·공백·서식 문자
    '', '   ', '\u200B', '\u2060\uFEFF', '\u00A0\u3000\t\n', '\u0085', null, undefined,
    // WD-1124: 서버 WD-1105 집합 — 한글 채움·점자 빈칸
    '\u3164', '\u115F', '\u1160', '\uFFA0', '\u2800',
    // 홀로 결합·변이 선택자·둘러싸는 기호·제어
    '\u0301', '\uFE0F', '\u20DD', '\u0000', '\u0001',
    // 섞인 값·홀로 서로게이트·배정 안 된 무시 코드 포인트
    ' \u3164\u200B\u2800 ', '\uD800', '\u2065', '\uFFF0',
    // 14평면 무시 코드 포인트 블록 — 서버는 WD-1126 에서 같은 범위를 넣는 중
    '\u{E0000}', '\u{E0001}\u{E007F}',
  ])('보이는 글자가 없으면 false: %j', (v) => {
    expect(hasVisibleText(v)).toBe(false);
  });

  it.each(['a', '\u200B대시보드', ' 가 ', 'ㅎ', '❤️', 'e\u0301', 'a\u3164', '차트\u200B', '😀', '🏴\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}'])('보이는 글자가 하나라도 있으면 true: %j', (v) => {
    expect(hasVisibleText(v)).toBe(true);
  });
});
