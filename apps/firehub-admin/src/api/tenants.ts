import type {
  ConnectionTestResult,
  CreateTenantRequest,
  DataStoreBackendOption,
  DataStoreVerifyResponse,
  TenantCreatedResponse,
  TenantDataStoreResponse,
  TenantMemberResponse,
  TenantSummaryResponse,
} from '../types/platform';
import { client } from './client';

export const tenantsApi = {
  /** 쿼리 파라미터가 없다 — 검색·정렬·페이징은 전부 클라이언트 몫이다. */
  list: () => client.get<TenantSummaryResponse[]>('/tenants'),
  get: (id: number) => client.get<TenantSummaryResponse>(`/tenants/${id}`),
  create: (data: CreateTenantRequest) => client.post<TenantCreatedResponse>('/tenants', data),
  /** 204. 즉시 차단이 아니다 — 발급된 액세스 토큰은 만료(기본 30분)까지 유효하다. */
  suspend: (id: number) => client.post<void>(`/tenants/${id}/suspend`),
  activate: (id: number) => client.post<void>(`/tenants/${id}/activate`),
  members: (id: number) => client.get<TenantMemberResponse[]>(`/tenants/${id}/members`),
  /** 선택 가능한 저장소 백엔드 목록. 플래그가 꺼진 백엔드는 available=false. */
  dataStoreBackends: () => client.get<DataStoreBackendOption[]>('/tenants/data-store/backends'),
  /** 생성 전 연결 테스트(DBA_AUTO 전용). 접속 실패도 200 + ok=false 로 온다. */
  testDataStore: (req: {
    backend: string;
    host: string;
    port: number;
    database: string;
    credentials: { username: string; password: string };
  }) => client.post<ConnectionTestResult>('/tenants/data-store/test', req),
  dataStore: (id: number) => client.get<TenantDataStoreResponse>(`/tenants/${id}/data-store`),
  verifyDataStore: (id: number) =>
    client.post<DataStoreVerifyResponse>(`/tenants/${id}/data-store/verify`),
  /** PENDING 에서만(409 DATA_STORE_NOT_PENDING). 계정 비밀번호가 교체되고 새 스크립트가 온다. */
  reissueDataStoreScript: (id: number) =>
    client.post<{ script: string }>(`/tenants/${id}/data-store/script`),
};
