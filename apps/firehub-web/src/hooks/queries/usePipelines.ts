import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { pipelinesApi } from '../../api/pipelines';
import { fetchAllPages } from '../../lib/fetch-all-pages';
import type { CreateTriggerRequest, PipelineExecutionResponse, UpdatePipelineRequest, UpdateTriggerRequest } from '../../types/pipeline';

export function usePipelines(params: { page?: number; size?: number }) {
  return useQuery({
    queryKey: ['pipelines', params],
    queryFn: () => pipelinesApi.getPipelines(params).then(r => r.data),
  });
}

/** 서버가 목록 조회 size 에 @Max(200) 을 걸어 초과 시 400 을 주므로(PipelineController) 이 크기로 페이지를 순회한다. */
const ALL_PIPELINES_PAGE_SIZE = 200;

/**
 * 선택 목록(연쇄 트리거의 선행 파이프라인 등)용 전체 파이프라인 조회 (#737).
 *
 * 왜: `size: 1000` 한 번으로 받으려 하면 서버가 400 을 돌려 목록이 항상 비었다.
 * 서버 상한 이하 크기로 전 페이지를 순회해 모은다. queryKey 가 'pipelines' 로 시작하므로
 * 파이프라인 생성·삭제·수정 시의 기존 무효화에 함께 걸린다.
 */
export function useAllPipelines() {
  return useQuery({
    queryKey: ['pipelines', 'all'],
    queryFn: () =>
      fetchAllPages(
        (page, size) => pipelinesApi.getPipelines({ page, size }).then((r) => r.data),
        ALL_PIPELINES_PAGE_SIZE,
      ),
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

// 증분 처리 스텝의 "처음부터 다시 만들기" 예약/취소 — 성공 시 파이프라인 상세를 무효화해
// StepConfigPanel이 최신 lastRunAt/fullRebuildPending/warnings를 다시 조회하게 한다.
export function useReserveFullRebuild(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (stepId: number) => pipelinesApi.reserveFullRebuild(pipelineId, stepId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId] });
    },
  });
}

export function useCancelFullRebuild(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (stepId: number) => pipelinesApi.cancelFullRebuild(pipelineId, stepId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId] });
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

// --- Trigger hooks ---

export function useTriggers(pipelineId: number) {
  return useQuery({
    queryKey: ['triggers', pipelineId],
    queryFn: () => pipelinesApi.getTriggers(pipelineId).then(r => r.data),
    enabled: pipelineId > 0,
  });
}

export function useCreateTrigger(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateTriggerRequest) =>
      pipelinesApi.createTrigger(pipelineId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
    },
  });
}

export function useUpdateTrigger(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ triggerId, data }: { triggerId: number; data: UpdateTriggerRequest }) =>
      pipelinesApi.updateTrigger(pipelineId, triggerId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
    },
  });
}

export function useDeleteTrigger(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (triggerId: number) =>
      pipelinesApi.deleteTrigger(pipelineId, triggerId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
    },
  });
}

export function useToggleTrigger(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (triggerId: number) =>
      pipelinesApi.toggleTrigger(pipelineId, triggerId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['triggers', pipelineId] });
    },
  });
}

export function useTriggerEvents(pipelineId: number) {
  return useQuery({
    queryKey: ['trigger-events', pipelineId],
    queryFn: () => pipelinesApi.getTriggerEvents(pipelineId, 20).then(r => r.data),
    enabled: pipelineId > 0,
  });
}
