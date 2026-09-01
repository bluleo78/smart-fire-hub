import type { EvidenceChunk, ReviewItemResponse, ReviewItemType } from '../types/reviewItem';
import { client } from './client';

export const reviewItemsApi = {
  // page/size는 opt-in(#422) — 생략하면 백엔드가 기존처럼 전체를 반환한다. 무한스크롤 훅이 페이지 단위로 호출한다.
  getPending: (itemType?: ReviewItemType, page?: number, size?: number) =>
    client.get<ReviewItemResponse[]>('/graphrag/review-items', {
      params: {
        status: 'pending',
        ...(itemType ? { itemType } : {}),
        ...(size != null ? { page: page ?? 0, size } : {}),
      },
    }),
  approve: (id: number, correctedValue?: string) =>
    client.post<ReviewItemResponse>(`/graphrag/review-items/${id}/approve`, { correctedValue }),
  reject: (id: number) => client.post<ReviewItemResponse>(`/graphrag/review-items/${id}/reject`),
  getEvidence: (id: number) => client.get<EvidenceChunk[]>(`/graphrag/review-items/${id}/evidence`),
};
