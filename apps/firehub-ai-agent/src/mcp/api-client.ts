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
    status?: string;
    favoriteOnly?: boolean;
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
    sortBy?: string;
    sortDir?: string;
    includeTotalCount?: boolean;
    page?: number;
    size?: number;
  }): Promise<any> {
    const response = await this.client.get(`/datasets/${id}/data`, { params });
    return response.data;
  }

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

  async createPipeline(data: {
    name: string;
    description?: string;
    steps: Array<{
      name: string;
      description?: string;
      scriptType: string;
      scriptContent?: string;
      outputDatasetId?: number;
      inputDatasetIds?: number[];
      dependsOnStepNames?: string[];
      loadStrategy?: string;
      apiConfig?: Record<string, unknown>;
      apiConnectionId?: number;
    }>;
  }): Promise<any> {
    const response = await this.client.post('/pipelines', data);
    return response.data;
  }

  async updatePipeline(id: number, data: {
    name?: string;
    description?: string;
    isActive?: boolean;
    steps?: Array<{
      name: string;
      description?: string;
      scriptType: string;
      scriptContent?: string;
      outputDatasetId?: number;
      inputDatasetIds?: number[];
      dependsOnStepNames?: string[];
      loadStrategy?: string;
      apiConfig?: Record<string, unknown>;
      apiConnectionId?: number;
    }>;
  }): Promise<any> {
    await this.client.put(`/pipelines/${id}`, data);
    return { success: true };
  }

  async deletePipeline(id: number): Promise<any> {
    await this.client.delete(`/pipelines/${id}`);
    return { success: true };
  }

  async previewApiCall(data: {
    url: string;
    method: string;
    headers?: Record<string, string>;
    queryParams?: Record<string, string>;
    body?: string;
    dataPath: string;
    fieldMappings?: Array<{ sourceField: string; targetColumn: string; dataType?: string }>;
    apiConnectionId?: number;
    inlineAuth?: Record<string, string>;
    timeoutMs?: number;
  }): Promise<any> {
    const response = await this.client.post('/pipelines/api-call/preview', data);
    return response.data;
  }

  async executePipeline(id: number): Promise<any> {
    const response = await this.client.post(`/pipelines/${id}/execute`);
    return response.data;
  }

  async getExecutionStatus(pipelineId: number, executionId: number): Promise<any> {
    const response = await this.client.get(`/pipelines/${pipelineId}/executions/${executionId}`);
    return response.data;
  }

  // Triggers
  async listTriggers(pipelineId: number): Promise<any> {
    const response = await this.client.get(`/pipelines/${pipelineId}/triggers`);
    return response.data;
  }

  async createTrigger(pipelineId: number, data: {
    name: string;
    triggerType: string;
    description?: string;
    config: Record<string, unknown>;
  }): Promise<any> {
    const response = await this.client.post(`/pipelines/${pipelineId}/triggers`, data);
    return response.data;
  }

  async updateTrigger(pipelineId: number, triggerId: number, data: {
    name?: string;
    isEnabled?: boolean;
    description?: string;
    config?: Record<string, unknown>;
  }): Promise<any> {
    await this.client.put(`/pipelines/${pipelineId}/triggers/${triggerId}`, data);
    return { success: true };
  }

  async deleteTrigger(pipelineId: number, triggerId: number): Promise<any> {
    await this.client.delete(`/pipelines/${pipelineId}/triggers/${triggerId}`);
    return { success: true };
  }

  // API Connections
  async listApiConnections(): Promise<any> {
    const response = await this.client.get('/api-connections');
    return response.data;
  }

  async getApiConnection(id: number): Promise<any> {
    const response = await this.client.get(`/api-connections/${id}`);
    return response.data;
  }

  async createApiConnection(data: {
    name: string;
    description?: string;
    authType: string;
    authConfig: Record<string, string>;
  }): Promise<any> {
    const response = await this.client.post('/api-connections', data);
    return response.data;
  }

  async updateApiConnection(id: number, data: {
    name?: string;
    description?: string;
    authType?: string;
    authConfig?: Record<string, string>;
  }): Promise<any> {
    const response = await this.client.put(`/api-connections/${id}`, data);
    return response.data;
  }

  async deleteApiConnection(id: number): Promise<any> {
    await this.client.delete(`/api-connections/${id}`);
    return { success: true };
  }

  // Data Imports
  async listImports(datasetId: number): Promise<any> {
    const response = await this.client.get(`/datasets/${datasetId}/imports`);
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
