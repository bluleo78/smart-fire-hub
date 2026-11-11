import { useQuery } from '@tanstack/react-query';

import { getPlatformDbCapabilities } from '../../api/platformDb';
import type { DocumentSearchMode } from '../../types/document';
import { useAuth } from '../useAuth';

/**
 * 플랫폼 DB 기능 상태(WD-495) 조회. 설치본 단위 값이라 테넌트와 무관해 쿼리 키에 테넌트를 넣지 않고 성공 값은 다시 묻지 않는다.
 * 실패는 두 번 재시도하며, 끝내 실패해도 호출부가 모두 켜짐으로 취급한다. 인증 전에는 조회하지 않는다.
 */
export function usePlatformDbCapabilities() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: ['platform-db', 'capabilities'],
    queryFn: getPlatformDbCapabilities,
    staleTime: Infinity,
    retry: 2,
    enabled: isAuthenticated,
  });
}

/**
 * 화면이 쓰는 플랫폼 DB 기능 판정. 모르면(조회 중·실패) true, 서버가 false 라고 답했을 때만 false —
 * 기본값 규칙을 호출부마다 다시 쓰지 않도록 여기 한 곳에 둔다.
 */
export interface PlatformDbFeatures {
  /** 벡터 검색 사용 가능 여부. */
  vectorSearch: boolean;
  /** 트라이그램(키워드) 검색 사용 가능 여부. */
  trigramSearch: boolean;
}

/**
 * usePlatformDbCapabilities 응답을 화면용 판정으로 바꾼다(WD-495).
 * 조회 중·실패에도 켜짐으로 두어 PG 설치본에서 안내 배너가 잠깐 번쩍이거나 기능이 잘못 숨겨지지 않게 한다.
 */
export function usePlatformDbFeatures(): PlatformDbFeatures {
  const { data } = usePlatformDbCapabilities();
  return {
    vectorSearch: data?.vectorSearch !== false,
    trigramSearch: data?.trigramSearch !== false,
  };
}

/**
 * 켜진 기능으로 쓸 수 있는 검색 모드를 표시 순서(HYBRID, SEMANTIC, KEYWORD)로 돌려준다.
 * HYBRID 는 벡터·트라이그램 둘 다, SEMANTIC 은 벡터, KEYWORD 는 트라이그램이 필요하다.
 * ai-agent 의 resolveSearchMode(platform-db-api.ts)와 같은 규칙이다 — 한쪽을 바꾸면 다른 쪽도 맞출 것.
 */
export function availableSearchModes(features: PlatformDbFeatures): DocumentSearchMode[] {
  const modes: DocumentSearchMode[] = [];
  if (features.vectorSearch && features.trigramSearch) modes.push('HYBRID');
  if (features.vectorSearch) modes.push('SEMANTIC');
  if (features.trigramSearch) modes.push('KEYWORD');
  return modes;
}
