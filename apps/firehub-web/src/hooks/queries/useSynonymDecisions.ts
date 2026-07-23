import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { synonymDecisionsApi } from '../../api/synonymDecisions';

/** TanStack Query 키 — 근접쌍 HITL 검수 도메인 */
const QUERY_KEY = 'synonymDecisions';

export function useSynonymDecisionsPending() {
  return useQuery({
    queryKey: [QUERY_KEY, 'pending'],
    queryFn: () => synonymDecisionsApi.getPending().then((r) => r.data),
  });
}

export function useApproveSynonymDecision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => synonymDecisionsApi.approve(id).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}

export function useRejectSynonymDecision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => synonymDecisionsApi.reject(id).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}
