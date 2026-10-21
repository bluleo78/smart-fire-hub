import { describe, expect, it } from 'vitest';

import { getPageAfterDelete, parseNonNegativeInt } from './pagination';

describe('getPageAfterDelete', () => {
  it('첫 페이지(0)에서는 항목이 몇 개 남았든 페이지를 보정하지 않는다', () => {
    expect(getPageAfterDelete(0, 0)).toBe(0);
    expect(getPageAfterDelete(1, 0)).toBe(0);
    expect(getPageAfterDelete(5, 0)).toBe(0);
  });

  it('2페이지 이상에서 삭제 대상이 해당 페이지의 마지막 1건이면 이전 페이지로 보정한다', () => {
    expect(getPageAfterDelete(1, 1)).toBe(0);
    expect(getPageAfterDelete(1, 3)).toBe(2);
  });

  it('현재 페이지에 여전히 다른 항목이 남아있으면 페이지를 보정하지 않는다', () => {
    expect(getPageAfterDelete(2, 1)).toBe(1);
    expect(getPageAfterDelete(10, 4)).toBe(4);
  });
});

// WD-146: 손으로 고친 주소(?page=abc)를 그대로 보내 서버 400 이 빈 목록처럼 보이던 결함
describe('parseNonNegativeInt', () => {
  it('0 이상 정수는 그대로 읽는다', () => {
    expect(parseNonNegativeInt('0', 9)).toBe(0);
    expect(parseNonNegativeInt('3', 9)).toBe(3);
    expect(parseNonNegativeInt('999', 9)).toBe(999);
  });

  it('없거나 비었으면 fallback', () => {
    expect(parseNonNegativeInt(null, 0)).toBe(0);
    expect(parseNonNegativeInt('', 10)).toBe(10);
    expect(parseNonNegativeInt('  ', 10)).toBe(10);
  });

  it('숫자가 아니거나 음수·소수면 fallback', () => {
    expect(parseNonNegativeInt('abc', 0)).toBe(0);
    expect(parseNonNegativeInt('-1', 0)).toBe(0);
    expect(parseNonNegativeInt('1.5', 0)).toBe(0);
    expect(parseNonNegativeInt('Infinity', 0)).toBe(0);
  });
});
