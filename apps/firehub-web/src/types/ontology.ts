// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
// id(S2): ontology_entity_property.id — 요소 단위 편집 API가 대상을 지목하는 주소.
// 이름으로 지목하면 리네임과 동시 수정이 서로를 덮어쓴다. 신규 삽입 대상 등 id 없는 경우도
// 있어 옵셔널이다(백엔드 OntologyResponse.Property의 4-인자 하위호환 생성자 참고).
export interface Property { id?: number; name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { id?: number; type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
// id/subjectTypeId/objectTypeId(S2): 저장은 FK 기반이며 요소 단위 편집 API는 이 id들로 대화한다.
// subject/object는 사람이 읽는 타입 "이름"을 계속 담는다 — 시각화·챗 등 이름 기반 소비자가 많다.
export interface Triple { id?: number; subject: string; relation: string; object: string; description: string; subjectTypeId?: number; objectTypeId?: number; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// 온톨로지 생명주기 상태. draft=미완성 초안, active=운영 중, archived=은퇴(신규 바인딩 불가, 기존 적재 보존).
export type OntologyStatus = 'draft' | 'active' | 'archived';

// 상태별 한글 라벨. OntologySelect(배지)와 OntologyManageDialog(테이블)가 공유한다 — 두 곳에 각각
// 정의하면 라벨을 바꿀 때 한쪽만 고쳐 화면마다 다른 문구가 뜨는 회귀가 나기 쉽다.
// active를 배지로 보여줄지 말지는 소비하는 컴포넌트의 판단이라 이 맵에는 넣지 않는다.
export const ONTOLOGY_STATUS_LABEL: Record<OntologyStatus, string> = {
  draft: '초안',
  active: '활성',
  archived: '은퇴',
};

// schemaVersion: 적재 당시 온톨로지 schema_version(5-4). 스탬프 도입 이전 레거시 노드는 null —
// "값 없음"과 "구버전"을 UI가 혼동하지 않도록 별도 상태로 다룬다(NodeDetailDrawer 참조).
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; schemaVersion: number | null; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }

// 다중 온톨로지 목록(GET /api/v1/ontologies). 관리 다이얼로그가 쓰는 카운트·수정일을 포함한다.
// isDefault: 문서 적재가 단수 /ontology로 의존하는 기본 온톨로지인지 — 서버(OntologyService)가 판정해
// 내려준다. "기본 온톨로지" 판정 기준이 바뀌어도(id 대신 플래그 컬럼 등) 프론트가 매직넘버를 다시
// 선언할 필요가 없도록 하기 위함이다. 삭제 불가 사유 문구는 프론트가 그대로 표현한다.
export interface OntologySummary {
  id: number;
  domain: string;
  schemaVersion: number;
  status: OntologyStatus;
  entityCount: number;
  datasetCount: number;
  updatedAt: string;
  isDefault: boolean;
}

// POST /api/v1/ontologies — 신규 생성. 챗은 'draft'로, UI 생성 폼도 'draft'로 만든 뒤 사람이 활성화한다.
export interface CreateOntologyRequest {
  domain: string;
  entities: EntityTypeDef[];
  relations: Triple[];
  status: OntologyStatus;
}

// 요소 단위 편집(S2) 응답 계약. 전체 스키마를 왕복시키는 PUT과 달리 요소 하나만 오간다.
// 모든 뮤테이션이 새 schemaVersion을 싣는다 — 클라이언트는 이 값을 "내가 유발한 증가분"과
// 비교해 남의 변경을 감지한다(409 낙관적 잠금의 대체).
export interface ElementMutationBase { schemaVersion: number }
export interface EntityTypeMutation extends ElementMutationBase { entityType: EntityTypeDef }
// deletedRelationIds: FK CASCADE로 함께 사라진 관계들. DB cascade는 조용하므로 서버가 삭제 직전에
// 조회해 담아 준다 — 클라이언트가 낙관적 갱신에서 무엇을 지워야 할지 알 수 있게.
export interface EntityTypeDeletion extends ElementMutationBase { deletedRelationIds: number[] }
export interface PropertyMutation extends ElementMutationBase { property: Property }
export interface RelationMutation extends ElementMutationBase { relation: Triple }
export type VersionOnly = ElementMutationBase;

export interface PatchOntologyRequest { domain: string }
export interface CreateEntityTypeRequest {
  type: string; description: string; naming: string; resolution: 'embedding' | 'exact';
}
// PATCH 의미론: null은 "변경 없음"이다. 빈 문자열과 구분된다.
export interface UpdateEntityTypeRequest {
  type?: string | null; description?: string | null; naming?: string | null;
  resolution?: 'embedding' | 'exact' | null;
}
export interface CreatePropertyRequest {
  name: string; description: string; dataType: 'text' | 'number' | 'date'; unit: string | null;
}
// unit은 3-값이다: null="변경 없음", ""="단위 지우기"(서버가 NULL로 정규화), 그 외="설정".
export interface UpdatePropertyRequest {
  name?: string | null; description?: string | null;
  dataType?: 'text' | 'number' | 'date' | null; unit?: string | null;
}
// 끝점은 타입 id로 지목한다 — 이름으로 보내면 리네임과 경합할 때 어느 타입인지 알 수 없다.
export interface CreateRelationRequest {
  subjectTypeId: number; relation: string; objectTypeId: number; description: string;
}
// 끝점은 수정 대상이 아니다. 끝점이 바뀌면 다른 관계이므로 삭제 후 재생성이다.
export interface UpdateRelationRequest { relation?: string | null; description?: string | null }
