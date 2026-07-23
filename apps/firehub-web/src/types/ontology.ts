// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface Property { name: string; description: string; dataType: 'text' | 'number' | 'date' | null; unit: string | null; }
export interface EntityTypeDef { type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; properties: Property[]; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; schemaVersion: number; entities: EntityTypeDef[]; relations: Triple[]; }

// PUT /api/v1/ontology 요청 — OntologySchema와 동일 형태(전체 교체, full-document 편집).
export type UpdateOntologyRequest = OntologySchema;

export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }
