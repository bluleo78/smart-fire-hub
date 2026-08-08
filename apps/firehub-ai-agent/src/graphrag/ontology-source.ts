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

/**
 * 데이터셋에 **바인딩된** 온톨로지를 해소한다. 문서 적재(ingest)가 이전에 GET /ontology(=id 1 고정)를
 * 읽어 새로 만든 온톨로지로는 적재가 되지 않던 문제를 고친다. 표 투영 경로는 이미 바인딩을 쓰므로
 * 두 경로의 동작을 맞추는 것이기도 하다.
 *
 * 폴백 순서: 바인딩 → 기본(GET /ontology) → 번들 CORE_ONTOLOGY.
 *
 * try 는 **바인딩 조회에만** 걸린다. "어떤 온톨로지를 쓸지 모르겠다"와 "쓸 온톨로지는 아는데
 * 못 가져왔다"는 다른 상황이기 때문이다. 후자를 삼켜 기본 온톨로지로 진행하면 이 함수가 고치려던
 * 버그(id=1 스키마로 적재)가 오류 경로로 되살아나고, recordGraphIngest 가 남기는
 * schemaVersionAtIngest 까지 틀린 값이 된다 — 그래프가 이미 잘못 적재된 뒤에야 드러난다.
 * 그래서 바인딩이 있는데 본체를 못 가져오면 폴백하지 않고 throw 한다.
 */
export async function resolveDatasetOntology(
  apiClient: Pick<FireHubApiClient, 'getOntology' | 'getDatasetOntology' | 'getOntologyById'>,
  datasetId: number,
): Promise<{ ontology: Ontology; ontologyId: number | null; source: 'binding' | 'default' | 'bundled-fallback' }> {
  let ontologyId: number | null = null;
  try {
    ontologyId = (await apiClient.getDatasetOntology(datasetId)).ontologyId;
  } catch (err) {
    // 바인딩 자체를 못 읽은 경우만 기존 동작(기본 온톨로지)으로 계속한다.
    console.warn('[graphrag] 온톨로지 바인딩 조회 실패, 기본 온톨로지로 진행:', err);
  }
  if (ontologyId != null) {
    // 여기서 실패하면 폴백하지 않는다 — 위 주석 참조.
    const ontology = deserializeOntology(await apiClient.getOntologyById(ontologyId));
    return { ontology, ontologyId, source: 'binding' };
  }
  const fallback = await loadOntologyWithSource(apiClient);
  return {
    ontology: fallback.ontology,
    ontologyId: null,
    source: fallback.source === 'db' ? 'default' : 'bundled-fallback',
  };
}
