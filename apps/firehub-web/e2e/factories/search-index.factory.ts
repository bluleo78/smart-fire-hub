import type { SearchIndexStatus } from '../../src/types/dataset';

/** 검색 탭 목 응답. 기본값은 꺼짐(OFF). */
export function createSearchIndexStatus(overrides: Partial<SearchIndexStatus> = {}): SearchIndexStatus {
  return {
    enabled: false,
    fields: [],
    status: 'OFF',
    indexedRows: 0,
    totalRows: 0,
    lastSyncedAt: null,
    lastError: null,
    embeddingModel: null,
    ...overrides,
  };
}
