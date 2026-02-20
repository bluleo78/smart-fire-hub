import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { pipelinesApi } from '../../api/pipelines';
import type { UpdatePipelineRequest, PipelineExecutionResponse } from '../../types/pipeline';

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

export function useUpdatePipeline(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdatePipelineRequest) =>
      pipelinesApi.updatePipeline(pipelineId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
      queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId] });
    },
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
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasActive = data?.some((e: PipelineExecutionResponse) => e.status === 'PENDING' || e.status === 'RUNNING');
      return hasActive ? 5000 : false;
    },
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
