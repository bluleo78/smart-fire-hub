import { describe, expect, it } from 'vitest';

import { isSameFormValue } from './form-equality';

/** 이탈 가드 dirty 판정용 폼 값 비교 (WD-642) */
describe('isSameFormValue', () => {
  it("빈 값(''/null/undefined/NaN)은 서로 같다", () => {
    expect(isSameFormValue('', undefined)).toBe(true);
    expect(isSameFormValue(null, '')).toBe(true);
    expect(isSameFormValue(Number.NaN, undefined)).toBe(true);
    expect(isSameFormValue({ a: '' }, {})).toBe(true);
  });

  it('빈 값과 실제 값은 다르다 — 0·false 는 빈 값이 아니다', () => {
    expect(isSameFormValue('', 'a')).toBe(false);
    expect(isSameFormValue(undefined, 0)).toBe(false);
    expect(isSameFormValue(null, false)).toBe(false);
  });

  it('원시 값은 그대로 비교한다', () => {
    expect(isSameFormValue(255, 255)).toBe(true);
    expect(isSameFormValue(255, 256)).toBe(false);
    expect(isSameFormValue('255', 255)).toBe(false);
  });

  it('객체는 키 순서와 없는 키(빈 값)를 무시하고 재귀로 비교한다', () => {
    expect(isSameFormValue({ a: 1, b: { c: 'x' } }, { b: { c: 'x' }, a: 1, d: undefined })).toBe(true);
    expect(isSameFormValue({ a: 1, b: { c: 'x' } }, { a: 1, b: { c: 'y' } })).toBe(false);
  });

  it('배열은 길이와 각 원소를 재귀로 비교한다', () => {
    const col = { columnName: '', dataType: 'TEXT', maxLength: undefined };
    expect(isSameFormValue([col], [{ dataType: 'TEXT', columnName: '' }])).toBe(true);
    expect(isSameFormValue([col], [col, col])).toBe(false);
    expect(isSameFormValue([col], [{ ...col, maxLength: 255 }])).toBe(false);
    expect(isSameFormValue([[1, 2]], [[1, 2]])).toBe(true);
    expect(isSameFormValue([[1, 2]], [[2, 1]])).toBe(false);
    expect(isSameFormValue([], {})).toBe(false);
  });

  it('Date 는 시각으로 비교한다', () => {
    expect(isSameFormValue(new Date(1000), new Date(1000))).toBe(true);
    expect(isSameFormValue(new Date(1000), new Date(2000))).toBe(false);
  });

  it('평범하지 않은 객체는 같은 참조일 때만 같다 — 키가 없다고 같다고 보지 않는다', () => {
    const map = new Map([['a', 1]]);
    expect(isSameFormValue(map, map)).toBe(true);
    expect(isSameFormValue(new Map([['a', 1]]), new Map([['a', 2]]))).toBe(false);
    expect(isSameFormValue({ m: new Map() }, { m: new Map() })).toBe(false);
  });
});
