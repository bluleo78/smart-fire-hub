import type { AxiosInstance } from 'axios';

export function createTriggerApi(client: AxiosInstance) {
  return {
    async listTriggers(pipelineId: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${pipelineId}/triggers`);
      return response.data;
    },
    /**
     * 트리거 한 건을 조회한다(GET /pipelines/{pid}/triggers/{tid}).
     * update_trigger 도구가 이름 없이·config 일부만 받은 수정을 서버 계약(name 필수, config 통째 교체)에 맞추려고
     * 기존 이름·config 를 읽는 데 쓴다(WD-227).
     */
    async getTrigger(pipelineId: number, triggerId: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${pipelineId}/triggers/${triggerId}`);
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
