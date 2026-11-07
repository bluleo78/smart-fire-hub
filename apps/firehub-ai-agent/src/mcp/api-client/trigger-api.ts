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
    /**
     * 트리거를 수정한다(PUT). expectedUpdatedAt 은 수정 전에 읽은 트리거의 updatedAt — 그 사이 사람이 먼저 고쳤으면 서버가
     * 409 EDIT_CONFLICT 로 거부한다(WD-576). 빼면 비교 없이 저장된다.
     */
    async updateTrigger(
      pipelineId: number,
      triggerId: number,
      data: {
        name?: string;
        isEnabled?: boolean;
        description?: string;
        config?: Record<string, unknown>;
        expectedUpdatedAt?: string;
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
