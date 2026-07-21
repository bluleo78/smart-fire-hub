// 추출 시점 온톨로지 소스 — api(DB 소유)에서 fetch하고, 실패 시 번들 CORE_ONTOLOGY 로 폴백한다.
// (api 무응답에도 추출이 동작하던 기존 속성 보존). ingest 당 1회 호출 → 청크 전반에 재사용(인메모리 캐시).
import type { FireHubApiClient } from '../mcp/api-client.js';
import { Ontology, CORE_ONTOLOGY, deserializeOntology } from './ontology.js';

export async function loadOntology(apiClient: Pick<FireHubApiClient, 'getOntology'>): Promise<Ontology> {
  try {
    return deserializeOntology(await apiClient.getOntology());
  } catch (err) {
    console.warn('[graphrag] 온톨로지 fetch 실패, 번들 CORE_ONTOLOGY 폴백:', err);
    return CORE_ONTOLOGY;
  }
}
