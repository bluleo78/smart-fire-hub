import type { AxiosInstance } from 'axios';

export function createProactiveApi(client: AxiosInstance) {
  return {
    async listSmartJobs(): Promise<unknown> {
      const response = await client.get('/proactive/jobs');
      return response.data;
    },
    async createSmartJob(data: {
      name: string;
      prompt: string;
      cronExpression: string;
      timezone?: string;
      templateId?: number;
      channels?: string[];
    }): Promise<unknown> {
      const response = await client.post('/proactive/jobs', data);
      return response.data;
    },
    async updateSmartJob(
      id: number,
      data: {
        name?: string;
        prompt?: string;
        cronExpression?: string;
        timezone?: string;
        templateId?: number;
        channels?: string[];
        enabled?: boolean;
      },
    ): Promise<unknown> {
      const response = await client.put(`/proactive/jobs/${id}`, data);
      return response.data;
    },
    async deleteSmartJob(id: number): Promise<unknown> {
      await client.delete(`/proactive/jobs/${id}`);
      return { success: true };
    },
    async executeSmartJob(id: number): Promise<unknown> {
      const response = await client.post(`/proactive/jobs/${id}/execute`);
      return response.data;
    },
    async listReportTemplates(): Promise<unknown> {
      const response = await client.get('/proactive/templates');
      return response.data;
    },
    async createReportTemplate(data: {
      name: string;
      description?: string;
      style?: string;
      structure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
        }>;
        output_format: string;
      };
    }): Promise<unknown> {
      const response = await client.post('/proactive/templates', data);
      return response.data;
    },
    async createSmartJobWithTemplate(data: {
      name: string;
      prompt: string;
      cronExpression?: string;
      timezone?: string;
      channels?: string[];
      templateName: string;
      templateStructure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
          instruction?: string;
          children?: unknown[];
        }>;
        output_format: string;
      };
      templateStyle?: string;
    }): Promise<unknown> {
      // 1. Create template
      const template = await client.post('/proactive/templates', {
        name: data.templateName,
        description: `AI 자동 생성 — "${data.prompt}"`,
        style: data.templateStyle,
        structure: data.templateStructure,
      });
      const templateId = (template.data as { id: number }).id;
      // 2. Create smart job
      const jobPayload: Record<string, unknown> = {
        name: data.name,
        prompt: data.prompt,
        templateId,
        cronExpression: data.cronExpression ?? '0 9 * * *',
        timezone: data.timezone ?? 'Asia/Seoul',
        config: {
          channels: (data.channels ?? ['CHAT']).map((ch) => ({
            type: ch,
            recipientUserIds: [],
            recipientEmails: [],
          })),
        },
      };
      if (!data.cronExpression) jobPayload.enabled = false;
      const job = await client.post('/proactive/jobs', jobPayload);
      return { template: template.data, job: job.data };
    },

    /**
     * 리포트 템플릿 단건 조회
     * @param id 조회할 템플릿 ID
     */
    async getReportTemplate(id: number): Promise<unknown> {
      const response = await client.get(`/proactive/templates/${id}`);
      return response.data;
    },

    /**
     * 리포트 템플릿 수정
     * @param id 수정할 템플릿 ID
     * @param data 수정할 필드 (모두 선택적)
     */
    async updateReportTemplate(
      id: number,
      data: {
        name?: string;
        description?: string;
        style?: string;
        structure?: {
          sections?: Array<{
            key: string;
            label: string;
            required?: boolean;
            type?: string;
          }>;
          output_format?: string;
        };
      },
    ): Promise<unknown> {
      const response = await client.put(`/proactive/templates/${id}`, data);
      return response.data;
    },

    /**
     * 리포트 템플릿 삭제
     * @param id 삭제할 템플릿 ID
     */
    async deleteReportTemplate(id: number): Promise<unknown> {
      await client.delete(`/proactive/templates/${id}`);
      return { success: true };
    },

    /**
     * 스마트 잡 실행 이력 목록 조회
     * @param jobId 잡 ID
     * @param params 페이지네이션 파라미터 (limit, offset)
     */
    async listJobExecutions(
      jobId: number,
      params?: { limit?: number; offset?: number },
    ): Promise<unknown> {
      const response = await client.get(`/proactive/jobs/${jobId}/executions`, { params });
      return response.data;
    },

    /**
     * 스마트 잡 단건 실행 이력 조회
     * @param jobId 잡 ID
     * @param executionId 실행 이력 ID
     */
    async getExecution(jobId: number, executionId: number): Promise<unknown> {
      const response = await client.get(`/proactive/jobs/${jobId}/executions/${executionId}`);
      return response.data;
    },
  };
}
