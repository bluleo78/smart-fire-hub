import type {
  CreateTenantRequest,
  TenantMemberResponse,
  TenantSummaryResponse,
} from '../types/platform';
import { client } from './client';

export const tenantsApi = {
  /** 쿼리 파라미터가 없다 — 검색·정렬·페이징은 전부 클라이언트 몫이다. */
  list: () => client.get<TenantSummaryResponse[]>('/tenants'),
  get: (id: number) => client.get<TenantSummaryResponse>(`/tenants/${id}`),
  create: (data: CreateTenantRequest) => client.post<TenantSummaryResponse>('/tenants', data),
  /** 204. 즉시 차단이 아니다 — 발급된 액세스 토큰은 만료(기본 30분)까지 유효하다. */
  suspend: (id: number) => client.post<void>(`/tenants/${id}/suspend`),
  activate: (id: number) => client.post<void>(`/tenants/${id}/activate`),
  members: (id: number) => client.get<TenantMemberResponse[]>(`/tenants/${id}/members`),
};
