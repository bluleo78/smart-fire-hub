import { useQuery } from '@tanstack/react-query';

import { getDataStoreCapabilities } from '../../api/dataStore';
import { useAuth } from '../useAuth';

/**
 * 저장소 기능 지원 여부. 테넌트의 저장소는 세션 중 바뀌지 않으므로 staleTime 을 무한으로 둔다.
 * 로딩 중·조회 실패 시 data 는 undefined 이며, 호출부는 PG 기본값(모두 지원)으로 취급해 화면을 그대로 둔다.
 * 실제 미지원 기능은 api 가 422 로 막으므로 안전하다.
 *
 * 쿼리 키에 활성 테넌트를 넣는다(WD-80) — 테넌트 전환(selectTenant)은 하드 리로드로 캐시를 비우지만, 로그아웃 후 다른
 * 테넌트로 다시 로그인하는 경로는 페이지를 다시 읽지 않아 캐시가 남는다. 키가 테넌트와 무관하면 무한 staleTime 탓에
 * PG 테넌트의 판정이 Tibero 테넌트에(또는 그 반대로) 그대로 쓰인다. useMyPermissions 와 같은 방식이다.
 * 활성 테넌트가 없으면(로그인 전) 누구의 저장소인지 정할 수 없어 조회하지 않는다.
 */
export function useDataStoreCapabilities() {
  const { activeTenantId } = useAuth();
  return useQuery({
    queryKey: ['data-store', 'capabilities', activeTenantId],
    queryFn: getDataStoreCapabilities,
    staleTime: Infinity,
    retry: false,
    enabled: activeTenantId !== null,
  });
}

/** 화면이 쓰는 저장소 기능 판정(WD-80). 기본값 규칙을 호출부마다 다시 쓰지 않도록 여기 한 곳에 둔다. */
export interface DataStoreFeatures {
  /** GEOMETRY 타입 지원 여부. 모르거나(조회 중·실패·로그인 전) 지원하면 true, 저장소가 미지원이라고 답했을 때만 false. */
  geometrySupported: boolean;
  /**
   * 행 검색 지원 여부의 세 상태. 조회 중이면 undefined(아직 모름), 조회 실패·로그인 전이면 PG 기본값 true(지금 화면 그대로 —
   * 미지원 기능은 api 가 422 로 막는다), 조회 성공이면 응답 값.
   */
  rowSearch: boolean | undefined;
}

/**
 * useDataStoreCapabilities 응답을 화면용 판정으로 바꾼다(WD-80).
 * rowSearch 의 undefined 는 isLoading(첫 조회 진행 중) 기준이다 — 비활성 쿼리(로그인 전)는 isPending 이어도 isLoading 이
 * 아니므로 PG 기본값 true 가 된다.
 */
export function useDataStoreFeatures(): DataStoreFeatures {
  const { data, isLoading } = useDataStoreCapabilities();
  return {
    geometrySupported: data?.geometry !== false,
    rowSearch: isLoading ? undefined : data?.rowSearch !== false,
  };
}
