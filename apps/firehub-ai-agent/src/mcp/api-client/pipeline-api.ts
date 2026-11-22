import type { AxiosInstance } from 'axios';

export function createPipelineApi(client: AxiosInstance) {
  return {
    async listPipelines(params?: { page?: number; size?: number }): Promise<unknown> {
      const response = await client.get('/pipelines', { params });
      return response.data;
    },
    async getPipeline(id: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${id}`);
      return response.data;
    },
    async createPipeline(data: {
      name: string;
      description?: string;
      steps: Array<{
        name: string;
        description?: string;
        scriptType: string;
        scriptContent?: string;
        outputDatasetId?: number;
        inputDatasetIds?: number[];
        dependsOnStepNames?: string[];
        loadStrategy?: string;
        apiConfig?: Record<string, unknown>;
        aiConfig?: Record<string, unknown>;
        apiConnectionId?: number;
      }>;
    }): Promise<unknown> {
      const response = await client.post('/pipelines', data);
      return response.data;
    },
    async updatePipeline(
      id: number,
      data: {
        name?: string;
        description?: string;
        isActive?: boolean;
        steps?: Array<{
          name: string;
          description?: string;
          scriptType: string;
          scriptContent?: string;
          outputDatasetId?: number;
          inputDatasetIds?: number[];
          dependsOnStepNames?: string[];
          loadStrategy?: string;
          apiConfig?: Record<string, unknown>;
          aiConfig?: Record<string, unknown>;
          apiConnectionId?: number;
        }>;
      },
    ): Promise<unknown> {
      await client.put(`/pipelines/${id}`, data);
      return { success: true };
    },
    async deletePipeline(id: number): Promise<unknown> {
      await client.delete(`/pipelines/${id}`);
      return { success: true };
    },
    /**
     * API 호출 미리보기.
     * 서버는 주소를 `url` 로만 읽으므로 도구 인자(path/customUrl)를 실행기와 같은 우선순위
     * (연결이면 path, 아니면 customUrl)로 `url` 에 담는다. 주소가 없으면 연결의 기본 주소를
     * 잘못 부르지 않도록 보내지 않고 거절한다.
     */
    async previewApiCall(data: {
      path?: string;
      customUrl?: string;
      method: string;
      headers?: Record<string, string>;
      queryParams?: Record<string, string>;
      body?: string;
      dataPath: string;
      fieldMappings?: Array<{ sourceField: string; targetColumn: string; dataType?: string }>;
      apiConnectionId?: number;
      inlineAuth?: Record<string, string>;
      timeoutMs?: number;
    }): Promise<unknown> {
      // GET 만 보낸다: 다른 메서드는 저장된 인증으로 실제 데이터를 바꾸는데 이 도구에는 확인 절차가 없다.
      const method = data.method.toUpperCase();
      if (method !== 'GET') {
        throw new Error(
          `API_CALL: 에이전트 미리보기는 GET 만 보냅니다 — ${method} 요청은 실제 데이터를 바꿀 수 있어 사용자가 파이프라인 편집기의 「테스트 호출」로 확인해야 합니다`,
        );
      }
      const { path, customUrl, ...rest } = data;
      const connected = rest.apiConnectionId != null;
      const url = connected ? path : customUrl;
      if (url == null || url.trim() === '') {
        throw new Error(
          connected
            ? 'API_CALL: apiConnectionId 설정 시 path가 필수입니다(연결의 기본 주소 그대로 부르려면 \'/\')'
            : 'API_CALL: apiConnectionId 없이 호출하려면 customUrl이 필수입니다',
        );
      }
      // url 을 마지막에 둬 입력에 섞인 옛 url 보다 검증한 주소가 이긴다.
      const response = await client.post('/pipelines/api-call/preview', { ...rest, url });
      return response.data;
    },
    async executePipeline(id: number): Promise<unknown> {
      const response = await client.post(`/pipelines/${id}/execute`);
      return response.data;
    },
    async getExecutionStatus(pipelineId: number, executionId: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${pipelineId}/executions/${executionId}`);
      return response.data;
    },
  };
}
