import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { reviewItemsApi } from '../../api/reviewItems';
import type { ReviewItemType } from '../../types/reviewItem';

/**
 * TanStack Query 키 — 범용 AI 검수 인박스 도메인.
 * 다른 세션이 먼저 처리해 409(#398)가 나는 경우, 페이지에서 이 키로 직접
 * invalidate하여 stale 행을 제거해야 하므로 외부로 노출한다.
 */
export const REVIEW_ITEMS_QUERY_KEY = 'reviewItems';
const QUERY_KEY = REVIEW_ITEMS_QUERY_KEY;

/** 페이지 크기 — useObjectList(오브젝트 브라우저)와 동일한 무한스크롤 관례(size=50). */
const PAGE_SIZE = 50;

/**
 * pending 검수 항목 무한스크롤 조회(#422) — pending 큐가 수백~수천 건으로 불어나도 한 번에
 * 전량을 불러오지 않는다. 백엔드가 opt-in page/size(생략 시 전체 반환)라 항상 명시적으로 넘긴다.
 * hasMore는 별도 응답 필드가 아니라 "받은 개수가 size와 같다"로 추론한다(#398 stale-page invalidate와
 * 동일하게 응답 스키마를 배열로 유지 — ai-agent MCP 클라이언트가 같은 엔드포인트를 배열로 소비한다).
 */
export function useReviewItemsPending(itemType?: ReviewItemType) {
  return useInfiniteQuery({
    queryKey: [QUERY_KEY, 'pending', itemType ?? 'all'],
    queryFn: ({ pageParam }) => reviewItemsApi.getPending(itemType, pageParam, PAGE_SIZE).then((r) => r.data),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => (lastPage.length === PAGE_SIZE ? allPages.length : undefined),
  });
}

export function useReviewItemEvidence(id: number, enabled: boolean) {
  return useQuery({
    queryKey: [QUERY_KEY, 'evidence', id],
    queryFn: () => reviewItemsApi.getEvidence(id).then((r) => r.data),
    enabled,
  });
}

export function useApproveReviewItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, correctedValue }: { id: number; correctedValue?: string }) =>
      reviewItemsApi.approve(id, correctedValue).then((r) => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [QUERY_KEY] }),
  });
}

export function useRejectReviewItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => reviewItemsApi.reject(id).then((r) => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [QUERY_KEY] }),
  });
}
