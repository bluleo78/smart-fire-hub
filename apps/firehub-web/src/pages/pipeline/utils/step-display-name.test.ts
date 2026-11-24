import { describe, expect, it } from 'vitest';

import { isUnnamedStep, stepDisplayName } from './step-display-name';

describe('stepDisplayName', () => {
  it('이름이 있으면 원문 그대로 돌려준다(자르지 않음)', () => {
    expect(stepDisplayName('적재', 0)).toBe('적재');
    expect(stepDisplayName('  앞뒤 공백  ', 1)).toBe('  앞뒤 공백  ');
  });

  it('이름이 없으면 「스텝 #n」(n 은 1부터)으로 부른다', () => {
    expect(stepDisplayName('', 0)).toBe('스텝 #1');
    expect(stepDisplayName('   ', 2)).toBe('스텝 #3');
    expect(stepDisplayName(null, 4)).toBe('스텝 #5');
    expect(stepDisplayName(undefined, 0)).toBe('스텝 #1');
  });
});

describe('isUnnamedStep', () => {
  it('빈 문자열·공백뿐·null 은 이름 없음', () => {
    expect(isUnnamedStep('')).toBe(true);
    expect(isUnnamedStep('  ')).toBe(true);
    expect(isUnnamedStep(null)).toBe(true);
  });

  it('글자가 있으면 이름 있음', () => {
    expect(isUnnamedStep('a')).toBe(false);
  });
});
