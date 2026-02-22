import type { AxiosInstance } from 'axios';

export function createMiscApi(client: AxiosInstance) {
  return {
    async listImports(datasetId: number): Promise<unknown> {
      const response = await client.get(`/datasets/${datasetId}/imports`);
      return response.data;
    },
    async getDashboard(): Promise<unknown> {
      const response = await client.get('/dashboard/stats');
      return response.data;
    },
  };
}
