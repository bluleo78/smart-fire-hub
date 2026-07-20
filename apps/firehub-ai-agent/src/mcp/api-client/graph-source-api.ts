import type { AxiosInstance } from 'axios';

/** GraphRAG 추출 원천 청크 (백엔드 ChunkContentResponse 와 1:1) */
export interface ChunkContent {
  chunkId: number;
  content: string;
}

/** GraphRAG 추출 원천(청크 bulk-read) API. 기존 createXxxApi 팩토리 패턴을 따른다. */
export function createGraphSourceApi(client: AxiosInstance) {
  return {
    /** datasetId의 모든 문서 청크를 반환한다. 페이지네이션 없음(스켈레톤 범위). */
    async listDocumentChunks(datasetId: number): Promise<ChunkContent[]> {
      return (await client.get(`/datasets/${datasetId}/document-chunks`)).data;
    },
  };
}
