import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiConnectionsApi } from '../../api/api-connections';
import type { CreateApiConnectionRequest, UpdateApiConnectionRequest } from '../../types/api-connection';

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
