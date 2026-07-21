import type { GraphData,OntologySchema } from '@/types/ontology';

import { client } from './client';

// 온톨로지 시각화 API — 읽기 전용.
export const ontologyApi = {
  getOntology: () => client.get<OntologySchema>('/ontology'),
  getGraph: () => client.get<GraphData>('/ontology/graph'),
};
