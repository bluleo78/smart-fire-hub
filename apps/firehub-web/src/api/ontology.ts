import type { GraphData,OntologySchema, OntologySummary, UpdateOntologyRequest } from '@/types/ontology';

import { client } from './client';

// 온톨로지 시각화/편집 API.
export const ontologyApi = {
  getOntology: () => client.get<OntologySchema>('/ontology'),
  getGraph: () => client.get<GraphData>('/ontology/graph'),
  // 지식 모델 편집(B-2b) — full-document PUT. 낙관적 동시성: req.schemaVersion 불일치 시 409.
  updateOntology: (req: UpdateOntologyRequest) => client.put<OntologySchema>('/ontology', req),
  // 슬라이스 E — 데이터셋 바인딩용. 전역 getOntology()는 id=1 고정이라 다중 온톨로지에 쓸 수 없다.
  listOntologies: () => client.get<OntologySummary[]>('/ontologies'),
  getOntologyById: (id: number) => client.get<OntologySchema>(`/ontology/${id}`),
};
