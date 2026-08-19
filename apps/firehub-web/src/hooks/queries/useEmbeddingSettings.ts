import { useQuery } from '@tanstack/react-query';

import { settingsApi } from '../../api/settings';
import type { ResolvedSettingResponse } from '../../types/settings';

// 임베딩 설정 쿼리 키.
const EMBEDDING_SETTINGS_KEY = ['settings', 'embedding'] as const;

/**
 * 문서 RAG 임베딩 provider 설정을 조회한다.
 * - GET /settings?prefix=embedding 으로 embedding.* 키 목록을 가져온다.
 * - api_key는 백엔드에서 마스킹(****...)되어 내려온다.
 * - 응답에는 overridden/tenantEditable 플래그가 함께 온다(P7-b). embedding.* 4키는 전부
 *   플랫폼 잠금이므로 tenantEditable=false 로 내려오고, 화면은 그 플래그로 읽기 전용을 표시한다.
 *
 * 저장 훅은 없다 — P7-b 에서 임베딩 4키가 플랫폼 소유로 확정되어 테넌트 평면의 저장 경로가
 * 서버에서 거부되기 때문에 화면과 함께 제거했다.
 */
export function useEmbeddingSettings() {
  return useQuery<ResolvedSettingResponse[]>({
    queryKey: EMBEDDING_SETTINGS_KEY,
    queryFn: () => settingsApi.getByPrefix('embedding').then((r) => r.data),
  });
}
