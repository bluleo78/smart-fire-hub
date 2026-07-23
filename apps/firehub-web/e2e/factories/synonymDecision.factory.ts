import type { SynonymDecisionResponse } from '../../src/types/synonymDecision';

export function createSynonymDecision(overrides: Partial<SynonymDecisionResponse> = {}): SynonymDecisionResponse {
  return {
    id: 1,
    entityType: 'Cause',
    nameA: '전기적 요인',
    nameB: '분전반의 누전',
    status: 'pending',
    similarity: 0.707,
    rationale: '둘 다 분전반 누전을 지칭함',
    decidedBy: null,
    decidedAt: null,
    createdAt: '2026-07-23T09:00:00',
    ...overrides,
  };
}
