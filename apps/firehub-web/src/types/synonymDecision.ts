/** synonym_decision API 응답 — 근접쌍 HITL 검수 대기열 1건. */
export interface SynonymDecisionResponse {
  id: number;
  entityType: string;
  nameA: string;
  nameB: string;
  status: 'pending' | 'approved' | 'rejected';
  similarity: number | null;
  rationale: string | null;
  decidedBy: number | null;
  decidedAt: string | null;
  createdAt: string | null;
}
