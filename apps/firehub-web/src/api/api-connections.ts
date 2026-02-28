import type {
  ApiConnectionResponse,
  CreateApiConnectionRequest,
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
};
