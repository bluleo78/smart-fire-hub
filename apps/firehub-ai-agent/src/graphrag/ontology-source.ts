// 추출 시점 온톨로지 소스 — 데이터셋에 바인딩된 온톨로지를 api(DB 소유)에서 fetch한다.
// "기본 온톨로지" 폴백은 존재하지 않는다: 바인딩이 없으면 이 함수는 명확히 실패하고, 호출부가
// 그 실패를 그대로 사용자에게 전달해 "먼저 온톨로지를 연결하라"고 안내해야 한다(#678).
import type { FireHubApiClient } from '../mcp/api-client.js';
import { Ontology, deserializeOntology } from './ontology.js';

/**
 * 데이터셋에 바인딩된 온톨로지를 해소한다.
 *
 * 바인딩 조회 자체가 실패하면(네트워크 오류 등) 그대로 전파한다 — 조용히 진행하면 어떤 온톨로지로
 * 적재/조회했는지 알 수 없는 상태가 되고, 그 실수는 그래프가 이미 잘못 적재된 뒤에야 드러난다.
 * 바인딩이 없으면(ontologyId === null) 사용자에게 그대로 보여줄 수 있는 에러를 던진다.
 */
export async function resolveDatasetOntology(
  apiClient: Pick<FireHubApiClient, 'getDatasetOntology' | 'getOntologyById'>,
  datasetId: number,
): Promise<{ ontology: Ontology; ontologyId: number }> {
  const { ontologyId } = await apiClient.getDatasetOntology(datasetId);
  if (ontologyId == null) {
    throw new Error(
      `데이터셋 ${datasetId}은(는) 온톨로지에 연결되어 있지 않습니다. `
        + '먼저 온톨로지를 연결하세요(graphrag_bind_ontology 또는 데이터셋 상세 화면의 "온톨로지 연결").',
    );
  }
  const ontology = deserializeOntology(await apiClient.getOntologyById(ontologyId));
  return { ontology, ontologyId };
}
