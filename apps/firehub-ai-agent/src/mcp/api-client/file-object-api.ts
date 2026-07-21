import type { AxiosInstance } from 'axios';

/** FILE(오브젝트) 데이터셋의 오브젝트 1건. (백엔드 ObjectItemResponse 와 필드 일치) */
export interface ObjectItem {
  /** prefix 를 포함한 전체 오브젝트 키. presigned URL 발급 시 이 값을 그대로 넘긴다. */
  key: string;
  /** prefix 를 제외한 표시명 = 상대 경로(폴더 구조 보존). 다운로드 저장명이기도 하다. */
  name: string;
  /** 바이트 단위 크기 */
  size: number;
  /** 마지막 수정 시각 (ISO 문자열) */
  lastModified: string;
}

/** GET /datasets/{id}/objects 응답. (백엔드 ObjectListResponse 와 1:1) */
export interface ObjectListResponse {
  objects: ObjectItem[];
  /** 다음 페이지 continuation 토큰. 없으면(null) 마지막 페이지. */
  nextToken: string | null;
  /** 다음 페이지 존재 여부 */
  hasMore: boolean;
}

/** GET /datasets/{id}/objects/url 응답. (백엔드 PresignedUrlResponse 와 1:1) */
export interface PresignedUrlResponse {
  /** 단기 presigned GET URL. 공개 MinIO 엔드포인트로 서명되어 브라우저가 직접 내려받는다. */
  url: string;
  /** URL 만료까지 남은 초 */
  expiresInSeconds: number;
}

/**
 * FILE(오브젝트 스토리지) 데이터셋 API.
 *
 * FILE 데이터셋은 물리 테이블이 없고 MinIO 버킷/프리픽스에 원본 파일을 그대로 저장한다.
 * 개별 파일을 DB 행으로 관리하지 않으므로 목록은 항상 S3 ListObjects 로 실시간 계산한다.
 * 바이트는 프록시하지 않고(presigned URL 만 발급) 브라우저가 직접 내려받는 S3 방식이다.
 */
export function createFileObjectApi(client: AxiosInstance) {
  return {
    /**
     * FILE 데이터셋의 오브젝트 한 페이지를 조회한다.
     *
     * @param datasetId FILE 데이터셋 ID
     * @param token     continuation 토큰(이전 응답의 nextToken). 첫 페이지는 생략.
     * @param size      페이지 크기(백엔드에서 1~200 클램프, 생략 시 50)
     */
    async listObjects(
      datasetId: number,
      token?: string,
      size?: number,
    ): Promise<ObjectListResponse> {
      const response = await client.get(`/datasets/${datasetId}/objects`, {
        params: { token: token ?? undefined, size: size ?? undefined },
      });
      return response.data;
    },

    /**
     * 특정 오브젝트의 단기 presigned GET URL 을 발급한다(다운로드/미리보기 겸용).
     *
     * @param datasetId FILE 데이터셋 ID
     * @param key       오브젝트 전체 key(listObjects 가 반환한 key). 반드시 데이터셋 prefix 하위여야 한다.
     */
    async getObjectUrl(datasetId: number, key: string): Promise<PresignedUrlResponse> {
      const response = await client.get(`/datasets/${datasetId}/objects/url`, {
        params: { key },
      });
      return response.data;
    },
  };
}
