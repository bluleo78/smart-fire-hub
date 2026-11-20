/** 숫자 컬럼 판정 단위 테스트(WD-354) */
import { describe, expect, it } from 'vitest';

import {
  hasMixedSignValues,
  isEmptyValue,
  isNonNumericValue,
  isNumericColumn,
  isWeightColumn,
  toPlotNumber,
} from './numeric-columns';

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
  it('공백만 있는 문자열도 빈 값이다(WD-838)', () => {
    expect(isNonNumericValue(' ')).toBe(false);
    expect(isNonNumericValue('\t')).toBe(false);
  });
  it('숫자·숫자 문자열은 false, 문자·불리언은 true', () => {
    expect(isNonNumericValue(5)).toBe(false);
    expect(isNonNumericValue('10.5')).toBe(false);
    expect(isNonNumericValue('서울')).toBe(true);
    expect(isNonNumericValue(true)).toBe(true);
  });
});

describe('빈 값 규칙 통일 (WD-841)', () => {
  const empties: unknown[] = [null, undefined, '', ' ', '\t'];

  it('isEmptyValue: null·undefined·빈 문자열·공백뿐 문자열만 참', () => {
    for (const v of empties) expect(isEmptyValue(v)).toBe(true);
    for (const v of [0, '0', '서울', false]) expect(isEmptyValue(v)).toBe(false);
  });

  it('빈 값은 네 함수가 같은 뜻으로 읽는다', () => {
    for (const v of empties) {
      expect(toPlotNumber(v)).toBeNull();
      expect(isNonNumericValue(v)).toBe(false);
      expect(isWeightColumn('c', [{ c: v }])).toBe(false);
      expect(isNumericColumn('c', [{ c: v }])).toBe(false);
    }
  });

  it('isWeightColumn: 공백은 건너뛰고 숫자가 하나라도 있어야 참', () => {
    expect(isWeightColumn('c', [{ c: ' ' }, { c: '3' }])).toBe(true);
    expect(isWeightColumn('c', [{ c: ' ' }, { c: '  ' }])).toBe(false);
  });

  it('isNumericColumn: 숫자 비율 70% 초과만 참 — 불리언·빈 문자열 컬럼은 거짓', () => {
    expect(isNumericColumn('c', [{ c: '12.50' }, { c: '3' }, { c: '4' }, { c: null }])).toBe(true);
    expect(isNumericColumn('c', [{ c: true }, { c: false }, { c: true }])).toBe(false);
    // WD-841: 빈 값은 분자·분모에서 모두 빼므로 비어 있지 않은 값(1) 은 전부 숫자 → 참(예전 판정은 분모에 빈 값을 세어 거짓이었다)
    expect(isNumericColumn('c', [{ c: '' }, { c: '' }, { c: '1' }])).toBe(true);
  });

  it('isNumericColumn: 빈 값은 분자·분모에서 뺀다(WD-841)', () => {
    const blanks = Array.from({ length: 7 }, () => ({ c: '' }));
    const fives = Array.from({ length: 13 }, () => ({ c: '5' }));
    expect(isNumericColumn('c', [...blanks, ...fives])).toBe(true);
    expect(isNumericColumn('c', [{ c: null }, { c: '3' }])).toBe(true);
    expect(isNumericColumn('c', [{ c: '' }, { c: null }, { c: '  ' }])).toBe(false);
    expect(isNumericColumn('c', [{ c: true }, { c: false }])).toBe(false);
    expect(isNumericColumn('c', [{ c: '서울' }, { c: '부산' }, { c: '1' }])).toBe(false);
  });
});

describe('hasMixedSignValues (WD-843)', () => {
  it('숫자 문자열(DECIMAL)·숫자로 양수와 음수가 모두 있으면 참', () => {
    expect(hasMixedSignValues('c', [{ c: '10' }, { c: '-3.5' }])).toBe(true);
    expect(hasMixedSignValues('c', [{ c: 5 }, { c: -1 }])).toBe(true);
  });

  it('불리언·공백·0·문자는 양수/음수로 세지 않고, 빈 행도 거짓', () => {
    expect(hasMixedSignValues('c', [{ c: true }, { c: -1 }])).toBe(false);
    expect(hasMixedSignValues('c', [{ c: ' ' }, { c: 3 }])).toBe(false);
    expect(hasMixedSignValues('c', [{ c: 0 }, { c: 3 }])).toBe(false);
    expect(hasMixedSignValues('c', [{ c: '서울' }, { c: -2 }])).toBe(false);
    expect(hasMixedSignValues('c', [])).toBe(false);
  });
});
