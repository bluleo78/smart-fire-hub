import type { AxiosInstance } from 'axios';
import type { EntityType } from '../../graphrag/ontology.js';

/** 근접쌍 HITL 검수 대기열 API — firehub-api의 synonym_decision 테이블에 위임. */
export function createSynonymApi(client: AxiosInstance) {
  return {
    // 근접쌍의 기존 결정을 조회한다. 결정이 없으면(첫 발견) undefined.
    async lookupSynonymDecision(
      entityType: EntityType, nameA: string, nameB: string,
    ): Promise<'approved' | 'rejected' | undefined> {
      const { data } = await client.get('/graphrag/synonym-decisions/lookup', {
        params: { entityType, nameA, nameB },
      });
      return data.status === 'approved' || data.status === 'rejected' ? data.status : undefined;
    },
    // LLM이 "같다"고 판정한 근접쌍을 검수 대기열에 등록한다(이미 존재하면 서버가 무시).
    async recordPendingSynonym(
      entityType: EntityType, nameA: string, nameB: string, similarity: number, rationale: string,
    ): Promise<void> {
      await client.post('/graphrag/synonym-decisions/pending', { entityType, nameA, nameB, similarity, rationale });
    },
  };
}
