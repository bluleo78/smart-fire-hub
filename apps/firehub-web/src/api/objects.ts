import axios from 'axios';

import { client } from './client';

/** 오브젝트 스토리지(FILE 데이터셋) 1건 메타 — key(전체 키)/name(prefix 제외 상대경로, 표시용)/size/최종수정일 */
export interface ObjectItem {
  key: string;
  name: string;
  size: number;
  lastModified: string | null;
}

/** 폴더 모드의 하위 폴더 1건 — path: prefix 제외 상대 경로 + 끝 '/'(예 '제품/Tibero7/'), name: 마지막 세그먼트(WD-761). */
export interface ObjectFolder {
  path: string;
  name: string;
}

/**
 * 오브젝트 목록 페이지 응답 — 토큰 기반 페이지네이션.
 * folders 는 폴더 모드에서만 채워지고, truncated 는 검색이 요청당 훑는 상한에 닿아 일부만 찾았다는 뜻이다(WD-761).
 * 두 필드는 예전 서버·모킹 응답에 없으므로 선택 필드로 두고, 읽는 쪽이 `?? []` / `?? false` 로 기본값을 준다.
 */
export interface ObjectListResponse {
  objects: ObjectItem[];
  folders?: ObjectFolder[];
  nextToken: string | null;
  hasMore: boolean;
  truncated?: boolean;
}

/**
 * 목록 조회 조건(WD-761). 서버 모드 결정: q 가 공백이 아니면 검색(path 무시), 아니고 recursive 면 재귀 평면 목록(예전 동작),
 * 그 밖은 path 폴더의 하위 폴더 + 파일(폴더 모드).
 */
export interface ObjectListParams {
  /** 현재 폴더(앞뒤 / 없이, 루트는 생략) */
  path?: string;
  /** 이름 부분 일치 검색어 */
  q?: string;
  /** true 면 폴더 구분 없이 하위 전체를 평면으로(AI 채팅 위젯 호환) */
  recursive?: boolean;
  token?: string;
  size?: number;
}

/** 오브젝트 단건 presigned GET URL 응답 */
export interface PresignedUrlResponse {
  url: string;
  expiresInSeconds: number;
}

/** presigned GET 의 응답 처리 방식 — inline: 브라우저에서 열기, attachment: 파일로 저장(내보내기 정책 대상) */
export type ObjectDisposition = 'inline' | 'attachment';

/**
 * 업로드 대상 — 앱이 생성한 키 + 클라이언트가 PUT할 presigned URL.
 * 요청 files 와 같은 순서·개수로 온다. 그 파일이 서버의 파일별 검증에 실패했으면 key·uploadUrl 없이 error(사유)만 온다
 * (WD-776 — 규칙은 서버 ObjectKeyGenerator.generateKey). 그 파일만 실패로 보이고 나머지는 올린다.
 */
export interface UploadTarget {
  key?: string;
  uploadUrl?: string;
  /** 이 파일을 발급하지 못한 이유(성공이면 없음) */
  error?: string;
}

/** 업로드 URL 발급 응답 — 대상 목록 + 만료(초) */
export interface UploadUrlResponse {
  targets: UploadTarget[];
  expiresInSeconds: number;
}

export const objectsApi = {
  // 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션). 값이 undefined 인 조건은 axios 가 쿼리에서 뺀다.
  // signal: 검색어가 바뀌어 쓸모없어진 앞 요청(서버가 최대 1만 개를 훑는 검색 포함)을 끊는다.
  list: (datasetId: number, params: ObjectListParams, signal?: AbortSignal) =>
    client.get<ObjectListResponse>(`/datasets/${datasetId}/objects`, { params, signal }),
  // 오브젝트 단건 presigned GET URL — inline(미리보기, 기본)은 열람 권한만, attachment(다운로드)는 서버가 내보내기 정책을
  // 판정한다(S4). disposition 은 서명된 쿼리 파라미터라 클라이언트가 URL 을 고쳐 바꿀 수 없다.
  presignedUrl: (datasetId: number, key: string, disposition: ObjectDisposition = 'inline') =>
    client.get<PresignedUrlResponse>(`/datasets/${datasetId}/objects/url`, { params: { key, disposition } }),
  // presigned PUT URL 배치 발급 (앱이 "<prefix><path>/<filename>" 키 생성 — S3 방식). path 는 올릴 폴더(생략 시 루트, WD-761).
  requestUploadUrls: (datasetId: number, body: { path?: string; files: { filename: string }[] }) =>
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
