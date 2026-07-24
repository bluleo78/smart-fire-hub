/** 범용 AI 검수 인박스 — graph_review_item API 타입. */
export type ReviewItemStatus = 'pending' | 'approved' | 'rejected';
export type ReviewItemType = 'synonym_merge' | 'property_normalization';

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

export interface ReviewItemResponse {
  id: number;
  itemType: ReviewItemType;
  status: ReviewItemStatus;
  datasetId: number | null;
  signalType: string | null;
  signalScore: number | null;
  reason: string | null;
  payload: SynonymPayload | PropertyPayload | Record<string, unknown>;
  decidedBy: number | null;
  decidedAt: string | null;
  createdAt: string | null;
}

export interface EvidenceChunk {
  chunkId: number;
  content: string;
}
