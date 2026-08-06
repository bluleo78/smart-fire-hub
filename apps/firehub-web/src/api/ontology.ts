import type {
  CreateOntologyRequest,
  GraphData,
  OntologySchema,
  OntologyStatus,
  OntologySummary,
  UpdateOntologyRequest,
} from '@/types/ontology';

import { client } from './client';

// 온톨로지 시각화/편집/생명주기 API.
export const ontologyApi = {
  getOntology: () => client.get<OntologySchema>('/ontology'),
  getGraph: () => client.get<GraphData>('/ontology/graph'),
  // 목록. status 미지정 시 서버 기본값(active만)이 적용된다. 관리 화면은 'all'을 넘긴다.
  listOntologies: (status?: OntologyStatus | 'all') =>
    client.get<OntologySummary[]>('/ontologies', { params: status ? { status } : undefined }),
  getOntologyById: (id: number) => client.get<OntologySchema>(`/ontology/${id}`),
  // 신규 생성 — 생성된 온톨로지 id를 201로 반환한다.
  createOntology: (req: CreateOntologyRequest) => client.post<number>('/ontologies', req),
  // id 스코프 편집(B-2b) — full-document PUT. 낙관적 동시성: req.schemaVersion 불일치 시 409.
  // 전역 PUT /ontology(id=1 고정)은 백엔드에 하위호환용으로 남아 있지만 웹은 항상 id 스코프로만 편집한다.
  updateOntologyById: (id: number, req: UpdateOntologyRequest) =>
    client.put<OntologySchema>(`/ontology/${id}`, req),
  // 삭제 — 참조 중이거나 기본 온톨로지면 409.
  deleteOntology: (id: number) => client.delete<void>(`/ontology/${id}`),
};
