import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { pipelinesApi } from '../../api/pipelines';

export function usePipelines(params: { page?: number; size?: number }) {
  return useQuery({
    queryKey: ['pipelines', params],
    queryFn: () => pipelinesApi.getPipelines(params).then(r => r.data),
  });
}

export function usePipeline(id: number) {
  return useQuery({
    queryKey: ['pipelines', id],
    queryFn: () => pipelinesApi.getPipelineById(id).then(r => r.data),
    enabled: !!id,
  });
}

export function useCreatePipeline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: pipelinesApi.createPipeline,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pipelines'] }),
  });
}

export function useDeletePipeline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: pipelinesApi.deletePipeline,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['pipelines'] }),
  });
}

export function useExecutePipeline(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => pipelinesApi.executePipeline(pipelineId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId, 'executions'] });
    },
  });
}

export function useExecutions(pipelineId: number) {
  return useQuery({
    queryKey: ['pipelines', pipelineId, 'executions'],
    queryFn: () => pipelinesApi.getExecutions(pipelineId).then(r => r.data),
    enabled: !!pipelineId,
  });
}

export function useExecution(pipelineId: number, execId: number) {
  return useQuery({
    queryKey: ['pipelines', pipelineId, 'executions', execId],
    queryFn: () => pipelinesApi.getExecutionById(pipelineId, execId).then(r => r.data),
    enabled: !!pipelineId && !!execId,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && (data.status === 'PENDING' || data.status === 'RUNNING')) return 3000;
      return false;
    },
  });
}
