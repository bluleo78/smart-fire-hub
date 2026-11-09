import { describe, expect, it } from 'vitest';

import { isValidId, parseRouteId, toValidId } from './route-id';

/** 주소 id 조각 판정 (WD-434) */
describe('parseRouteId', () => {
  it('숫자만으로 된 양의 정수는 그 값을 돌려준다', () => {
    expect(parseRouteId('15')).toBe(15);
    expect(parseRouteId('007')).toBe(7);
  });

  it('조각이 없으면 undefined 다(새로 만들기 경로)', () => {
    expect(parseRouteId(undefined)).toBeUndefined();
  });

  it('숫자만이 아니면 null — 앞자리 숫자만 읽지 않는다', () => {
    for (const raw of ['abc', '15abc', '1e3', '1.5', '-3', ' 1', '', 'NaN']) {
      expect(parseRouteId(raw)).toBeNull();
    }
  });

  it('0 과 안전한 정수 범위 밖은 null 이다', () => {
    expect(parseRouteId('0')).toBeNull();
    expect(parseRouteId('9007199254740993')).toBeNull();
  });
});

/** 형식 없는 값의 조회 가능 판정 — 안전한 정수만 id 로 받는다 (WD-671) */
describe('isValidId · toValidId', () => {
  it('안전한 양의 정수만 참이다', () => {
    expect(isValidId(1)).toBe(true);
    expect(isValidId(Number.MAX_SAFE_INTEGER)).toBe(true);
    for (const v of [0, -1, 1.5, NaN, Infinity, null, undefined]) {
      expect(isValidId(v as number | null | undefined)).toBe(false);
    }
  });

  it('정밀도를 잃은 큰 수(1e21·MAX_SAFE_INTEGER+1)는 정수여도 거짓이다', () => {
    expect(isValidId(1e21)).toBe(false);
    expect(isValidId(Number.MAX_SAFE_INTEGER + 1)).toBe(false);
  });

  it('toValidId 는 숫자·숫자 문자열을 같은 규칙으로 읽는다', () => {
    expect(toValidId(7)).toBe(7);
    expect(toValidId('7')).toBe(7);
    expect(toValidId(1e21)).toBeNull();
    expect(toValidId('1e21')).toBeNull();
    expect(toValidId('abc')).toBeNull();
    expect(toValidId({})).toBeNull();
  });
});
