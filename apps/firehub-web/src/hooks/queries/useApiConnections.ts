import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiConnectionsApi } from '../../api/api-connections';
import type { CreateApiConnectionRequest, UpdateApiConnectionRequest } from '../../types/api-connection';

/** TanStack Query 키 — API 연결 도메인 */
const QUERY_KEY = 'apiConnections';

export function useApiConnections() {
  return useQuery({
    queryKey: [QUERY_KEY],
    queryFn: () => apiConnectionsApi.getAll().then(r => r.data),
  });
}

export function useApiConnection(id: number) {
  return useQuery({
    queryKey: [QUERY_KEY, id],
    queryFn: () => apiConnectionsApi.getById(id).then(r => r.data),
    enabled: !!id,
  });
}

export function useCreateApiConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateApiConnectionRequest) =>
      apiConnectionsApi.create(data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}

export function useUpdateApiConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateApiConnectionRequest }) =>
      apiConnectionsApi.update(id, data).then(r => r.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY, variables.id] });
    },
  });
}

export function useDeleteApiConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiConnectionsApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}

/**
 * 단일 API 연결 즉시 테스트 (관리자 전용)
 * - 성공/실패 후 목록 캐시를 갱신해 lastStatus를 최신화한다.
 */
export function useTestApiConnection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiConnectionsApi.test(id).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}

/**
 * 전체 API 연결 일괄 갱신 (관리자 전용)
 * - 백엔드 스케줄러를 즉시 트리거하고 jobId를 반환한다.
 */
export function useRefreshAllApiConnections() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiConnectionsApi.refreshAll().then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QUERY_KEY] });
    },
  });
}

/**
 * 파이프라인 스텝용 슬림 연결 목록 조회
 * - 일반 로그인 사용자도 접근 가능 (selectable 엔드포인트)
 * - staleTime 60s: 파이프라인 에디터에서 자주 재렌더링되므로 캐시 유지
 */
export function useApiConnectionsSelectable() {
  return useQuery({
    queryKey: [QUERY_KEY, 'selectable'],
    queryFn: () => apiConnectionsApi.getSelectable().then((r) => r.data),
    staleTime: 60_000,
  });
}
