import type {
  CreateOntologyRequest,
  GraphData,
  OntologySchema,
  OntologyStatus,
  OntologySummary,
} from '@/types/ontology';

import { client } from './client';

// 온톨로지 시각화/편집/생명주기 API.
export const ontologyApi = {
  // bare GET /ontology(id 없는 기본 온톨로지 조회)의 프론트 래퍼는 M-2(S2 최종 리뷰)로 제거했다 —
  // by-id 이관(useOntologyById) 이후 프론트 호출부가 하나도 남지 않았다. 백엔드 엔드포인트 자체는
  // ai-agent와 문서 파이프라인이 계속 쓰므로 건드리지 않는다.
  getGraph: () => client.get<GraphData>('/ontology/graph'),
  // 목록. status 미지정 시 서버 기본값(active만)이 적용된다. 관리 화면은 'all'을 넘긴다.
  listOntologies: (status?: OntologyStatus | 'all') =>
    client.get<OntologySummary[]>('/ontologies', { params: status ? { status } : undefined }),
  getOntologyById: (id: number) => client.get<OntologySchema>(`/ontology/${id}`),
  // 신규 생성 — 생성된 온톨로지 id를 201로 반환한다.
  createOntology: (req: CreateOntologyRequest) => client.post<number>('/ontologies', req),
  // 요소 단위 편집(S2)은 ontologyElementApi(ontology-element.ts)가 전담한다 — 전체 스키마를
  // 왕복시키던 id 스코프 full-document PUT(updateOntologyById)은 Task 6에서 제거했다.
  // 상태 전이(활성화/은퇴/복귀) — 스키마 편집과 분리된 전용 경로. 본문 없이 상태만 보내므로
  // 호출부가 대상 온톨로지 스키마를 미리 조회할 필요가 없고, schema_version도 올라가지 않는다.
  updateOntologyStatus: (id: number, status: OntologyStatus) =>
    client.patch<void>(`/ontology/${id}/status`, { status }),
  // 삭제 — 참조 중이거나 기본 온톨로지면 409.
  deleteOntology: (id: number) => client.delete<void>(`/ontology/${id}`),
};
