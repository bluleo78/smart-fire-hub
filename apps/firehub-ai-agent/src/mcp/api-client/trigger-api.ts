import type { AxiosInstance } from 'axios';

export function createTriggerApi(client: AxiosInstance) {
  return {
    async listTriggers(pipelineId: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${pipelineId}/triggers`);
      return response.data;
    },
    async createTrigger(
      pipelineId: number,
      data: {
        name: string;
        triggerType: string;
        description?: string;
        config: Record<string, unknown>;
      },
    ): Promise<unknown> {
      const response = await client.post(`/pipelines/${pipelineId}/triggers`, data);
      return response.data;
    },
    async updateTrigger(
      pipelineId: number,
      triggerId: number,
      data: {
        name?: string;
        isEnabled?: boolean;
        description?: string;
        config?: Record<string, unknown>;
      },
    ): Promise<unknown> {
      await client.put(`/pipelines/${pipelineId}/triggers/${triggerId}`, data);
      return { success: true };
    },
    async deleteTrigger(pipelineId: number, triggerId: number): Promise<unknown> {
      await client.delete(`/pipelines/${pipelineId}/triggers/${triggerId}`);
      return { success: true };
    },
  };
}
