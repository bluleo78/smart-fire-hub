import axios, { AxiosInstance, AxiosError } from 'axios';

export class FireHubApiClient {
  private client: AxiosInstance;

  constructor(baseURL: string, internalToken: string, userId: number) {
    this.client = axios.create({
      baseURL,
      headers: {
        'Authorization': `Internal ${internalToken}`,
        'X-On-Behalf-Of': String(userId),
        'Content-Type': 'application/json'
      }
    });

    // Request/Response logging & error extraction
    this.client.interceptors.request.use((config) => {
      console.log(`[MCP API] ${config.method?.toUpperCase()} ${config.baseURL}${config.url}`, config.params ? `params=${JSON.stringify(config.params)}` : '');
      return config;
    });

    this.client.interceptors.response.use(
      (response) => {
        console.log(`[MCP API] ${response.status} ${response.config.method?.toUpperCase()} ${response.config.url}`);
        return response;
      },
      (error: AxiosError) => {
        const status = error.response?.status;
        const data = error.response?.data as Record<string, unknown> | undefined;
        const apiMessage = data?.message || data?.error || error.message;
        console.error(`[MCP API] ${status || 'NETWORK_ERROR'} ${error.config?.method?.toUpperCase()} ${error.config?.url} - ${apiMessage}`);
        throw new Error(`API 오류 (${status}): ${apiMessage}`);
      }
    );
  }

  // Categories
  async listCategories(): Promise<any> {
    const response = await this.client.get('/dataset-categories');
    return response.data;
  }

  async createCategory(data: { name: string; description?: string }): Promise<any> {
    const response = await this.client.post('/dataset-categories', data);
    return response.data;
  }

  async updateCategory(id: number, data: { name: string; description?: string }): Promise<any> {
    const response = await this.client.put(`/dataset-categories/${id}`, data);
    return response.data;
  }

  // Datasets
  async listDatasets(params?: {
    categoryId?: number;
    datasetType?: string;
    search?: string;
    page?: number;
    size?: number;
  }): Promise<any> {
    const response = await this.client.get('/datasets', { params });
    return response.data;
  }

  async getDataset(id: number): Promise<any> {
    const response = await this.client.get(`/datasets/${id}`);
    return response.data;
  }

  async queryDatasetData(id: number, params?: {
    search?: string;
    page?: number;
    size?: number;
  }): Promise<any> {
    const response = await this.client.get(`/datasets/${id}/data`, { params });
    return response.data;
  }

  async getDatasetColumns(id: number): Promise<any> {
    const response = await this.client.get(`/datasets/${id}/columns`);
    return response.data;
  }

  async createDataset(data: {
    name: string;
    tableName: string;
    description?: string;
    categoryId?: number;
    datasetType?: string;
    columns?: Array<{
      columnName: string;
      displayName: string;
      dataType: string;
      maxLength?: number;
      isNullable?: boolean;
      isIndexed?: boolean;
      description?: string;
    }>;
  }): Promise<any> {
    const response = await this.client.post('/datasets', data);
    return response.data;
  }

  async updateDataset(id: number, data: {
    name?: string;
    description?: string;
    categoryId?: number;
  }): Promise<any> {
    const response = await this.client.put(`/datasets/${id}`, data);
    return response.data;
  }

  // Pipelines
  async listPipelines(params?: {
    search?: string;
    isActive?: boolean;
    page?: number;
    size?: number;
  }): Promise<any> {
    const response = await this.client.get('/pipelines', { params });
    return response.data;
  }

  async getPipeline(id: number): Promise<any> {
    const response = await this.client.get(`/pipelines/${id}`);
    return response.data;
  }

  async executePipeline(id: number): Promise<any> {
    const response = await this.client.post(`/pipelines/${id}/execute`);
    return response.data;
  }

  async getExecutionStatus(id: number): Promise<any> {
    const response = await this.client.get(`/pipelines/executions/${id}`);
    return response.data;
  }

  // Data Imports
  async listImports(params?: {
    datasetId?: number;
    status?: string;
    page?: number;
    size?: number;
  }): Promise<any> {
    const response = await this.client.get('/data-imports', { params });
    return response.data;
  }

  // Dashboard
  async getDashboard(): Promise<any> {
    const response = await this.client.get('/dashboard/stats');
    return response.data;
  }

  // Dataset Data Manipulation
  async executeQuery(datasetId: number, sql: string, maxRows?: number): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/query`, { sql, maxRows });
    return response.data;
  }

  async addRow(datasetId: number, data: Record<string, unknown>): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/data/rows`, { data });
    return response.data;
  }

  async addRowsBatch(datasetId: number, rows: Record<string, unknown>[]): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/data/rows/batch`, { rows });
    return response.data;
  }

  async updateRow(datasetId: number, rowId: number, data: Record<string, unknown>): Promise<any> {
    await this.client.put(`/datasets/${datasetId}/data/rows/${rowId}`, { data });
    return { success: true };
  }

  async deleteRows(datasetId: number, rowIds: number[]): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/data/delete`, { rowIds });
    return response.data;
  }

  async truncateDataset(datasetId: number): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/data/truncate`);
    return response.data;
  }

  async getRowCount(datasetId: number): Promise<any> {
    const response = await this.client.get(`/datasets/${datasetId}/data/count`);
    return response.data;
  }

  async replaceDatasetData(datasetId: number, rows: Record<string, unknown>[]): Promise<any> {
    const response = await this.client.post(`/datasets/${datasetId}/data/replace`, { rows });
    return response.data;
  }
}
