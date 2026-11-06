/** 숫자 컬럼 판정 단위 테스트(WD-354) */
import { describe, expect, it } from 'vitest';

import { isNonNumericValue, isWeightColumn } from './numeric-columns';

describe('isWeightColumn', () => {
  it('숫자·숫자 문자열(DECIMAL)만 있으면 참 — NULL·빈 값은 세지 않는다', () => {
    expect(isWeightColumn('w', [{ w: 1 }, { w: '0.04' }, { w: null }, { w: '' }])).toBe(true);
  });

  it('문자 값이 하나라도 있거나, 값이 모두 비어 있거나, 도형·참거짓이면 거짓', () => {
    expect(isWeightColumn('w', [{ w: 1 }, { w: '가' }])).toBe(false);
    expect(isWeightColumn('w', [{ w: null }, {}])).toBe(false);
    expect(isWeightColumn('g', [{ g: { type: 'Point', coordinates: [127, 37] } }])).toBe(false);
    expect(isWeightColumn('b', [{ b: true }])).toBe(false);
  });
});

describe('isNonNumericValue(WD-505)', () => {
  it('빈 값은 숫자 아님으로 보지 않는다(뷰가 0 으로 그린다)', () => {
    expect(isNonNumericValue(null)).toBe(false);
    expect(isNonNumericValue(undefined)).toBe(false);
    expect(isNonNumericValue('')).toBe(false);
  });
  it('숫자·숫자 문자열은 false, 문자·불리언은 true', () => {
    expect(isNonNumericValue(5)).toBe(false);
    expect(isNonNumericValue('10.5')).toBe(false);
    expect(isNonNumericValue('서울')).toBe(true);
    expect(isNonNumericValue(true)).toBe(true);
  });
});
