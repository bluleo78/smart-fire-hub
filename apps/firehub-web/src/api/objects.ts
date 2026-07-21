import axios from 'axios';

import { client } from './client';

/** 오브젝트 스토리지(FILE 데이터셋) 1건 메타 — key(전체 키)/name(prefix 제외 상대경로, 표시용)/size/최종수정일 */
export interface ObjectItem {
  key: string;
  name: string;
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

/** 업로드 대상 — 앱이 생성한 키 + 클라이언트가 PUT할 presigned URL */
export interface UploadTarget {
  key: string;
  uploadUrl: string;
}

/** 업로드 URL 발급 응답 — 대상 목록 + 만료(초) */
export interface UploadUrlResponse {
  targets: UploadTarget[];
  expiresInSeconds: number;
}

export const objectsApi = {
  // 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션)
  list: (datasetId: number, params: { token?: string; size?: number }) =>
    client.get<ObjectListResponse>(`/datasets/${datasetId}/objects`, { params }),
  // 오브젝트 단건 presigned GET URL
  presignedUrl: (datasetId: number, key: string) =>
    client.get<PresignedUrlResponse>(`/datasets/${datasetId}/objects/url`, { params: { key } }),
  // presigned PUT URL 배치 발급 (앱이 "<prefix><filename>" 키 생성 — S3 방식)
  requestUploadUrls: (datasetId: number, body: { files: { filename: string }[] }) =>
    client.post<UploadUrlResponse>(`/datasets/${datasetId}/objects/upload-urls`, body),
};

/** presigned PUT URL로 파일 바이트를 MinIO에 직접 업로드한다(앱 baseURL/인터셉터 미경유). */
export async function putToPresignedUrl(uploadUrl: string, file: File): Promise<void> {
  await axios.put(uploadUrl, file, {
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
  });
}

/** 오브젝트 키에서 표시 파일명(마지막 경로 세그먼트) 추출 — S3 방식. */
export function objectName(key: string): string {
  return key.split('/').pop() || key;
}
