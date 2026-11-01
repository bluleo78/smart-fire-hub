import { describe, expect, it } from 'vitest';

import { parseRouteId } from './route-id';

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
