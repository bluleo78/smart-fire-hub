import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { embeddingApi, type EmbeddingStatus } from '../../api/embedding';

export const EMBEDDING_STATUS_KEY = ['admin', 'embedding', 'status'] as const;

/** 현황 폴링 간격(ms) — 재임베딩 진행 중일 때만 쓴다. */
const STATUS_POLL_MS = 3000;

/**
 * 현황 쿼리의 다음 폴링 간격을 정한다(false = 멈춤). 순수 함수로 떼어 단위 테스트한다.
 * - 조회 실패(403 등)는 종단 상태 — 멈춘다(WD-600). 예전에는 data 가 없으면 무조건 3초 뒤 다시 불러,
 *   권한 없는 사용자 화면에서 실패 요청이 끝없이 반복됐다. 재시도는 무효화(저장·재임베딩)나 화면 재진입이 맡는다.
 * - 아직 데이터가 없으면(첫 응답 전) 폴링을 이어 간다.
 * - 미설정이거나 데이터셋·문서청크가 모두 임베딩됐고 잡이 돌고 있지 않으면 멈춘다.
 */
export function embeddingStatusRefetchInterval(state: {
  status: 'pending' | 'error' | 'success';
  data: EmbeddingStatus | undefined;
}): number | false {
  if (state.status === 'error') return false;
  const data = state.data;
  if (!data) return STATUS_POLL_MS;
  if (!data.configured) return false; // 미설정이면 진행할 잡이 없다
  const done =
    data.datasets.embedded >= data.datasets.total &&
    data.documentChunks.embedded >= data.documentChunks.total &&
    data.job?.status !== 'RUNNING';
  return done ? false : STATUS_POLL_MS;
}

/**
 * 임베딩 현황 조회
 * - 재임베딩이 진행 중일 때는 3초 간격으로 폴링하고,
 *   데이터셋·문서청크가 모두 임베딩 완료되면 폴링을 멈춘다.
 * - 조회가 실패하면(403 등) 폴링을 멈춘다(embeddingStatusRefetchInterval, WD-600).
 */
export function useEmbeddingStatus() {
  return useQuery({
    queryKey: EMBEDDING_STATUS_KEY,
    queryFn: () => embeddingApi.getStatus().then((r) => r.data),
    // v5 refetchInterval 콜백은 query 객체를 받는다 — query.state 로 최신 상태·데이터 접근.
    refetchInterval: (query) =>
      embeddingStatusRefetchInterval({ status: query.state.status, data: query.state.data }),
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
