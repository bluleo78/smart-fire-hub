import { describe, expect, it } from 'vitest';

import { cloneName } from './clone-name';

describe('cloneName (WD-430)', () => {
  it('짧은 이름은 그대로 두고 접미사를 붙인다', () => {
    expect(cloneName('일일 리포트', '(복사본)')).toBe('일일 리포트 (복사본)');
  });

  it('붙이면 200자를 넘는 이름은 앞부분만 남겨 정확히 200자로 맞춘다', () => {
    const result = cloneName('가'.repeat(200), '(복사본)');
    expect(Array.from(result)).toHaveLength(200);
    expect(result.endsWith(' (복사본)')).toBe(true);
    expect(result.startsWith('가'.repeat(194))).toBe(true);
  });

  it('잘린 끝의 공백은 지워 접미사 앞에 공백이 두 번 오지 않는다', () => {
    // 「 (사본)」은 5자라 원래 이름은 195자까지 남는다 — 195번째가 공백이면 잘라 낸다
    const name = `${'a'.repeat(194)} bcdef`;
    expect(cloneName(name, '(사본)')).toBe(`${'a'.repeat(194)} (사본)`);
  });

  it('이모지(서로게이트 쌍)를 반으로 자르지 않고 코드 포인트로 센다', () => {
    const result = cloneName('😀'.repeat(200), '(복사본)');
    expect(Array.from(result)).toHaveLength(200);
    expect(result).not.toContain('�');
    expect(Array.from(result).slice(0, 194).every((c) => c === '😀')).toBe(true);
  });
});
