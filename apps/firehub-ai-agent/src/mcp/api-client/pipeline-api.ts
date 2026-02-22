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
    async previewApiCall(data: {
      url: string;
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
      const response = await client.post('/pipelines/api-call/preview', data);
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
