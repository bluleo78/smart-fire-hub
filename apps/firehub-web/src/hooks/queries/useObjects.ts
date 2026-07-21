import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { objectsApi, putToPresignedUrl } from '../../api/objects';

/**
 * 오브젝트 목록 무한스크롤 조회 — 토큰 기반 페이지네이션.
 * getNextPageParam은 마지막 페이지의 hasMore/nextToken으로 다음 페이지 여부를 판단한다.
 */
export function useObjectList(datasetId: number, size = 50) {
  return useInfiniteQuery({
    queryKey: ['datasets', datasetId, 'objects'],
    queryFn: ({ pageParam }) =>
      objectsApi.list(datasetId, { token: pageParam as string | undefined, size }).then((r) => r.data),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.nextToken ?? undefined) : undefined),
  });
}

/** 업로드 배치 결과 — 총 시도 수/성공 수 + 실패한 파일(재시도 대상). */
export interface UploadResult {
  total: number;
  succeeded: number;
  failedFiles: File[];
}

/**
 * FILE 데이터셋 업로드 뮤테이션.
 * ① upload-urls로 파일 수만큼 presigned PUT 대상 발급 → ② 각 파일을 MinIO에 직접 PUT →
 * ③ 성공/실패와 무관하게 목록을 무효화하여 성공분을 즉시 반영한다.
 * PUT은 Promise.allSettled로 독립 처리 — 일부가 실패해도 전체를 에러로 만들지 않고,
 * 실패한 파일만 모아 재시도할 수 있게 한다(전부-아니면-전무 방지). upload-urls 발급 자체가
 * 실패하는 총 실패만 throw되어 mutation error(isError)로 전파된다.
 */
export function useUploadObjects(datasetId: number) {
  const qc = useQueryClient();
  return useMutation<UploadResult, Error, File[]>({
    mutationFn: async (files) => {
      // 원본 파일명을 그대로 보내면 앱이 "<prefix><filename>" 키로 저장한다(S3 방식).
      const { data } = await objectsApi.requestUploadUrls(datasetId, {
        files: files.map((f) => ({ filename: f.name })),
      });
      // 응답 targets는 요청 files 순서를 유지하므로 인덱스로 짝지어 업로드한다.
      const results = await Promise.allSettled(
        data.targets.map((t, i) => putToPresignedUrl(t.uploadUrl, files[i])),
      );
      // 실패한(rejected) 인덱스의 원본 파일만 골라 재시도 대상으로 반환한다.
      const failedFiles = results
        .map((r, i) => (r.status === 'rejected' ? files[i] : null))
        .filter((f): f is File => f !== null);
      return {
        total: data.targets.length,
        succeeded: data.targets.length - failedFiles.length,
        failedFiles,
      };
    },
    // onSettled: 성공/실패와 무관하게 목록을 무효화 — 부분 성공분이 즉시 노출되도록 한다.
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['datasets', datasetId, 'objects'] });
    },
  });
}
