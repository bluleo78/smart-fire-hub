import { describe, it, expect } from 'vitest';
import {
  EMBED_BATCH_SIZE,
  EMBED_MAX_TEXT_LENGTH,
  MAX_SEARCH_QUERY_LENGTH,
  clampSearchQuery,
  truncateUtf16,
} from './request-limits.js';

/**
 * request-limits.ts 테스트(WD-600) — api 요청 크기 상한 사본과 자르기 규칙.
 * 상한 값이 api(EmbedRequest·SearchQueryLimits)와 어긋나면 400 이 다시 나므로 값 자체도 고정해 둔다.
 */
describe('request-limits', () => {
  it('상한 값은 api 상수와 같다', () => {
    expect(MAX_SEARCH_QUERY_LENGTH).toBe(1000);
    expect(EMBED_BATCH_SIZE).toBe(256);
    expect(EMBED_MAX_TEXT_LENGTH).toBe(2000);
  });

  it('truncateUtf16 은 상한 이하 문자열을 그대로 돌려준다', () => {
    expect(truncateUtf16('abc', 3)).toBe('abc');
    expect(truncateUtf16('', 3)).toBe('');
  });

  it('truncateUtf16 은 상한(UTF-16 코드 단위)으로 자른다', () => {
    expect(truncateUtf16('가'.repeat(10), 4)).toBe('가'.repeat(4));
  });

  it('truncateUtf16 은 서로게이트 쌍을 반으로 가르지 않는다', () => {
    // 'a' + 🔥(2 코드 단위) — 2에서 자르면 🔥 의 앞 절반만 남으므로 그것도 버린다.
    const cut = truncateUtf16('a🔥b', 2);
    expect(cut).toBe('a');
  });

  it('clampSearchQuery 는 1,000자로 자른다', () => {
    expect(clampSearchQuery('x'.repeat(1500))).toHaveLength(1000);
    expect(clampSearchQuery('짧은 질의')).toBe('짧은 질의');
  });
});
