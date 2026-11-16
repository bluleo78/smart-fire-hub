import { useQuery } from '@tanstack/react-query';

import { usersApi } from '@/api/users';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { MIN_QUERY_LENGTH } from '@/lib/search-limits';

/**
 * 플랫폼 사용자 검색 쿼리 키의 머리. 검색 화면(Owner 선택기·멤버 추가 「기존 계정」)과 캐시를 무효화하는 곳(계정 생성)이 같은 문자열을
 * 써야 하므로 한 곳에 둔다 — 한쪽만 고치면 방금 만든 계정이 검색에 안 보이는 식으로 조용히 어긋난다.
 */
export const PLATFORM_USER_SEARCH_KEY = 'platform-user-search';

/**
 * 플랫폼 사용자 디바운스 검색(300ms, 2자 이상). `UserSearchPicker` 를 쓰는 화면이 이 훅의 결과를 그대로 내려준다.
 *
 * 같은 쿼리 키를 쓰므로 Owner 선택기와 멤버 추가 대화상자가 캐시를 함께 쓴다(같은 검색어면 같은 결과).
 *
 * @param keyword 입력 중인 검색어(디바운스 전)
 * @param active 검색할 상황인지 — 이미 사용자를 골랐으면 false 로 넘겨 요청을 멈춘다
 */
export function usePlatformUserSearch(keyword: string, active: boolean) {
  const debounced = useDebounceValue(keyword, 300);
  // 실제로 검색한 값(디바운스·trim) — 입력 중인 글자가 아니라 지금 보이는 결과를 만든 검색어다.
  const searched = debounced.trim();
  const enabled = active && searched.length >= MIN_QUERY_LENGTH;
  const { data: results, isFetching, isError } = useQuery({
    queryKey: [PLATFORM_USER_SEARCH_KEY, debounced],
    queryFn: () => usersApi.search(searched).then((r) => r.data),
    enabled,
  });
  // 조회가 끝나 결과(빈 목록 포함)를 보일 수 있는 상태. 실패는 따로 구별한다(isError).
  const settled = enabled && !isFetching;
  return {
    searched,
    enabled,
    results,
    isFetching,
    isError,
    /** 조회 실패 — 「결과 없음」과 구별해 보여야 한다. */
    showError: settled && isError,
    /** 결과 목록(빈 목록 포함)을 그릴 수 있는지. */
    showResults: settled && !isError && results !== undefined,
    /** 검색은 성공했지만 결과가 0건인지 — 호출자가 「새 계정 만들기」 같은 다음 행동을 붙인다. */
    noResults: settled && !isError && results !== undefined && results.length === 0,
  };
}

/** `usePlatformUserSearch` 결과 모양 — `UserSearchPicker` 의 prop 타입으로 쓴다. */
export type PlatformUserSearch = ReturnType<typeof usePlatformUserSearch>;
