import type { BindOntologyResponse, DatasetOntologyResponse, MappingResponse, MappingSpec } from '@/types/mapping';

import { client } from './client';

// 데이터셋 매핑(슬라이스 1 B) + 온톨로지 바인딩(슬라이스 0 A) API.
// 검증 위반 시 백엔드가 한국어 메시지의 400을 반환하므로, 호출부는 handleApiError로 그대로 노출한다.
export const mappingApi = {
  // 매핑이 없으면 404. 호출부(useMapping)가 이를 "빈 매핑" 상태로 정규화한다.
  getMapping: (datasetId: number) => client.get<MappingResponse>(`/datasets/${datasetId}/mapping`),
  // 저장은 항상 draft 상태로 들어간다. 활성화는 별도 호출.
  saveMapping: (datasetId: number, spec: MappingSpec) =>
    client.put<MappingResponse>(`/datasets/${datasetId}/mapping`, spec),
  // 저장된 spec을 재검증한 뒤 active로 전환한다.
  activateMapping: (datasetId: number) =>
    client.post<MappingResponse>(`/datasets/${datasetId}/mapping/activate`),
  getBinding: (datasetId: number) => client.get<DatasetOntologyResponse>(`/datasets/${datasetId}/ontology`),
  // upsert — 200 + 결과 본문. 같은 모델 재연결은 멱등이고, 다른 모델로 바꾸면 이전 모델 기준 매핑이 지워진다(WD-184).
  bindOntology: (datasetId: number, ontologyId: number) =>
    client.put<BindOntologyResponse>(`/datasets/${datasetId}/ontology`, { ontologyId }),
};
