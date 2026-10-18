import { describe, expect, it } from 'vitest';

import { AI_BEHAVIOR_SETTING_SUPPORT } from './ai-credential';

/**
 * WD-59 — 공급자별 실행 설정 반영 여부. firehub-ai-agent `src/providers/types.ts` 의
 * `AGENT_RUN_SETTING_SUPPORT` 와 같은 사실이어야 한다(그쪽 chat.test.ts 에도 같은 표를 고정해 두었다).
 */
describe('AI_BEHAVIOR_SETTING_SUPPORT', () => {
  it('최대 턴 수는 모든 공급자, Temperature·최대 응답 토큰은 opencode 만 반영한다', () => {
    expect(AI_BEHAVIOR_SETTING_SUPPORT).toEqual({
      sdk: { 'ai.max_turns': true, 'ai.temperature': false, 'ai.max_tokens': false },
      cli: { 'ai.max_turns': true, 'ai.temperature': false, 'ai.max_tokens': false },
      'cli-api': { 'ai.max_turns': true, 'ai.temperature': false, 'ai.max_tokens': false },
      opencode: { 'ai.max_turns': true, 'ai.temperature': true, 'ai.max_tokens': true },
    });
  });
});
