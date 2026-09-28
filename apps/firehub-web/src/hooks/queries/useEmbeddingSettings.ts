import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { embeddingApi, type EmbeddingConfigRequest } from '../../api/embedding';
import { EMBEDDING_STATUS_KEY } from './useEmbedding';

/** 임베딩 설정 쿼리 키. */
export const EMBEDDING_CONFIG_KEY = ['settings', 'embedding'] as const;

/**
 * 테넌트 임베딩 설정 조회(#713). 설정은 테넌트 전용이라 미설정이면 `configured=false` 가 온다.
 * 키는 마스킹(`apiKeyMasked`)만 내려오고 폼 값으로는 쓰지 않는다.
 */
export function useEmbeddingConfig() {
  return useQuery({
    queryKey: EMBEDDING_CONFIG_KEY,
    queryFn: () => embeddingApi.getConfig().then((r) => r.data),
  });
}

/**
 * 설정 저장. 서버가 다시 probe 해 차원을 확정하고, 재임베딩이 필요하면 스스로 잡을 투입한다 — 그래서 성공 시
 * 설정과 함께 현황도 무효화해 진행률 폴링을 다시 켠다.
 */
export function useSaveEmbeddingConfig() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: EmbeddingConfigRequest) => embeddingApi.saveConfig(req).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: EMBEDDING_CONFIG_KEY });
      queryClient.invalidateQueries({ queryKey: EMBEDDING_STATUS_KEY });
    },
  });
}
