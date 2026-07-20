import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { objectsApi } from '../../api/objects';

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

/**
 * 단건 presigned URL 조회(썸네일용).
 * staleTime을 presign 만료(5분)보다 짧게(4분) 두어 만료된 URL을 캐시에서 재사용하지 않는다.
 * enabled: 이미지가 아닌 오브젝트(썸네일이 필요 없는 행)에서 불필요한 presign 요청을
 * 보내지 않도록 호출부(ObjectThumbnail)에서 이미지 여부를 넘겨 제어한다.
 */
export function usePresignedUrl(datasetId: number, key: string, enabled = true) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'objects', 'url', key],
    queryFn: () => objectsApi.presignedUrl(datasetId, key).then((r) => r.data.url),
    staleTime: 4 * 60 * 1000,
    enabled,
  });
}
