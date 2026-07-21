// 온톨로지 시각화 계약 타입 — firehub-api /api/v1/ontology(/graph) 응답과 1:1.
export interface EntityTypeDef { type: string; description: string; naming: string; resolution: 'embedding' | 'exact'; }
export interface Triple { subject: string; relation: string; object: string; description: string; }
export interface OntologySchema { domain: string; entities: EntityTypeDef[]; relations: Triple[]; }

export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface GraphData { nodes: GraphNode[]; edges: GraphEdge[]; }
