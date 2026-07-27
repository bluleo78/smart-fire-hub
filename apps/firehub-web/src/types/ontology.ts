// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface Property { name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { id?: number; type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// 타입 리네임 힌트(5-5, 5-6에서 목적 변경) — entities/relations는 이미 새 이름으로 편집돼 있으므로,
// 이 배열은 서버(OntologyRepository)가 "이름이 바뀐 기존 행"을 매칭해 entity_type_id를 보존할 수 있도록
// "무엇을 무엇으로 바꿨는지"만 별도로 전달한다. Neo4j는 entity_type_id 기반 key라 리네임의 영향을 받지 않는다.
export interface TypeRename { from: string; to: string; }

// PUT /api/v1/ontology 요청 — OntologySchema에 renames(5-5)를 더한 형태(전체 교체, full-document 편집).
export type UpdateOntologyRequest = OntologySchema & { renames: TypeRename[] };

// schemaVersion: 적재 당시 온톨로지 schema_version(5-4). 스탬프 도입 이전 레거시 노드는 null —
// "값 없음"과 "구버전"을 UI가 혼동하지 않도록 별도 상태로 다룬다(NodeDetailDrawer 참조).
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; schemaVersion: number | null; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }

// 다중 온톨로지 목록(GET /api/v1/ontologies) — 데이터셋 바인딩 시 선택지로 쓴다.
export interface OntologySummary { id: number; domain: string; schemaVersion: number; }
