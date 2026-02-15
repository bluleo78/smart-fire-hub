import { client } from './client';
import type {
  PipelineResponse, PipelineDetailResponse, CreatePipelineRequest,
  PipelineExecutionResponse, ExecutionDetailResponse,
} from '../types/pipeline';
import type { PageResponse } from '../types/common';

export const pipelinesApi = {
  getPipelines: (params: { page?: number; size?: number }) =>
    client.get<PageResponse<PipelineResponse>>('/pipelines', { params }),
  getPipelineById: (id: number) =>
    client.get<PipelineDetailResponse>(`/pipelines/${id}`),
  createPipeline: (data: CreatePipelineRequest) =>
    client.post<PipelineDetailResponse>('/pipelines', data),
  updatePipeline: (id: number, data: { name: string; description?: string }) =>
    client.put(`/pipelines/${id}`, data),
  deletePipeline: (id: number) =>
    client.delete(`/pipelines/${id}`),
  executePipeline: (id: number) =>
    client.post<PipelineExecutionResponse>(`/pipelines/${id}/execute`),
  getExecutions: (pipelineId: number) =>
    client.get<PipelineExecutionResponse[]>(`/pipelines/${pipelineId}/executions`),
  getExecutionById: (pipelineId: number, execId: number) =>
    client.get<ExecutionDetailResponse>(`/pipelines/${pipelineId}/executions/${execId}`),
};
