import type { SynonymDecisionResponse } from '../types/synonymDecision';
import { client } from './client';

export const synonymDecisionsApi = {
  getPending: () =>
    client.get<SynonymDecisionResponse[]>('/graphrag/synonym-decisions', { params: { status: 'pending' } }),
  approve: (id: number) => client.post<SynonymDecisionResponse>(`/graphrag/synonym-decisions/${id}/approve`),
  reject: (id: number) => client.post<SynonymDecisionResponse>(`/graphrag/synonym-decisions/${id}/reject`),
};
