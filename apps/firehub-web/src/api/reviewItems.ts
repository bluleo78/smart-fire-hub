import type { EvidenceChunk, ReviewItemResponse, ReviewItemType } from '../types/reviewItem';
import { client } from './client';

export const reviewItemsApi = {
  getPending: (itemType?: ReviewItemType) =>
    client.get<ReviewItemResponse[]>('/graphrag/review-items', {
      params: { status: 'pending', ...(itemType ? { itemType } : {}) },
    }),
  approve: (id: number, correctedValue?: string) =>
    client.post<ReviewItemResponse>(`/graphrag/review-items/${id}/approve`, { correctedValue }),
  reject: (id: number) => client.post<ReviewItemResponse>(`/graphrag/review-items/${id}/reject`),
  getEvidence: (id: number) => client.get<EvidenceChunk[]>(`/graphrag/review-items/${id}/evidence`),
};
