/**
 * Anthropic usage 객체의 "컨텍스트 크기" 계산 헬퍼 (#336).
 *
 * 프롬프트 캐싱이 켜지면 `input_tokens`는 캐시 미스분만 세므로 실제 컨텍스트의 극히 일부다.
 * 컨텍스트 사용량 칩·컴팩션 경고는 전체 컨텍스트 크기를 기준으로 하므로
 * 캐시 read/creation 토큰을 반드시 합산해야 한다.
 *
 * 세 실행 경로(agent-cli / agent-sdk / process-message)가 같은 계산을 복제하고 있었고
 * CLI 경로만 합산을 빠뜨려 칩이 상시 0%였다 — 재발 방지를 위해 한 곳으로 모은다.
 */
export interface TokenUsageLike {
  input_tokens?: number;
  cache_read_input_tokens?: number;
  cache_creation_input_tokens?: number;
}

/** 캐시 토큰까지 합산한 입력(컨텍스트) 토큰 수. usage가 없으면 0. */
export function totalInputTokens(usage: TokenUsageLike | null | undefined): number {
  if (!usage) return 0;
  return (
    (usage.input_tokens ?? 0) +
    (usage.cache_read_input_tokens ?? 0) +
    (usage.cache_creation_input_tokens ?? 0)
  );
}
