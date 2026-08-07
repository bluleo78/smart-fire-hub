// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface Property { name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { id?: number; type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// 타입 리네임 힌트(5-5, 5-6에서 목적 변경) — entities/relations는 이미 새 이름으로 편집돼 있으므로,
// 이 배열은 서버(OntologyRepository)가 "이름이 바뀐 기존 행"을 매칭해 entity_type_id를 보존할 수 있도록
// "무엇을 무엇으로 바꿨는지"만 별도로 전달한다. Neo4j는 entity_type_id 기반 key라 리네임의 영향을 받지 않는다.
export interface TypeRename { from: string; to: string; }

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

// PUT /api/v1/ontology(/{id}) 요청 — OntologySchema에 renames(5-5)를 더한 형태(전체 교체, full-document 편집).
// 상태 전이는 이 요청에 포함하지 않는다 — PATCH /ontology/{id}/status 전용(ontologyApi.updateOntologyStatus).
export type UpdateOntologyRequest = OntologySchema & { renames: TypeRename[] };

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
