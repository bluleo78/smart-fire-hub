import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { reviewItemsApi } from '../../api/reviewItems';
import type { ReviewItemType } from '../../types/reviewItem';

/**
 * TanStack Query 키 — 범용 AI 검수 인박스 도메인.
 * 다른 세션이 먼저 처리해 409(#398)가 나는 경우, 페이지에서 이 키로 직접
 * invalidate하여 stale 행을 제거해야 하므로 외부로 노출한다.
 */
export const REVIEW_ITEMS_QUERY_KEY = 'reviewItems';
const QUERY_KEY = REVIEW_ITEMS_QUERY_KEY;

export function useReviewItemsPending(itemType?: ReviewItemType) {
  return useQuery({
    queryKey: [QUERY_KEY, 'pending', itemType ?? 'all'],
    queryFn: () => reviewItemsApi.getPending(itemType).then((r) => r.data),
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
