// 추출 시점 온톨로지 소스 — api(DB 소유)에서 fetch하고, 실패 시 번들 CORE_ONTOLOGY 로 폴백한다.
// (api 무응답에도 추출이 동작하던 기존 속성 보존). ingest 당 1회 호출 → 청크 전반에 재사용(인메모리 캐시).
import type { FireHubApiClient } from '../mcp/api-client.js';
import { Ontology, CORE_ONTOLOGY, deserializeOntology } from './ontology.js';

export async function loadOntology(apiClient: Pick<FireHubApiClient, 'getOntology'>): Promise<Ontology> {
  return (await loadOntologyWithSource(apiClient)).ontology;
}

/**
 * loadOntology 와 동일하되 "DB 응답인지 번들 폴백인지"를 함께 반환한다.
 * 폴백은 추출 경로에서는 무해한 안전망이지만, 스키마를 사용자에게 **광고**하는 경로
 * (graphrag_describe_ontology)에서는 낡은 하드코딩 스키마를 사실처럼 제시하게 되므로
 * 호출부가 출처를 구분해 응답할 수 있어야 한다.
 */
export async function loadOntologyWithSource(
  apiClient: Pick<FireHubApiClient, 'getOntology'>,
): Promise<{ ontology: Ontology; source: 'db' | 'bundled-fallback' }> {
  try {
    return { ontology: deserializeOntology(await apiClient.getOntology()), source: 'db' };
  } catch (err) {
    console.warn('[graphrag] 온톨로지 fetch 실패, 번들 CORE_ONTOLOGY 폴백:', err);
    return { ontology: CORE_ONTOLOGY, source: 'bundled-fallback' };
  }
}
