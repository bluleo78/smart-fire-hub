/** 범용 AI 검수 인박스 — graph_review_item API 타입. */
export type ReviewItemStatus = 'pending' | 'approved' | 'rejected';
export type ReviewItemType = 'synonym_merge' | 'property_normalization' | 'entity_extraction';

/** 동의어 병합 항목 payload. */
export interface SynonymPayload {
  entityType: string;
  nameA: string;
  nameB: string;
}

/** 속성 정규화 항목 payload. */
export interface PropertyPayload {
  entityKey: string;
  entityType: string;
  propertyName: string;
  dataType: 'text' | 'number' | 'date';
  rawText: string;
  sourceChunkIds?: number[];
}

/** 저신뢰 엔티티가 끌린 보류 관계(승인 시 함께 적재). */
export interface EntityRelationRef {
  relType: string;
  direction: 'out' | 'in';
  otherKey: string;
}

/** 저신뢰 엔티티 검수 항목 payload. */
export interface EntityPayload {
  entityType: string;
  name: string;
  properties?: Record<string, number | string>;
  sourceChunkIds?: number[];
  relations?: EntityRelationRef[];
}

export interface ReviewItemResponse {
  id: number;
  itemType: ReviewItemType;
  status: ReviewItemStatus;
  datasetId: number | null;
  signalType: string | null;
  signalScore: number | null;
  reason: string | null;
  payload: SynonymPayload | PropertyPayload | EntityPayload | Record<string, unknown>;
  decidedBy: number | null;
  decidedAt: string | null;
  createdAt: string | null;
}

export interface EvidenceChunk {
  chunkId: number;
  content: string;
}
