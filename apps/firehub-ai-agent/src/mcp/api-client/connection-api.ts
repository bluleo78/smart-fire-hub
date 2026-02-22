import type { AxiosInstance } from 'axios';

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
    async createApiConnection(data: {
      name: string;
      description?: string;
      authType: string;
      authConfig: Record<string, string>;
    }): Promise<unknown> {
      const response = await client.post('/api-connections', data);
      return response.data;
    },
    async updateApiConnection(
      id: number,
      data: {
        name?: string;
        description?: string;
        authType?: string;
        authConfig?: Record<string, string>;
      },
    ): Promise<unknown> {
      const response = await client.put(`/api-connections/${id}`, data);
      return response.data;
    },
    async deleteApiConnection(id: number): Promise<unknown> {
      await client.delete(`/api-connections/${id}`);
      return { success: true };
    },
  };
}
