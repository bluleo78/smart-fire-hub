/**
 * location-path 단위 테스트 (WD-673)
 *
 * 이탈 가드가 「같은 화면으로 돌아왔는가」를 판정할 때, 표기만 다른 같은 위치(원문 한글·공백 vs 퍼센트 인코딩,
 * `+` vs `%20`, 16진수 대소문자)는 같다고, 실제로 다른 위치(예약 문자 `%2F` vs `/`)는 다르다고 보는지 확인한다.
 */
import { describe, expect, it } from 'vitest';

import { isSameLocationPath, normalizeLocationPath } from './location-path';

describe('isSameLocationPath (WD-673)', () => {
  it.each([
    ['한글 경로', '/data/검색', '/data/%EA%B2%80%EC%83%89'],
    ['경로의 공백', '/data/a b', '/data/a%20b'],
    ['쿼리의 한글·공백', '/form?q=한글 값', '/form?q=%ED%95%9C%EA%B8%80%20%EA%B0%92'],
    ['쿼리 공백의 + 와 %20', '/form?q=a+b', '/form?q=a%20b'],
    ['16진수 대소문자', '/data/%ea%b2%80', '/data/%EA%B2%80'],
    ['해시의 한글', '/form#요약', '/form#%EC%9A%94%EC%95%BD'],
    ['이미 인코딩된 쪽끼리', '/form?q=%ED%95%9C', '/form?q=%ED%95%9C'],
  ])('%s — 같은 위치로 본다', (_label, a, b) => {
    expect(isSameLocationPath(a, b)).toBe(true);
    expect(isSameLocationPath(b, a)).toBe(true);
  });

  it.each([
    ['다른 경로', '/form', '/form2'],
    ['다른 쿼리 값', '/form?q=한글', '/form?q=한국'],
    ['예약 문자 %2F 와 /', '/a%2Fb', '/a/b'],
    ['쿼리의 %2B(더하기)와 +(공백)', '/form?q=a%2Bb', '/form?q=a+b'],
    ['해시 유무', '/form', '/form#x'],
  ])('%s — 다른 위치로 본다', (_label, a, b) => {
    expect(isSameLocationPath(a, b)).toBe(false);
  });

  it('잘못된 퍼센트 시퀀스도 예외 없이 비교한다', () => {
    expect(() => normalizeLocationPath('/a/%E0%A4?q=%E0%A4#%E0%A4')).not.toThrow();
    expect(isSameLocationPath('/a/%E0%A4?q=%zz', '/a/%E0%A4?q=%zz')).toBe(true);
    expect(isSameLocationPath('/a/%E0%A4', '/a/%E0%A5')).toBe(false);
  });
});
