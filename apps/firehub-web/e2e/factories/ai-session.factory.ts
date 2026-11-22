/**
 * AI 대화(세션) 목록 API 모킹 데이터 팩토리 (WD-951)
 * AISession 타입을 그대로 써서 API 스키마가 바뀌면 시험이 컴파일 오류로 알린다.
 */

import type { AISession } from '../../src/types/ai';

/** AI 대화 한 건을 만든다 — 시험에 중요한 값만 덮어쓴다 */
export function createAISession(overrides?: Partial<AISession>): AISession {
  return {
    id: 1,
    sessionId: 'session-1',
    title: '테스트 대화',
    createdAt: '2026-11-23T00:00:00Z',
    updatedAt: '2026-11-23T00:00:00Z',
    ...overrides,
  };
}
