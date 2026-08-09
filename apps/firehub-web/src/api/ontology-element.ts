import type {
  CreateEntityTypeRequest,
  CreatePropertyRequest,
  CreateRelationRequest,
  EntityTypeDeletion,
  EntityTypeMutation,
  PatchOntologyRequest,
  PropertyMutation,
  RelationMutation,
  UpdateEntityTypeRequest,
  UpdatePropertyRequest,
  UpdateRelationRequest,
  VersionOnly,
} from '@/types/ontology';

import { client } from './client';

// 지식 모델 요소 단위 편집 API(S1). 요소 하나만 보내므로 낙관적 잠금이 필요 없다 — 서로 다른
// 요소를 고치는 것은 충돌이 아니다. Task 6에서 프론트엔드의 full-document PUT 호출부
// (ontologyApi.updateOntologyById)를 제거했고(NEW-5, Task 6 리뷰 라운드2), Task 7에서
// 서버의 PUT /ontology, PUT /ontology/{id}(OntologyController.java) 자체도 삭제했다 —
// 지식 모델 편집은 이제 이 요소 단위 API가 유일한 경로다.
export const ontologyElementApi = {
  patchDomain: (id: number, req: PatchOntologyRequest) =>
    client.patch<VersionOnly>(`/ontology/${id}`, req),
  addEntityType: (id: number, req: CreateEntityTypeRequest) =>
    client.post<EntityTypeMutation>(`/ontology/${id}/entity-types`, req),
  updateEntityType: (id: number, etId: number, req: UpdateEntityTypeRequest) =>
    client.patch<EntityTypeMutation>(`/ontology/${id}/entity-types/${etId}`, req),
  deleteEntityType: (id: number, etId: number) =>
    client.delete<EntityTypeDeletion>(`/ontology/${id}/entity-types/${etId}`),
  addProperty: (id: number, etId: number, req: CreatePropertyRequest) =>
    client.post<PropertyMutation>(`/ontology/${id}/entity-types/${etId}/properties`, req),
  updateProperty: (id: number, etId: number, propId: number, req: UpdatePropertyRequest) =>
    client.patch<PropertyMutation>(`/ontology/${id}/entity-types/${etId}/properties/${propId}`, req),
  deleteProperty: (id: number, etId: number, propId: number) =>
    client.delete<VersionOnly>(`/ontology/${id}/entity-types/${etId}/properties/${propId}`),
  addRelation: (id: number, req: CreateRelationRequest) =>
    client.post<RelationMutation>(`/ontology/${id}/relations`, req),
  updateRelation: (id: number, relId: number, req: UpdateRelationRequest) =>
    client.patch<RelationMutation>(`/ontology/${id}/relations/${relId}`, req),
  deleteRelation: (id: number, relId: number) =>
    client.delete<VersionOnly>(`/ontology/${id}/relations/${relId}`),
};
