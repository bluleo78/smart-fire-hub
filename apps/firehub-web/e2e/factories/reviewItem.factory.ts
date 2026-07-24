import type { EvidenceChunk, ReviewItemResponse } from '../../src/types/reviewItem';

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

// 저신뢰 엔티티 검수 항목(승인 시 그래프 적재) — signal은 low_confidence, score=confidence.
export function createEntityReviewItem(overrides: Partial<ReviewItemResponse> = {}): ReviewItemResponse {
  return {
    id: 3, itemType: 'entity_extraction', status: 'pending', datasetId: 122,
    signalType: 'low_confidence', signalScore: 0.42,
    reason: '문서에 명시적 언급이 약해 추론에 의존함',
    payload: {
      entityType: 'Cause', name: '노후 배선 추정', sourceChunkIds: [9],
      relations: [{ relType: 'CAUSED_BY', direction: 'in', otherKey: '3:창고 화재' }],
    },
    decidedBy: null, decidedAt: null, createdAt: '2026-07-23T09:00:00',
    ...overrides,
  };
}

// GET /evidence 모킹용 원문 청크 스니펫(동의어 항목의 근거 표시 검증용).
export function createEvidenceChunk(overrides: Partial<EvidenceChunk> = {}): EvidenceChunk {
  return { chunkId: 501, content: '스프링클러 설비 오작동으로 인한 방수 지연이 확인되었다.', ...overrides };
}
