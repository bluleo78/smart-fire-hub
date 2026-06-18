import { client } from './client';

// 임베딩 현황 카운트 — total: 전체 대상 수, embedded: 임베딩 완료 수
export interface EmbeddingCounts {
  total: number;
  embedded: number;
}

// 재임베딩 현황 — 현재 모델 및 데이터셋/문서청크별 진행 카운트
export interface EmbeddingStatus {
  model: string;
  datasets: EmbeddingCounts;
  documentChunks: EmbeddingCounts;
}

// 전체 재임베딩 트리거 결과 — 재임베딩 대상으로 잡힌 데이터셋/문서셋 수
export interface ReindexAllResult {
  datasets: number;
  documentDatasets: number;
}

// baseURL이 이미 '/api/v1'을 포함하므로 경로 접두는 '/admin/embedding/...'만 사용한다.
export const embeddingApi = {
  getStatus: () => client.get<EmbeddingStatus>('/admin/embedding/status'),
  reindexAll: () => client.post<ReindexAllResult>('/admin/embedding/reindex-all'),
};
