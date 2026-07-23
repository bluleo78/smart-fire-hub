import type { GraphData,OntologySchema, UpdateOntologyRequest } from '@/types/ontology';

import { client } from './client';

// 온톨로지 시각화/편집 API.
export const ontologyApi = {
  getOntology: () => client.get<OntologySchema>('/ontology'),
  getGraph: () => client.get<GraphData>('/ontology/graph'),
  // 지식 모델 편집(B-2b) — full-document PUT. 낙관적 동시성: req.schemaVersion 불일치 시 409.
  updateOntology: (req: UpdateOntologyRequest) => client.put<OntologySchema>('/ontology', req),
};
