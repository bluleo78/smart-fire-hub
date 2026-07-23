// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface Property { name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// PUT /api/v1/ontology 요청 — OntologySchema와 동일 형태(전체 교체, full-document 편집).
export type UpdateOntologyRequest = OntologySchema;

// schemaVersion: 적재 당시 온톨로지 schema_version(5-4). 스탬프 도입 이전 레거시 노드는 null —
// "값 없음"과 "구버전"을 UI가 혼동하지 않도록 별도 상태로 다룬다(NodeDetailDrawer 참조).
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; schemaVersion: number | null; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }
