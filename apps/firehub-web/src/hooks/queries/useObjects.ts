import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { type ObjectListParams, objectsApi, putToPresignedUrl } from '../../api/objects';
import type { UploadItem } from '../../lib/uploadTree';

/** 목록 조회 조건 — 폴더(path)·검색어(q)·재귀 평면 목록(recursive). 서버 모드 결정은 ObjectListParams 참고(WD-761). */
export type ObjectListFilter = Pick<ObjectListParams, 'path' | 'q' | 'recursive'>;

/**
 * 오브젝트 목록 무한스크롤 조회 — 토큰 기반 페이지네이션.
 * getNextPageParam은 마지막 페이지의 hasMore/nextToken으로 다음 페이지 여부를 판단한다.
 * 조건마다 다른 캐시를 쓰도록 queryKey 끝에 조건을 붙인다 — 앞부분(['datasets', id, 'objects'])은 그대로라
 * 업로드 뒤 무효화가 모든 폴더·검색 캐시에 닿는다. 빈 값은 undefined 로 맞춰 '' 와 생략이 같은 캐시를 쓰고,
 * 서버에도 싣지 않는다(axios 가 undefined 쿼리를 뺀다).
 */
export function useObjectList(datasetId: number, filter: ObjectListFilter = {}, size = 50) {
  const path = filter.path || undefined;
  const q = filter.q || undefined;
  const recursive = filter.recursive || undefined;
  return useInfiniteQuery({
    queryKey: ['datasets', datasetId, 'objects', { path, q, recursive }],
    queryFn: ({ pageParam, signal }) =>
      objectsApi
        .list(datasetId, { path, q, recursive, token: pageParam as string | undefined, size }, signal)
        .then((r) => r.data),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.nextToken ?? undefined) : undefined),
  });
}

/** 업로드 배치 결과 — 총 시도 수/성공 수 + 실패한 항목(재시도 대상) + 올린 폴더. */
export interface UploadResult {
  total: number;
  succeeded: number;
  failedItems: UploadItem[];
  /**
   * 이 배치를 올린 폴더('' = 루트, WD-761). 재시도는 훅의 현재 폴더로 가므로, 화면은 이 값이 지금 폴더와 같을 때만
   * 재시도를 띄워 실패분이 다른 폴더로 올라가지 않게 한다.
   */
  folderPath: string;
}

/**
 * FILE 데이터셋 업로드 뮤테이션.
 * ① upload-urls로 항목 수만큼 presigned PUT 대상 발급(각 항목의 상대경로를 filename으로 전송 → 폴더 구조 보존) →
 * ② 각 파일을 MinIO에 직접 PUT → ③ 성공/실패와 무관하게 목록을 무효화하여 성공분을 즉시 반영한다.
 * PUT은 Promise.allSettled로 독립 처리 — 일부가 실패해도 전체를 에러로 만들지 않고,
 * 실패한 항목만 모아 재시도할 수 있게 한다(전부-아니면-전무 방지). upload-urls 발급 자체가
 * 실패하는 총 실패만 throw되어 mutation error(isError)로 전파된다.
 * folderPath 는 올릴 폴더(지금 보는 폴더, WD-761) — 루트('' 또는 생략)면 본문에 싣지 않아 예전 요청과 같다.
 */
export function useUploadObjects(datasetId: number, folderPath?: string) {
  const qc = useQueryClient();
  return useMutation<UploadResult, Error, UploadItem[]>({
    mutationFn: async (items) => {
      // 상대경로(path)를 filename으로 보내면 앱이 "<prefix><폴더/><상대경로>" 키로 저장한다(폴더 구조 보존, S3 방식).
      const { data } = await objectsApi.requestUploadUrls(datasetId, {
        ...(folderPath ? { path: folderPath } : {}),
        files: items.map((it) => ({ filename: it.path })),
      });
      // 응답 targets는 요청 순서를 유지하므로 인덱스로 짝지어 업로드한다.
      const results = await Promise.allSettled(
        data.targets.map((t, i) => putToPresignedUrl(t.uploadUrl, items[i].file)),
      );
      // 실패한(rejected) 인덱스의 원본 항목만 골라 재시도 대상으로 반환한다.
      const failedItems = results
        .map((r, i) => (r.status === 'rejected' ? items[i] : null))
        .filter((it): it is UploadItem => it !== null);
      return {
        total: data.targets.length,
        succeeded: data.targets.length - failedItems.length,
        failedItems,
        folderPath: folderPath ?? '',
      };
    },
    // onSettled: 성공/실패와 무관하게 목록을 무효화 — 부분 성공분이 즉시 노출되도록 한다.
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['datasets', datasetId, 'objects'] });
    },
  });
}
