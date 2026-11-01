import type { CronPreviewParams, CronPreviewResponse, PageResponse } from '../types/common';
import type {
CancelExecutionResponse,
CreatePipelineRequest,
CreateTriggerRequest, ExecutionDetailResponse,
PipelineDeleteImpact,
PipelineDetailResponse, PipelineExecutionResponse,   PipelineResponse, TriggerEventResponse,   TriggerResponse,   UpdatePipelineRequest, UpdatePipelineResponse, UpdateTriggerRequest,
} from '../types/pipeline';
import { client } from './client';

/** 삭제 영향 조회 시간 제한(WD-170) — 분석 삭제 영향(WD-86)과 같은 값. 넘으면 실패로 보고 경고 + 삭제를 허용한다. */
const DELETE_IMPACT_TIMEOUT_MS = 10_000;

export const pipelinesApi = {
  getPipelines: (params: { page?: number; size?: number }) =>
    client.get<PageResponse<PipelineResponse>>('/pipelines', { params }),
  getPipelineById: (id: number) =>
    client.get<PipelineDetailResponse>(`/pipelines/${id}`),
  createPipeline: (data: CreatePipelineRequest) =>
    client.post<PipelineDetailResponse>('/pipelines', data),
  updatePipeline: (id: number, data: UpdatePipelineRequest) =>
    client.put<UpdatePipelineResponse>(`/pipelines/${id}`, data),
  deletePipeline: (id: number) =>
    client.delete(`/pipelines/${id}`),
  /**
   * 삭제 영향(WD-170) — 함께 지워질 자동 생성 임시 데이터셋 개수. 삭제 확인 창을 열 때만 조회한다.
   * timeout: 조회가 삭제를 무한정 막지 않게 끊는다(넘으면 호출부가 실패로 보고 경고 + 삭제 허용 — WD-86 과 같은 값).
   */
  getDeleteImpact: (id: number, signal?: AbortSignal) =>
    client.get<PipelineDeleteImpact>(`/pipelines/${id}/delete-impact`, {
      signal,
      timeout: DELETE_IMPACT_TIMEOUT_MS,
    }),
  executePipeline: (id: number) =>
    client.post<PipelineExecutionResponse>(`/pipelines/${id}/execute`),
  // 증분 처리 스텝의 다음 실행에서 전체 재생성(책갈피 무시)을 예약/취소한다.
  reserveFullRebuild: (pipelineId: number, stepId: number) =>
    client.post<void>(`/pipelines/${pipelineId}/steps/${stepId}/full-rebuild`),
  cancelFullRebuild: (pipelineId: number, stepId: number) =>
    client.delete<void>(`/pipelines/${pipelineId}/steps/${stepId}/full-rebuild`),
  getExecutions: (pipelineId: number) =>
    client.get<PipelineExecutionResponse[]>(`/pipelines/${pipelineId}/executions`),
  getExecutionById: (pipelineId: number, execId: number) =>
    client.get<ExecutionDetailResponse>(`/pipelines/${pipelineId}/executions/${execId}`),
  // 진행 중(PENDING/RUNNING) 실행을 취소한다(WD-153). PENDING 은 즉시 CANCELLED, RUNNING 은 요청만 기록되고
  // 실행기가 다음 점검 지점에서 멈춘다. 이미 끝난 실행은 409.
  cancelExecution: (pipelineId: number, execId: number) =>
    client.post<CancelExecutionResponse>(`/pipelines/${pipelineId}/executions/${execId}/cancel`),

  // Trigger APIs
  /** 스케줄 트리거 편집 창 「다음 5회」 미리보기 — 스케줄러와 같은 서머타임 규칙으로 서버가 계산한다(WD-391). */
  getTriggerCronPreview: (params: CronPreviewParams) =>
    client.get<CronPreviewResponse>('/triggers/cron-preview', { params }),
  getTriggers: (pipelineId: number) =>
    client.get<TriggerResponse[]>(`/pipelines/${pipelineId}/triggers`),
  createTrigger: (pipelineId: number, data: CreateTriggerRequest) =>
    client.post<TriggerResponse>(`/pipelines/${pipelineId}/triggers`, data),
  updateTrigger: (pipelineId: number, triggerId: number, data: UpdateTriggerRequest) =>
    client.put(`/pipelines/${pipelineId}/triggers/${triggerId}`, data),
  deleteTrigger: (pipelineId: number, triggerId: number) =>
    client.delete(`/pipelines/${pipelineId}/triggers/${triggerId}`),
  // 원하는 상태(enabled)를 실어 보낸다 — 서버가 현재 값을 뒤집으면 옛 화면에서 누른 요청이 반대로 적용된다(WD-435).
  toggleTrigger: (pipelineId: number, triggerId: number, enabled: boolean) =>
    client.patch(`/pipelines/${pipelineId}/triggers/${triggerId}/toggle`, { enabled }),
  getTriggerEvents: (pipelineId: number, limit?: number) =>
    client.get<TriggerEventResponse[]>(`/pipelines/${pipelineId}/trigger-events`, { params: { limit } }),

  previewApiCall: (data: {
    url: string;
    method: string;
    headers?: Record<string, string>;
    queryParams?: Record<string, string>;
    body?: string;
    dataPath?: string;
    fieldMappings?: Array<{ sourceField: string; targetColumn: string; dataType?: string }>;
    apiConnectionId?: number | null;
    inlineAuth?: Record<string, string>;
    timeoutMs?: number;
  }) =>
    client.post<{
      success: boolean;
      rawJson: string | null;
      rows: Array<Record<string, unknown>>;
      columns: string[];
      totalExtractedRows: number;
      errorMessage: string | null;
    }>('/pipelines/api-call/preview', data),
};
