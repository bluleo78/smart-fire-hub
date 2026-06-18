import type { AxiosInstance } from 'axios';

/** POST /datasets/search 응답 후보 1건. (백엔드 DatasetSearchHit record와 필드 일치) */
export interface DatasetSearchHit {
  datasetId: number;
  name: string;
  description: string | null;
  storageType: 'TABLE' | 'DOCUMENT';
  originType: 'SOURCE' | 'DERIVED' | 'TEMP';
  tableName: string | null;
  category: string | null;
  score: number;
}

/** 데이터셋 카탈로그 통합 Discovery 검색 API. */
export function createDatasetSearchApi(client: AxiosInstance) {
  return {
    /**
     * 데이터셋 카탈로그를 시맨틱+키워드 하이브리드로 검색한다.
     *
     * @param query       검색 질의 문자열
     * @param mode        검색 모드 (undefined → 백엔드 기본값, null 전달)
     * @param topK        반환할 최대 후보 수 (undefined → 백엔드 기본값, null 전달)
     * @param storageType 저장 유형 필터 (undefined → 전체, 백엔드에 null 전달)
     */
    async searchDatasets(
      query: string,
      mode?: 'SEMANTIC' | 'KEYWORD' | 'HYBRID',
      topK?: number,
      storageType?: 'TABLE' | 'DOCUMENT',
    ): Promise<DatasetSearchHit[]> {
      const response = await client.post('/datasets/search', {
        query,
        mode: mode ?? null,
        topK: topK ?? null,
        storageType: storageType ?? null,
      });
      return response.data;
    },
  };
}
