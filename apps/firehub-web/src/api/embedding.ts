import type { HostingLocation } from '../lib/hosting-location';
import { client } from './client';

// 임베딩 현황 카운트 — total: 전체 대상 수, embedded: 현재 공간(차원·모델)으로 임베딩된 수
export interface EmbeddingCounts {
  total: number;
  embedded: number;
}

/** 지원 provider(#713 — VOYAGE 제거). */
export type EmbeddingProviderType = 'OLLAMA' | 'OPENAI';

/** GET /settings/embedding — 테넌트 전용 설정. 키는 마스킹만 내려온다(평문·암호문 없음). */
export interface EmbeddingConfigView {
  configured: boolean;
  provider: EmbeddingProviderType | null;
  model: string | null;
  baseUrl: string | null;
  dimension: number | null;
  apiKeyMasked: string;
  /** 공급자 호스팅 위치 선언(S3 §5-5). 미설정이면 'EXTERNAL'. */
  hosting: HostingLocation;
}

/** 저장·연결 테스트 요청. apiKey 를 생략하면 저장된 키를 유지한다(Base URL 이 같을 때만). */
export interface EmbeddingConfigRequest {
  provider: EmbeddingProviderType;
  model: string;
  baseUrl: string;
  apiKey?: string;
  /**
   * 공급자 호스팅 위치 선언. 생략하면 provider·Base URL 이 그대로일 때만 기존 선언 유지(바뀌면 외부) — 화면은 혼란이
   * 없게 항상 현재 선택값을 명시해 보낸다(전송 대상이 바뀌면 화면이 먼저 외부로 되돌리고 알린다).
   */
  hosting?: HostingLocation;
}

/** 연결 테스트 결과 — 실제 임베딩 1건으로 잰 차원. */
export interface EmbeddingProbeResult {
  dimension: number;
}

/** 그 설정으로 저장했을 때 다시 임베딩할 대상 수. */
export interface EmbeddingImpact {
  chunks: number;
  datasets: number;
  rowSearchIndexes: number;
}

/** 테넌트 재임베딩 잡 상태. */
export interface EmbeddingJobState {
  status: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED' | 'SUPERSEDED';
  lastError: string | null;
  updatedAt: string | null;
}

// 재임베딩 현황 — 현재 공간 기준 진행 카운트 + 마지막 잡 상태. 미설정이면 configured=false.
export interface EmbeddingStatus {
  configured: boolean;
  model: string | null;
  dimension: number | null;
  datasets: EmbeddingCounts;
  documentChunks: EmbeddingCounts;
  job: EmbeddingJobState | null;
}

// 전체 재임베딩 트리거 결과 — 요청 시점의 재임베딩 대상 수
export type ReindexAllResult = EmbeddingImpact;

// baseURL이 이미 '/api/v1'을 포함하므로 경로 접두는 '/admin/...', '/settings/...'만 사용한다.
export const embeddingApi = {
  getStatus: () => client.get<EmbeddingStatus>('/admin/embedding/status'),
  reindexAll: () => client.post<ReindexAllResult>('/admin/embedding/reindex-all'),
  getConfig: () => client.get<EmbeddingConfigView>('/settings/embedding'),
  // 외부 호출을 일으키므로 POST — 저장하지 않는다.
  testConfig: (req: EmbeddingConfigRequest) =>
    client.post<EmbeddingProbeResult>('/settings/embedding/test', req),
  getImpact: (params: { model: string; dimension: number }) =>
    client.get<EmbeddingImpact>('/settings/embedding/impact', { params }),
  saveConfig: (req: EmbeddingConfigRequest) =>
    client.put<EmbeddingConfigView>('/settings/embedding', req),
};
