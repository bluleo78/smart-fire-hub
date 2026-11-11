import { describe, expect, it } from 'vitest';

import { availableSearchModes } from './usePlatformDbCapabilities';

/** 켜진 기능 조합별 검색 모드 노출 규칙(WD-495) — HYBRID 는 두 기능 모두 필요하다. */
describe('availableSearchModes', () => {
  it('둘 다 켜짐: HYBRID, SEMANTIC, KEYWORD 순서', () => {
    expect(availableSearchModes({ vectorSearch: true, trigramSearch: true })).toEqual([
      'HYBRID',
      'SEMANTIC',
      'KEYWORD',
    ]);
  });

  it('벡터만 켜짐: SEMANTIC', () => {
    expect(availableSearchModes({ vectorSearch: true, trigramSearch: false })).toEqual(['SEMANTIC']);
  });

  it('트라이그램만 켜짐: KEYWORD', () => {
    expect(availableSearchModes({ vectorSearch: false, trigramSearch: true })).toEqual(['KEYWORD']);
  });

  it('둘 다 꺼짐: 없음', () => {
    expect(availableSearchModes({ vectorSearch: false, trigramSearch: false })).toEqual([]);
  });
});
