import { type QueryKey, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import { isNotFoundError } from '../../lib/api-error';

/**
 * 상세 화면 첫 조회가 404 면 그 리소스의 목록 쿼리만 무효화한다 (WD-646).
 *
 * 공유받은 링크 등으로 지워진 항목의 상세에 들어온 뒤 「목록으로」를 누르면, 목록 캐시(staleTime 30초)가 지워진 행을 그대로 보였다.
 *
 * 목록 키 접두 규칙: 리소스마다 목록 쿼리(페이지 목록·전체 목록·무한 스크롤)는 `[리소스, 'list', …]` 아래에 둔다
 * (DATASET_LIST_KEY·DASHBOARD_LIST_KEY·CHART_LIST_KEY·SAVED_QUERY_LIST_KEY·PIPELINE_LIST_KEY·ROLE_LIST_KEY).
 * - 목록만 골라 무효화할 수 있다 — 상세(`[리소스, id]`)와 그 아래 키를 건드리지 않아 404 상세를 다시 부르지 않는다.
 * - 키 모양(두 번째 칸이 객체인지 등)을 추측하는 predicate 가 필요 없다.
 * - `[리소스]` 로 시작하므로 생성·삭제 때의 기존 접두 무효화에도 그대로 걸린다.
 *
 * @param query 상세 useQuery 결과 — 받아 둔 데이터 없이 실패했는지(isLoadingError)와 오류만 본다(재조회 중 404 는 제외)
 * @param listKey 위 규칙의 목록 키 접두(모듈 상수)
 */
export function useInvalidateListsOnEntryNotFound(
  query: { isLoadingError: boolean; error: unknown },
  listKey: QueryKey,
) {
  const queryClient = useQueryClient();
  const entryNotFound = query.isLoadingError && isNotFoundError(query.error);
  useEffect(() => {
    if (entryNotFound) void queryClient.invalidateQueries({ queryKey: listKey });
  }, [entryNotFound, listKey, queryClient]);
}
