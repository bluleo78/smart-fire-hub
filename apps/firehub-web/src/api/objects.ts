import { client } from './client';

/** 오브젝트 스토리지(FILE 데이터셋) 1건 메타 — key/size/최종수정일 */
export interface ObjectItem {
  key: string;
  size: number;
  lastModified: string | null;
}

/** 오브젝트 목록 페이지 응답 — 토큰 기반 페이지네이션 */
export interface ObjectListResponse {
  objects: ObjectItem[];
  nextToken: string | null;
  hasMore: boolean;
}

/** 오브젝트 단건 presigned GET URL 응답 */
export interface PresignedUrlResponse {
  url: string;
  expiresInSeconds: number;
}

export const objectsApi = {
  // 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션)
  list: (datasetId: number, params: { token?: string; size?: number }) =>
    client.get<ObjectListResponse>(`/datasets/${datasetId}/objects`, { params }),
  // 오브젝트 단건 presigned GET URL
  presignedUrl: (datasetId: number, key: string) =>
    client.get<PresignedUrlResponse>(`/datasets/${datasetId}/objects/url`, { params: { key } }),
};
