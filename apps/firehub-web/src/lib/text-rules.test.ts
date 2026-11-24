import { describe, expect, it } from 'vitest';

import { hasVisibleText } from './text-rules';

/** hasVisibleText — 공백·보이지 않는 서식 문자(Cf)만인 값을 빈 값으로 보는 판정 (WD-1114) */
describe('hasVisibleText', () => {
  it.each(['', '   ', '\u200B', '\u2060\uFEFF', '\u00A0\u3000\t\n', '\u0085', null, undefined])('보이는 글자가 없으면 false: %j', (v) => {
    expect(hasVisibleText(v)).toBe(false);
  });

  it.each(['a', '\u200B대시보드', ' 가 '])('보이는 글자가 하나라도 있으면 true: %j', (v) => {
    expect(hasVisibleText(v)).toBe(true);
  });
});
