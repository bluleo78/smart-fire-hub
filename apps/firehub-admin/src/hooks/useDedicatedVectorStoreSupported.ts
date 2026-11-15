import { useQuery } from '@tanstack/react-query';

import { tenantsApi } from '@/api/tenants';

/** 전용 Qdrant 선택 가능 여부 쿼리 키 — 생성 폼과 상세 카드가 같은 캐시를 쓴다(WD-742). */
export const VECTOR_STORE_OPTIONS_QUERY_KEY = ['platform-vector-store-options'] as const;

/**
 * 전용 Qdrant 선택 가능 여부 조회 훅. 배포 설정(설치가 Qdrant 를 쓰는가)이라 오래 캐시한다.
 * 응답이 `dedicatedSupported === true` 일 때만 참이다 — 로딩·오류·예상 밖 본문(예: SPA index.html 문자열)은 전부
 * 「지원 안 함」으로 본다. 모르는 상태에서 섹션을 보이면 Qdrant 가 아닌 설치에서 쓸 수 없는 선택지를 내밀게 된다.
 */
export function useDedicatedVectorStoreSupported(): boolean {
  const query = useQuery({
    queryKey: VECTOR_STORE_OPTIONS_QUERY_KEY,
    queryFn: () => tenantsApi.vectorStoreOptions().then((r) => r.data),
    staleTime: 5 * 60_000,
  });
  return query.data?.dedicatedSupported === true;
}
