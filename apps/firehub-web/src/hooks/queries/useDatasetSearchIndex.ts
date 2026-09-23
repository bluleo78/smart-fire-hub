import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { datasetsApi } from '../../api/datasets';

/** 검색 색인 상태 쿼리 키 — 저장·재색인 후 무효화에 쓴다. */
const key = (datasetId: number) => ['datasets', datasetId, 'search-index'] as const;

/** 검색 색인 상태. 색인 중(SYNCING)일 때만 5초마다 다시 가져온다. */
export function useSearchIndex(datasetId: number) {
  return useQuery({
    queryKey: key(datasetId),
    queryFn: () => datasetsApi.getSearchIndex(datasetId).then((r) => r.data),
    enabled: !!datasetId,
    refetchInterval: (query) => (query.state.data?.status === 'SYNCING' ? 5000 : false),
  });
}

/** 검색 대상 필드 저장(빈 배열 = 끄기). */
export function useUpdateSearchIndex(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (fields: string[]) => datasetsApi.updateSearchIndex(datasetId, fields).then((r) => r.data),
    onSuccess: (data) => queryClient.setQueryData(key(datasetId), data),
  });
}

/** 수동 전체 재색인. */
export function useReindexSearchIndex(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => datasetsApi.reindexSearchIndex(datasetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key(datasetId) }),
  });
}
