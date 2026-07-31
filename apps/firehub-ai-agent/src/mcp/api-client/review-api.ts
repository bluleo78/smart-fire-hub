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
    // 저신뢰 관계 기존 검수 결정 조회. 없으면 undefined.
    async lookupRelationDecision(
      subjectKey: string, relType: string, objectKey: string,
    ): Promise<'approved' | 'rejected' | undefined> {
      const { data } = await client.get('/graphrag/review-items/relation/lookup', {
        params: { subjectKey, relType, objectKey },
      });
      return data.status === 'approved' || data.status === 'rejected' ? data.status : undefined;
    },
    // 저신뢰 관계를 검수 대기열에 등록(이미 존재하면 서버가 무시).
    async recordPendingRelation(item: {
      datasetId: number; subjectKey: string; relType: string; objectKey: string;
      subjectName: string; objectName: string; sourceChunkIds: number[];
      confidence: number; reason?: string;
    }): Promise<void> {
      await client.post('/graphrag/review-items/relation/pending', item);
    },

    // ── 검수 인박스 소비측(기존에는 firehub-web 전용이라 에이전트가 등록만 하고 조회·결정은 못 했다) ──

    // 검수 항목 목록. status 생략 시 서버 기본값 pending. 허용 외 status 는 서버가 400.
    async listReviewItems(status?: string, itemType?: string): Promise<ReviewItem[]> {
      const { data } = await client.get('/graphrag/review-items', { params: { status, itemType } });
      return data;
    },
    // 판단 근거 — 항목이 유래한 원문 청크 스니펫.
    async getReviewItemEvidence(id: number): Promise<{ chunkId: number; content: string }[]> {
      const { data } = await client.get(`/graphrag/review-items/${id}/evidence`);
      return data;
    },
    // 승인 — 그래프를 실제로 변경한 뒤 status 갱신(속성 항목은 correctedValue 필수).
    async approveReviewItem(id: number, correctedValue?: string): Promise<ReviewItem> {
      const { data } = await client.post(`/graphrag/review-items/${id}/approve`, { correctedValue });
      return data;
    },
    // 거부 — 그래프 변경 없이 status 만 rejected.
    async rejectReviewItem(id: number): Promise<ReviewItem> {
      const { data } = await client.post(`/graphrag/review-items/${id}/reject`);
      return data;
    },
  };
}

/**
 * 검수 항목 응답. payload 는 itemType 별로 형태가 다른 자유 JSON 이라 unknown 으로 두고,
 * 호출부(MCP 도구)가 타입별로 정규화한다 — 여기서 타입을 좁히면 백엔드 payload 변경에
 * 클라이언트가 조용히 어긋난다.
 */
export interface ReviewItem {
  id: number;
  itemType: string;
  status: string;
  datasetId: number | null;
  signalType: string | null;
  signalScore: number | null;
  reason: string | null;
  payload: Record<string, unknown>;
  decidedBy: number | null;
  decidedAt: string | null;
  createdAt: string;
}
