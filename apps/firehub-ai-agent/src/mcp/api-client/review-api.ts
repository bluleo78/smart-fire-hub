import type { AxiosInstance } from 'axios';
import type { EntityType } from '../../graphrag/ontology.js';

/** 범용 AI 검수 인박스 API 클라이언트 — firehub-api graph_review_item에 위임. */
export function createReviewApi(client: AxiosInstance) {
  return {
    // 동의어 근접쌍 기존 결정 조회. 결정 없으면(첫 발견) undefined.
    async lookupSynonymDecision(
      entityType: EntityType, nameA: string, nameB: string,
    ): Promise<'approved' | 'rejected' | undefined> {
      const { data } = await client.get('/graphrag/review-items/synonym/lookup', {
        params: { entityType, nameA, nameB },
      });
      return data.status === 'approved' || data.status === 'rejected' ? data.status : undefined;
    },
    // LLM "같다" 판정 근접쌍을 검수 대기열에 등록(이미 존재하면 서버가 무시). datasetId/sourceChunkIds는 원문 근거용(선택).
    async recordPendingSynonym(
      entityType: EntityType, nameA: string, nameB: string, similarity: number, rationale: string,
      datasetId?: number, sourceChunkIds?: number[],
    ): Promise<void> {
      await client.post('/graphrag/review-items/synonym/pending', {
        entityType, nameA, nameB, similarity, rationale, datasetId, sourceChunkIds,
      });
    },
    // 정규화 실패 속성을 검수 대기열에 등록. entityKey는 canonical 최종 key.
    async recordPropertyReview(
      datasetId: number, chunkId: number, entityKey: string, entityType: EntityType,
      propertyName: string, dataType: 'text' | 'number' | 'date', rawText: string,
    ): Promise<void> {
      await client.post('/graphrag/review-items/property/pending', {
        datasetId, chunkId, entityKey, entityType, propertyName, dataType, rawText,
      });
    },
    // 저신뢰 엔티티 기존 검수 결정 조회. 없으면 undefined.
    async lookupEntityDecision(
      entityType: EntityType, name: string,
    ): Promise<'approved' | 'rejected' | undefined> {
      const { data } = await client.get('/graphrag/review-items/entity/lookup', { params: { entityType, name } });
      return data.status === 'approved' || data.status === 'rejected' ? data.status : undefined;
    },
    // 저신뢰 엔티티(+보류 관계)를 검수 대기열에 등록(이미 존재하면 서버가 무시).
    async recordPendingEntity(item: {
      datasetId: number; entityType: EntityType; name: string;
      properties?: Record<string, number | string>; sourceChunkIds: number[];
      confidence: number; reason?: string;
      relations: { relType: string; direction: 'out' | 'in'; otherKey: string }[];
    }): Promise<void> {
      await client.post('/graphrag/review-items/entity/pending', item);
    },
  };
}
