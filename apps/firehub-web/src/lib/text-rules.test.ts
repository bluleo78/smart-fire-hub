import { describe, expect, it } from 'vitest';

import { hasVisibleText, stripEdgeSpace } from './text-rules';

/** hasVisibleText — 공백·보이지 않는 서식 문자(Cf)만인 값을 빈 값으로 보는 판정 (WD-1114) */
describe('hasVisibleText', () => {
  it.each(['', '   ', '\u200B', '\u2060\uFEFF', '\u00A0\u3000\t\n', '\u0085', null, undefined])('보이는 글자가 없으면 false: %j', (v) => {
    expect(hasVisibleText(v)).toBe(false);
  });

  it.each(['a', '\u200B대시보드', ' 가 '])('보이는 글자가 하나라도 있으면 true: %j', (v) => {
    expect(hasVisibleText(v)).toBe(true);
  });
});

describe('stripEdgeSpace (WD-1132)', () => {
  it('앞뒤 유니코드 공백(전각·NBSP·NEL)을 걷고 안쪽 공백·대소문자는 그대로 둔다', () => {
    expect(stripEdgeSpace('\u3000Acme\u00a0')).toBe('Acme');
    expect(stripEdgeSpace('\u0085 A b \u0085')).toBe('A b');
    expect(stripEdgeSpace('ACME')).toBe('ACME');
  });
});
