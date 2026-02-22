import type { AxiosInstance } from 'axios';

export function createDataApi(client: AxiosInstance) {
  return {
    async executeQuery(datasetId: number, sql: string, maxRows?: number): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/query`, { sql, maxRows });
      return response.data;
    },
    async addRow(datasetId: number, data: Record<string, unknown>): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/data/rows`, { data });
      return response.data;
    },
    async addRowsBatch(datasetId: number, rows: Record<string, unknown>[]): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/data/rows/batch`, { rows });
      return response.data;
    },
    async updateRow(
      datasetId: number,
      rowId: number,
      data: Record<string, unknown>,
    ): Promise<unknown> {
      await client.put(`/datasets/${datasetId}/data/rows/${rowId}`, { data });
      return { success: true };
    },
    async deleteRows(datasetId: number, rowIds: number[]): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/data/delete`, { rowIds });
      return response.data;
    },
    async truncateDataset(datasetId: number): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/data/truncate`);
      return response.data;
    },
    async getRowCount(datasetId: number): Promise<unknown> {
      const response = await client.get(`/datasets/${datasetId}/data/count`);
      return response.data;
    },
    async replaceDatasetData(datasetId: number, rows: Record<string, unknown>[]): Promise<unknown> {
      const response = await client.post(`/datasets/${datasetId}/data/replace`, { rows });
      return response.data;
    },
  };
}
