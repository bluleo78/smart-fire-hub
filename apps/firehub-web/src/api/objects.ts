import axios from 'axios';

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
  // presigned PUT URL 배치 발급 (앱이 키 생성)
  requestUploadUrls: (datasetId: number, body: { robotId?: string; files: { ext: string }[] }) =>
    client.post<UploadUrlResponse>(`/datasets/${datasetId}/objects/upload-urls`, body),
};

/** presigned PUT URL로 파일 바이트를 MinIO에 직접 업로드한다(앱 baseURL/인터셉터 미경유). */
export async function putToPresignedUrl(uploadUrl: string, file: File): Promise<void> {
  await axios.put(uploadUrl, file, {
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
  });
}

/** 파일명에서 확장자 추출(소문자, 점 제외). 확장자가 없으면 'bin'. */
export function extOf(file: File): string {
  const i = file.name.lastIndexOf('.');
  return i >= 0 ? file.name.slice(i + 1).toLowerCase() : 'bin';
}
