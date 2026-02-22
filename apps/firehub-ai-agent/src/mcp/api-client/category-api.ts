import type { AxiosInstance } from 'axios';

export function createCategoryApi(client: AxiosInstance) {
  return {
    async listCategories(): Promise<unknown> {
      const response = await client.get('/dataset-categories');
      return response.data;
    },
    async createCategory(data: { name: string; description?: string }): Promise<unknown> {
      const response = await client.post('/dataset-categories', data);
      return response.data;
    },
    async updateCategory(
      id: number,
      data: { name: string; description?: string },
    ): Promise<unknown> {
      const response = await client.put(`/dataset-categories/${id}`, data);
      return response.data;
    },
  };
}
