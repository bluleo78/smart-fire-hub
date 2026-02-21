import { client } from './client';
import type {
  PipelineResponse, PipelineDetailResponse, CreatePipelineRequest,
  UpdatePipelineRequest, PipelineExecutionResponse, ExecutionDetailResponse,
  TriggerResponse, TriggerEventResponse, CreateTriggerRequest, UpdateTriggerRequest,
} from '../types/pipeline';
import type { PageResponse } from '../types/common';

export const pipelinesApi = {
  getPipelines: (params: { page?: number; size?: number }) =>
    client.get<PageResponse<PipelineResponse>>('/pipelines', { params }),
  getPipelineById: (id: number) =>
    client.get<PipelineDetailResponse>(`/pipelines/${id}`),
  createPipeline: (data: CreatePipelineRequest) =>
    client.post<PipelineDetailResponse>('/pipelines', data),
  updatePipeline: (id: number, data: UpdatePipelineRequest) =>
    client.put(`/pipelines/${id}`, data),
  deletePipeline: (id: number) =>
    client.delete(`/pipelines/${id}`),
  executePipeline: (id: number) =>
    client.post<PipelineExecutionResponse>(`/pipelines/${id}/execute`),
  getExecutions: (pipelineId: number) =>
    client.get<PipelineExecutionResponse[]>(`/pipelines/${pipelineId}/executions`),
  getExecutionById: (pipelineId: number, execId: number) =>
    client.get<ExecutionDetailResponse>(`/pipelines/${pipelineId}/executions/${execId}`),

  // Trigger APIs
  getTriggers: (pipelineId: number) =>
    client.get<TriggerResponse[]>(`/pipelines/${pipelineId}/triggers`),
  createTrigger: (pipelineId: number, data: CreateTriggerRequest) =>
    client.post<TriggerResponse>(`/pipelines/${pipelineId}/triggers`, data),
  updateTrigger: (pipelineId: number, triggerId: number, data: UpdateTriggerRequest) =>
    client.put(`/pipelines/${pipelineId}/triggers/${triggerId}`, data),
  deleteTrigger: (pipelineId: number, triggerId: number) =>
    client.delete(`/pipelines/${pipelineId}/triggers/${triggerId}`),
  toggleTrigger: (pipelineId: number, triggerId: number) =>
    client.patch(`/pipelines/${pipelineId}/triggers/${triggerId}/toggle`),
  getTriggerEvents: (pipelineId: number, limit?: number) =>
    client.get<TriggerEventResponse[]>(`/pipelines/${pipelineId}/trigger-events`, { params: { limit } }),
};
