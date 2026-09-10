import type { AxiosInstance } from 'axios';

/** 저장된 API 연결의 상태 점검 결과 */
export interface TestConnectionResponse {
  ok: boolean;
  status: number | null;
  latencyMs: number;
  errorMessage: string | null;
}

/** 파이프라인 스텝 참조용 slim DTO */
export interface ApiConnectionSelectable {
  id: number;
  name: string;
  authType: string;
  baseUrl: string;
}

/**
 * 백엔드 `ApiConnectionReferencesResponse` 레코드의 TypeScript 미러.
 * 이 API 연결을 사용하는(pipeline_step.api_connection_id로 참조하는) 파이프라인 목록.
 * 삭제 전 영향 범위 확인용(#605) — dataset-manager의 get_dataset_references와 동일 패턴.
 */
export interface ApiConnectionReferences {
  apiConnectionId: number;
  pipelines: { id: number; name: string }[];
  totalCount: number;
}

export function createConnectionApi(client: AxiosInstance) {
  return {
    async listApiConnections(): Promise<unknown> {
      const response = await client.get('/api-connections');
      return response.data;
    },
    async getApiConnection(id: number): Promise<unknown> {
      const response = await client.get(`/api-connections/${id}`);
      return response.data;
    },
    /** 새 API 연결 생성. baseUrl(필수)과 healthCheckPath(선택)를 포함한다. */
    async createApiConnection(data: {
      name: string;
      description?: string;
      authType: string;
      authConfig: Record<string, string>;
      baseUrl: string;
      healthCheckPath?: string;
    }): Promise<unknown> {
      const response = await client.post('/api-connections', data);
      return response.data;
    },
    /** API 연결 부분 수정. baseUrl/healthCheckPath도 선택적으로 갱신 가능. */
    async updateApiConnection(
      id: number,
      data: {
        name?: string;
        description?: string;
        authType?: string;
        authConfig?: Record<string, string>;
        baseUrl?: string;
        healthCheckPath?: string;
      },
    ): Promise<unknown> {
      const response = await client.put(`/api-connections/${id}`, data);
      return response.data;
    },
    async deleteApiConnection(id: number): Promise<unknown> {
      await client.delete(`/api-connections/${id}`);
      return { success: true };
    },
    /**
     * 이 API 연결을 참조하는 파이프라인 목록을 조회한다. 삭제 전 영향 범위 확인용(#605).
     * pipeline_step.api_connection_id는 FK(ON DELETE 절 없음 → 기본 RESTRICT)라 참조가 있으면
     * 실제 삭제는 409로 거부되지만, 그 전에는 확인할 방법이 없었다 — dataset-manager의
     * get_dataset_references와 동일한 사전 확인 패턴을 제공한다.
     */
    async getApiConnectionReferences(id: number): Promise<ApiConnectionReferences> {
      const response = await client.get<ApiConnectionReferences>(
        `/api-connections/${id}/references`,
      );
      return response.data;
    },
    /** 저장된 API 연결을 즉시 테스트 호출하고 상태를 반환한다. 결과는 DB에도 반영된다. */
    async testApiConnection(id: number): Promise<TestConnectionResponse> {
      const response = await client.post<TestConnectionResponse>(`/api-connections/${id}/test`);
      return response.data;
    },
    /** 일반 사용자용 slim 목록 — 파이프라인 스텝에서 apiConnectionId 선택 등에 사용. */
    async listSelectableConnections(): Promise<ApiConnectionSelectable[]> {
      const response = await client.get<ApiConnectionSelectable[]>('/api-connections/selectable');
      return response.data;
    },
  };
}
