import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { embeddingApi, type EmbeddingStatus } from '../../api/embedding';

export const EMBEDDING_STATUS_KEY = ['admin', 'embedding', 'status'] as const;

/**
 * 임베딩 현황 조회
 * - 재임베딩이 진행 중일 때는 3초 간격으로 폴링하고,
 *   데이터셋·문서청크가 모두 임베딩 완료되면 폴링을 멈춘다.
 */
export function useEmbeddingStatus() {
  return useQuery({
    queryKey: EMBEDDING_STATUS_KEY,
    queryFn: () => embeddingApi.getStatus().then((r) => r.data),
    // v5 refetchInterval 콜백은 query 객체를 받는다 — query.state.data로 최신 데이터 접근.
    refetchInterval: (query) => {
      const data = query.state.data as EmbeddingStatus | undefined;
      if (!data) return 3000;
      if (!data.configured) return false; // 미설정이면 진행할 잡이 없다
      const done =
        data.datasets.embedded >= data.datasets.total &&
        data.documentChunks.embedded >= data.documentChunks.total &&
        data.job?.status !== 'RUNNING';
      return done ? false : 3000;
    },
  });
}

/**
 * 전체 재임베딩 실행
 * - 성공 시 토스트 안내 후 현황 쿼리를 무효화해 진행 폴링을 재개시킨다.
 */
export function useReindexAllEmbeddings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => embeddingApi.reindexAll().then((r) => r.data),
    onSuccess: (result) => {
      toast.success(
        `재임베딩을 시작했습니다 (문서 청크 ${result.chunks}, 데이터셋 ${result.datasets}).`,
      );
      queryClient.invalidateQueries({ queryKey: EMBEDDING_STATUS_KEY });
    },
    onError: () => toast.error('재임베딩 시작에 실패했습니다.'),
  });
}
