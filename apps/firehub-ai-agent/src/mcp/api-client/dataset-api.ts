import type { AxiosInstance } from 'axios';

export function createDatasetApi(client: AxiosInstance) {
  return {
    async listDatasets(params?: {
      categoryId?: number;
      datasetType?: string;
      search?: string;
      status?: string;
      favoriteOnly?: boolean;
      page?: number;
      size?: number;
    }): Promise<unknown> {
      const response = await client.get('/datasets', { params });
      return response.data;
    },
    async getDataset(id: number): Promise<unknown> {
      const response = await client.get(`/datasets/${id}`);
      return response.data;
    },
    async queryDatasetData(
      id: number,
      params?: {
        search?: string;
        sortBy?: string;
        sortDir?: string;
        includeTotalCount?: boolean;
        page?: number;
        size?: number;
      },
    ): Promise<unknown> {
      const response = await client.get(`/datasets/${id}/data`, { params });
      return response.data;
    },
    async createDataset(data: {
      name: string;
      tableName: string;
      description?: string;
      categoryId?: number;
      datasetType?: string;
      columns: Array<{
        columnName: string;
        displayName: string;
        dataType: string;
        maxLength?: number;
        isNullable?: boolean;
        isIndexed?: boolean;
        isPrimaryKey?: boolean;
        description?: string;
      }>;
    }): Promise<unknown> {
      const response = await client.post('/datasets', data);
      return response.data;
    },
    async updateDataset(
      id: number,
      data: {
        name?: string;
        description?: string;
        categoryId?: number;
      },
    ): Promise<unknown> {
      const response = await client.put(`/datasets/${id}`, data);
      return response.data;
    },
  };
}
