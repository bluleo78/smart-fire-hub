// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface Property { name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// 타입 리네임 의도(5-5) — entities/relations는 이미 새 이름으로 편집돼 있으므로(DB는 그대로 반영),
// 이 배열은 Neo4j 노드 key/type 마이그레이션을 위해 "무엇을 무엇으로 바꿨는지"만 별도로 전달한다.
export interface TypeRename { from: string; to: string; }

// PUT /api/v1/ontology 요청 — OntologySchema에 renames(5-5)를 더한 형태(전체 교체, full-document 편집).
export type UpdateOntologyRequest = OntologySchema & { renames: TypeRename[] };

// schemaVersion: 적재 당시 온톨로지 schema_version(5-4). 스탬프 도입 이전 레거시 노드는 null —
// "값 없음"과 "구버전"을 UI가 혼동하지 않도록 별도 상태로 다룬다(NodeDetailDrawer 참조).
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; schemaVersion: number | null; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }
