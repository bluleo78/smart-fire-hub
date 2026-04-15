import type {
  ApiConnectionResponse,
  ApiConnectionSelectable,
  CreateApiConnectionRequest,
  TestConnectionResponse,
  UpdateApiConnectionRequest,
} from '../types/api-connection';
import { client } from './client';

export const apiConnectionsApi = {
  getAll: () =>
    client.get<ApiConnectionResponse[]>('/api-connections'),
  getById: (id: number) =>
    client.get<ApiConnectionResponse>(`/api-connections/${id}`),
  create: (data: CreateApiConnectionRequest) =>
    client.post<ApiConnectionResponse>('/api-connections', data),
  update: (id: number, data: UpdateApiConnectionRequest) =>
    client.put<ApiConnectionResponse>(`/api-connections/${id}`, data),
  delete: (id: number) =>
    client.delete(`/api-connections/${id}`),
  /** 연결 상태 즉시 점검 (관리자 전용) */
  test: (id: number) =>
    client.post<TestConnectionResponse>(`/api-connections/${id}/test`),
  /** 전체 연결 일괄 갱신 (관리자 전용) */
  refreshAll: () =>
    client.post<{ jobId: string }>('/api-connections/refresh-all'),
  /** 파이프라인 스텝용 슬림 목록 (인증 사용자 누구나) */
  getSelectable: () =>
    client.get<ApiConnectionSelectable[]>('/api-connections/selectable'),
};
