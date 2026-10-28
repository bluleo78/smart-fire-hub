import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { pipelinesApi } from '../../api/pipelines';
import { fetchAllPages } from '../../lib/fetch-all-pages';
import type { CreateTriggerRequest, PipelineExecutionResponse, UpdatePipelineRequest, UpdateTriggerRequest } from '../../types/pipeline';
import { useMyPermissions } from './useMyPermissions';

/**
 * 파이프라인 목록(추가·삭제)과 상세 화면(헤더·실행 이력·트리거 탭·증분 처리)의 쓰기 진입점을 보일지 정하는 권한 묶음 (WD-219).
 * 화면은 권한 코드를 직접 읽지 않고 이 훅만 쓴다(데이터셋 상세의 useDatasetPermissions 와 같은 방식, WD-139·WD-144).
 *
 * 조회 권한만 있는 사용자(pipeline:read·trigger:read)에게 「실행」「수정」「트리거 추가」·⋯ 메뉴를 보여 주면
 * 눌러야 403 토스트를 받는다. 서버 컨트롤러(PipelineController·TriggerController)의 @RequirePermission 을 그대로 따라 나눈다 —
 * 정의 수정(pipeline:write)·실행/취소(pipeline:execute)·트리거 생성/수정/켜고 끄기(trigger:write)·트리거 삭제(trigger:delete)는
 * 서로 다른 권한이라 하나로 뭉치면 일부 사용자가 잘못 막히거나 열린다.
 * 권한을 불러오는 동안은 빈 집합이라 모두 false — 버튼이 나타났다 사라지는 깜빡임이 없다. 최종 판정은 서버이고 여기는 노출 결정용이다.
 */
export function usePipelinePermissions() {
  const { permissions, isLoading } = useMyPermissions();
  return {
    /** 파이프라인 목록·상세·실행 이력 조회(GET /pipelines…) — pipeline:read. 홈의 파이프라인 요약·바로가기도 이것으로 가린다(WD-272) */
    canReadPipeline: permissions.has('pipeline:read'),
    /** 「수정」(PUT /pipelines/{id})·증분 처리 재생성 예약/취소(POST·DELETE .../full-rebuild) — pipeline:write */
    canWritePipeline: permissions.has('pipeline:write'),
    /** 목록 행 삭제(DELETE /pipelines/{id}) — pipeline:delete. 「파이프라인 추가」·/pipelines/new 진입은 canWritePipeline(POST /pipelines 가 pipeline:write) */
    canDeletePipeline: permissions.has('pipeline:delete'),
    /** 「실행」·「재실행」(POST .../execute)·「실행 취소」(POST .../executions/{execId}/cancel) — pipeline:execute */
    canExecutePipeline: permissions.has('pipeline:execute'),
    /** 「트리거 추가」(POST)·편집(PUT)·활성화/비활성화(PATCH .../toggle) — trigger:write */
    canWriteTrigger: permissions.has('trigger:write'),
    /** 트리거 삭제(DELETE .../triggers/{triggerId}) — trigger:delete */
    canDeleteTrigger: permissions.has('trigger:delete'),
    isLoading,
  };
}

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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
      // 파이프라인이 만든 자동 생성 임시 데이터셋도 함께 지워지므로(WD-170) 데이터셋 목록도 다시 읽는다.
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}

/**
 * 파이프라인 삭제 영향(WD-170) — 삭제 확인 창 내용 안에서 쓰므로 창이 열려 있을 때만 마운트되어 조회한다.
 * gcTime 0: 창이 닫혀 언마운트되면 결과를 버린다 — 다시 열면 새로 조회한다(그 사이 실행으로 생긴 임시 데이터셋을 놓치지 않게).
 * retry false: 실패(시간 초과 포함)하면 바로 안내로 넘어간다(재시도 동안 삭제가 막혀 있지 않게). WD-86 useDeleteImpact 와 같은 규칙.
 */
export function usePipelineDeleteImpact(id: number) {
  return useQuery({
    queryKey: ['pipelines', id, 'delete-impact'],
    queryFn: ({ signal }) => pipelinesApi.getDeleteImpact(id, signal).then((r) => r.data),
    gcTime: 0,
    retry: false,
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
      // 저장에서 뺀 스텝의 자동 생성 임시 데이터셋이 함께 지워지므로(WD-170) 데이터셋 목록도 다시 읽는다.
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
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

/**
 * 진행 중 실행 취소(WD-153) — 변수는 취소할 실행 id.
 *
 * 성공 시 실행 목록·상세(['pipelines', id, 'executions', ...] prefix)를 무효화한다. 무효화 Promise 를 돌려줘
 * 재조회가 끝날 때까지 isPending 을 유지한다 — 그러지 않으면 서버의 cancelRequestedAt 이 도착하기 전 잠깐
 * 「실행 취소」 버튼이 다시 눌릴 수 있게 된다. RUNNING 으로 응답하면 상세/목록 폴링이 이어져 CANCELLED 전이를 잡는다.
 */
export function useCancelExecution(pipelineId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (executionId: number) =>
      pipelinesApi.cancelExecution(pipelineId, executionId).then((r) => r.data),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId, 'executions'] }),
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

/** 실행이 끝난 상태(더 이상 바뀌지 않는 상태)인지 — PENDING/RUNNING 이 아니면 종료로 본다. */
function isTerminalExecution(e: PipelineExecutionResponse) {
  return e.status !== 'PENDING' && e.status !== 'RUNNING';
}

/**
 * 실행 이력 조회 + 실행 종료 시 파이프라인 상세 무효화 (#733).
 *
 * 왜: 증분 스텝의 lastRunAt/fullRebuildPending 은 파이프라인 상세(['pipelines', id])의 steps 에서 오는데,
 * 실행은 비동기로 끝나고 서버가 그때 책갈피를 전진시키고 재생성 예약을 소비한다. 실행 목록만 다시 불러오면
 * 소비된 예약이 "다음 실행 시 전체 재생성 예정" 으로 남는다.
 *
 * 무엇을: "종료된 실행 목록"의 서명(id:status)이 바뀌는 순간 상세를 무효화한다. RUNNING → COMPLETED 전이뿐
 * 아니라, 실행이 너무 빨리 끝나 재조회가 곧바로 COMPLETED 를 받는(RUNNING 을 한 번도 못 본) 경우도
 * 새 종료 실행이 서명에 추가되므로 함께 잡힌다. 첫 로드는 기준값만 잡고 무효화하지 않는다.
 */
export function useExecutions(pipelineId: number) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ['pipelines', pipelineId, 'executions'],
    queryFn: () => pipelinesApi.getExecutions(pipelineId).then(r => r.data),
    enabled: !!pipelineId,
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasActive = data?.some((e: PipelineExecutionResponse) => e.status === 'PENDING' || e.status === 'RUNNING');
      return hasActive ? 5000 : false;
    },
  });

  const terminalSignature = query.data
    ?.filter(isTerminalExecution)
    .map((e) => `${e.id}:${e.status}`)
    .join(',');
  // 이전 서명 — undefined 면 아직 기준값이 없는 상태(첫 로드 전)다. 파이프라인이 바뀌면 기준을 다시 잡는다.
  const prevSignatureRef = useRef<{ pipelineId: number; signature: string } | undefined>(undefined);
  useEffect(() => {
    if (terminalSignature === undefined) return;
    const prev = prevSignatureRef.current;
    prevSignatureRef.current = { pipelineId, signature: terminalSignature };
    if (!prev || prev.pipelineId !== pipelineId || prev.signature === terminalSignature) return;
    // exact: 실행 목록(['pipelines', id, 'executions'])까지 prefix 로 다시 불러오지 않도록 상세만 무효화한다.
    queryClient.invalidateQueries({ queryKey: ['pipelines', pipelineId], exact: true });
  }, [terminalSignature, pipelineId, queryClient]);

  return query;
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
