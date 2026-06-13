import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { settingsApi } from '../../api/settings';
import type { SettingResponse, UpdateSettingsRequest } from '../../types/settings';

// 임베딩 설정 쿼리 키 — 저장 성공 후 invalidate 대상으로 사용한다.
const EMBEDDING_SETTINGS_KEY = ['settings', 'embedding'] as const;

/**
 * 문서 RAG 임베딩 provider 설정을 조회한다.
 * - GET /settings?prefix=embedding 으로 embedding.* 키 목록을 가져온다.
 * - api_key는 백엔드에서 마스킹(****...)되어 내려온다.
 */
export function useEmbeddingSettings() {
  return useQuery<SettingResponse[]>({
    queryKey: EMBEDDING_SETTINGS_KEY,
    queryFn: () => settingsApi.getByPrefix('embedding').then((r) => r.data),
  });
}

/**
 * 임베딩 설정을 저장한다.
 * - PUT /settings 로 변경된 키만 전달한다 (마스킹된 api_key 미변경 시 제외는 호출부에서 처리).
 * - 저장 성공 시 임베딩 쿼리를 무효화해 최신 값을 다시 불러온다.
 */
export function useUpdateEmbeddingSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateSettingsRequest) => settingsApi.update(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: EMBEDDING_SETTINGS_KEY });
    },
  });
}
