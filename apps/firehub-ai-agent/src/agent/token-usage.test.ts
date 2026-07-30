import { describe, expect, it } from 'vitest';

import { totalInputTokens } from './token-usage.js';

/**
 * #336 회귀 방지 — CLI 경로가 캐시 토큰을 빼먹어 컨텍스트 칩이 상시 0%였다.
 * 헬퍼는 세 실행 경로(agent-cli / agent-sdk / process-message)가 공유한다.
 */
describe('totalInputTokens', () => {
  it('캐시 read/creation 토큰을 input_tokens에 합산한다', () => {
    // 이슈에서 관측된 형태: 캐시 히트라 input_tokens는 4뿐이지만 실제 컨텍스트는 4만 토큰대
    expect(
      totalInputTokens({
        input_tokens: 4,
        cache_read_input_tokens: 38_000,
        cache_creation_input_tokens: 2_000,
      }),
    ).toBe(40_004);
  });

  it('캐시 필드가 없으면 input_tokens만 센다', () => {
    expect(totalInputTokens({ input_tokens: 1_234 })).toBe(1_234);
  });

  it('usage가 없거나 비어 있으면 0을 반환한다', () => {
    expect(totalInputTokens(undefined)).toBe(0);
    expect(totalInputTokens(null)).toBe(0);
    expect(totalInputTokens({})).toBe(0);
  });
});
