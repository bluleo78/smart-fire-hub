import type { AxiosInstance } from 'axios';

/** 검색 결과 청크 (백엔드 DocumentSearchHit 와 1:1) */
export interface DocumentSearchHit {
  chunkId: number;
  documentFileId: number;
  datasetId: number;
  fileName: string;
  chunkIndex: number;
  content: string;
  score: number;
}

/** 문서 RAG 검색 API. */
export function createDocumentApi(client: AxiosInstance) {
  return {
    /**
     * 문서 청크를 의미 기반으로 검색한다.
     *
     * @param query      검색 질의 문자열
     * @param datasetIds 검색 대상 dataset id 목록 (undefined → 전체, 백엔드에 null 전달)
     * @param topK       반환할 최대 청크 수 (undefined → 백엔드 기본값, null 전달)
     * @param mode       검색 모드 (undefined → 백엔드 기본값 HYBRID, null 전달)
     */
    async searchDocuments(
      query: string,
      datasetIds: number[] | undefined,
      topK: number | undefined,
      mode?: 'SEMANTIC' | 'KEYWORD' | 'HYBRID',
    ): Promise<DocumentSearchHit[]> {
      const response = await client.post('/documents/search', {
        query,
        datasetIds: datasetIds ?? null,
        topK: topK ?? null,
        mode: mode ?? null, // 서버 기본 HYBRID
      });
      return response.data;
    },
  };
}
