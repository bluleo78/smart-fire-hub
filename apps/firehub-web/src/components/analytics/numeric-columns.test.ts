/** 숫자 컬럼 판정 단위 테스트(WD-354) */
import { describe, expect, it } from 'vitest';

import { isWeightColumn } from './numeric-columns';

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
