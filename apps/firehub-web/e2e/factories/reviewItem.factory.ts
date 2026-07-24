import type { ReviewItemResponse } from '../../src/types/reviewItem';

export function createSynonymReviewItem(overrides: Partial<ReviewItemResponse> = {}): ReviewItemResponse {
  return {
    id: 1, itemType: 'synonym_merge', status: 'pending', datasetId: 122,
    signalType: 'similarity', signalScore: 0.707, reason: '둘 다 분전반 누전을 지칭함',
    payload: { entityType: 'Cause', nameA: '전기적 요인', nameB: '분전반의 누전' },
    decidedBy: null, decidedAt: null, createdAt: '2026-07-23T09:00:00',
    ...overrides,
  };
}

export function createPropertyReviewItem(overrides: Partial<ReviewItemResponse> = {}): ReviewItemResponse {
  return {
    id: 2, itemType: 'property_normalization', status: 'pending', datasetId: 122,
    signalType: 'normalization_failure', signalScore: null,
    reason: "'수천만원대' 값을 number 타입으로 정규화하지 못했습니다.",
    payload: { entityKey: '3:창고 화재', entityType: 'Incident', propertyName: '피해액', dataType: 'number', rawText: '수천만원대', sourceChunkIds: [7] },
    decidedBy: null, decidedAt: null, createdAt: '2026-07-23T09:00:00',
    ...overrides,
  };
}
