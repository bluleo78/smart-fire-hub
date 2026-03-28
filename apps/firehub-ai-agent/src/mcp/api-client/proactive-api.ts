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
  };
}
