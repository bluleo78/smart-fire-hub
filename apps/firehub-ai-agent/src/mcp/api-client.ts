import axios, { AxiosInstance, AxiosError } from 'axios';
import { API_ERROR_PREFIX } from '../constants.js';
import { createCategoryApi } from './api-client/category-api.js';
import {
  createDatasetApi,
  type DatasetColumnInput,
  type DatasetColumnResponse,
  type DatasetReferences,
} from './api-client/dataset-api.js';
import { createDataApi } from './api-client/data-api.js';
import { createPipelineApi } from './api-client/pipeline-api.js';
import { createTriggerApi } from './api-client/trigger-api.js';
import { createConnectionApi } from './api-client/connection-api.js';
import { createMiscApi } from './api-client/misc-api.js';
import { createProactiveApi } from './api-client/proactive-api.js';
import {
  createAnalyticsApi,
  type CreateSavedQueryParams,
  type CreateChartParams,
  type AnalyticsQueryResult,
  type SavedQuery,
  type SavedQueryList,
  type Chart,
  type ChartList,
  type ChartData,
  type SchemaInfo,
  type CreateDashboardParams,
  type Dashboard,
  type DashboardList,
  type AddDashboardWidgetParams,
  type DashboardWidget,
} from './api-client/analytics-api.js';

export class FireHubApiClient {
  private client: AxiosInstance;
  private _categories: ReturnType<typeof createCategoryApi>;
  private _datasets: ReturnType<typeof createDatasetApi>;
  private _data: ReturnType<typeof createDataApi>;
  private _pipelines: ReturnType<typeof createPipelineApi>;
  private _triggers: ReturnType<typeof createTriggerApi>;
  private _connections: ReturnType<typeof createConnectionApi>;
  private _misc: ReturnType<typeof createMiscApi>;
  private _analytics: ReturnType<typeof createAnalyticsApi>;
  private _proactive: ReturnType<typeof createProactiveApi>;

  constructor(baseURL: string, internalToken: string, userId: number) {
    this.client = axios.create({
      baseURL,
      headers: {
        Authorization: `Internal ${internalToken}`,
        'X-On-Behalf-Of': String(userId),
        'Content-Type': 'application/json',
      },
    });

    // Request/Response logging & error extraction
    this.client.interceptors.request.use((config) => {
      console.log(
        `[MCP API] ${config.method?.toUpperCase()} ${config.baseURL}${config.url}`,
        config.params ? `params=${JSON.stringify(config.params)}` : '',
      );
      return config;
    });

    this.client.interceptors.response.use(
      (response) => {
        console.log(
          `[MCP API] ${response.status} ${response.config.method?.toUpperCase()} ${response.config.url}`,
        );
        return response;
      },
      (error: AxiosError) => {
        const status = error.response?.status;
        const data = error.response?.data as Record<string, unknown> | undefined;
        const details = data?.details || data?.errors;
        const detailStr = details ? ` — ${JSON.stringify(details)}` : '';
        const apiMessage = `${data?.message || data?.error || error.message}${detailStr}`;
        console.error(
          `[MCP API] ${status || 'NETWORK_ERROR'} ${error.config?.method?.toUpperCase()} ${error.config?.url} - ${apiMessage}`,
        );
        throw new Error(`${API_ERROR_PREFIX} (${status}): ${apiMessage}`);
      },
    );

    this._categories = createCategoryApi(this.client);
    this._datasets = createDatasetApi(this.client);
    this._data = createDataApi(this.client);
    this._pipelines = createPipelineApi(this.client);
    this._triggers = createTriggerApi(this.client);
    this._connections = createConnectionApi(this.client);
    this._misc = createMiscApi(this.client);
    this._analytics = createAnalyticsApi(this.client);
    this._proactive = createProactiveApi(this.client);
  }

  listCategories() {
    return this._categories.listCategories();
  }
  createCategory(data: { name: string; description?: string }) {
    return this._categories.createCategory(data);
  }
  updateCategory(id: number, data: { name: string; description?: string }) {
    return this._categories.updateCategory(id, data);
  }

  listDatasets(params?: {
    categoryId?: number;
    datasetType?: string;
    search?: string;
    status?: string;
    favoriteOnly?: boolean;
    page?: number;
    size?: number;
  }) {
    return this._datasets.listDatasets(params);
  }
  getDataset(id: number) {
    return this._datasets.getDataset(id);
  }
  queryDatasetData(
    id: number,
    params?: {
      search?: string;
      sortBy?: string;
      sortDir?: string;
      includeTotalCount?: boolean;
      page?: number;
      size?: number;
    },
  ) {
    return this._datasets.queryDatasetData(id, params);
  }
  createDataset(data: {
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
  }) {
    return this._datasets.createDataset(data);
  }
  updateDataset(
    id: number,
    data: {
      name?: string;
      description?: string;
      categoryId?: number;
    },
  ) {
    return this._datasets.updateDataset(id, data);
  }
  /** 데이터셋 삭제. data 스키마의 물리 테이블도 함께 DROP된다. */
  deleteDataset(id: number) {
    return this._datasets.deleteDataset(id);
  }
  /** 데이터셋에 컬럼 추가. 물리 테이블에 ALTER TABLE ADD COLUMN이 수행된다. */
  addDatasetColumn(datasetId: number, column: DatasetColumnInput): Promise<DatasetColumnResponse> {
    return this._datasets.addDatasetColumn(datasetId, column);
  }
  /** 데이터셋 컬럼 제거. 물리 테이블에 ALTER TABLE DROP COLUMN이 수행된다 (파괴 작업). */
  dropDatasetColumn(datasetId: number, columnId: number) {
    return this._datasets.dropDatasetColumn(datasetId, columnId);
  }
  /** 데이터셋을 참조하는 파이프라인/대시보드/스마트잡을 조회. 삭제 전 영향 범위 확인용. */
  getDatasetReferences(id: number): Promise<DatasetReferences> {
    return this._datasets.getDatasetReferences(id);
  }

  listPipelines(params?: { page?: number; size?: number }) {
    return this._pipelines.listPipelines(params);
  }
  getPipeline(id: number) {
    return this._pipelines.getPipeline(id);
  }
  createPipeline(data: {
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
      aiConfig?: Record<string, unknown>;
      apiConnectionId?: number;
    }>;
  }) {
    return this._pipelines.createPipeline(data);
  }
  updatePipeline(
    id: number,
    data: {
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
        aiConfig?: Record<string, unknown>;
        apiConnectionId?: number;
      }>;
    },
  ) {
    return this._pipelines.updatePipeline(id, data);
  }
  deletePipeline(id: number) {
    return this._pipelines.deletePipeline(id);
  }
  previewApiCall(data: {
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
  }) {
    return this._pipelines.previewApiCall(data);
  }
  executePipeline(id: number) {
    return this._pipelines.executePipeline(id);
  }
  getExecutionStatus(pipelineId: number, executionId: number) {
    return this._pipelines.getExecutionStatus(pipelineId, executionId);
  }

  listTriggers(pipelineId: number) {
    return this._triggers.listTriggers(pipelineId);
  }
  createTrigger(
    pipelineId: number,
    data: {
      name: string;
      triggerType: string;
      description?: string;
      config: Record<string, unknown>;
    },
  ) {
    return this._triggers.createTrigger(pipelineId, data);
  }
  updateTrigger(
    pipelineId: number,
    triggerId: number,
    data: {
      name?: string;
      isEnabled?: boolean;
      description?: string;
      config?: Record<string, unknown>;
    },
  ) {
    return this._triggers.updateTrigger(pipelineId, triggerId, data);
  }
  deleteTrigger(pipelineId: number, triggerId: number) {
    return this._triggers.deleteTrigger(pipelineId, triggerId);
  }

  listApiConnections() {
    return this._connections.listApiConnections();
  }
  getApiConnection(id: number) {
    return this._connections.getApiConnection(id);
  }
  createApiConnection(data: {
    name: string;
    description?: string;
    authType: string;
    authConfig: Record<string, string>;
  }) {
    return this._connections.createApiConnection(data);
  }
  updateApiConnection(
    id: number,
    data: {
      name?: string;
      description?: string;
      authType?: string;
      authConfig?: Record<string, string>;
    },
  ) {
    return this._connections.updateApiConnection(id, data);
  }
  deleteApiConnection(id: number) {
    return this._connections.deleteApiConnection(id);
  }

  listImports(datasetId: number) {
    return this._misc.listImports(datasetId);
  }

  getDashboard() {
    return this._misc.getDashboard();
  }

  executeQuery(datasetId: number, sql: string, maxRows?: number) {
    return this._data.executeQuery(datasetId, sql, maxRows);
  }
  addRow(datasetId: number, data: Record<string, unknown>) {
    return this._data.addRow(datasetId, data);
  }
  addRowsBatch(datasetId: number, rows: Record<string, unknown>[]) {
    return this._data.addRowsBatch(datasetId, rows);
  }
  updateRow(datasetId: number, rowId: number, data: Record<string, unknown>) {
    return this._data.updateRow(datasetId, rowId, data);
  }
  deleteRows(datasetId: number, rowIds: number[]) {
    return this._data.deleteRows(datasetId, rowIds);
  }
  truncateDataset(datasetId: number) {
    return this._data.truncateDataset(datasetId);
  }
  getRowCount(datasetId: number) {
    return this._data.getRowCount(datasetId);
  }
  replaceDatasetData(datasetId: number, rows: Record<string, unknown>[]) {
    return this._data.replaceDatasetData(datasetId, rows);
  }

  async getFileInfo(fileId: number): Promise<{
    id: number;
    originalName: string;
    mimeType: string;
    fileSize: number;
    fileCategory: string;
  }> {
    const { data } = await this.client.get(`/files/${fileId}`);
    return data;
  }

  async downloadFile(fileId: number): Promise<Buffer> {
    const { data } = await this.client.get(`/files/${fileId}/content`, {
      responseType: 'arraybuffer',
    });
    return Buffer.from(data);
  }

  executeAnalyticsQuery(sql: string, maxRows?: number): Promise<AnalyticsQueryResult> {
    return this._analytics.executeAnalyticsQuery(sql, maxRows);
  }

  createSavedQuery(data: CreateSavedQueryParams): Promise<SavedQuery> {
    return this._analytics.createSavedQuery(data);
  }

  listSavedQueries(params?: { search?: string; folder?: string }): Promise<SavedQueryList> {
    return this._analytics.listSavedQueries(params);
  }

  executeSavedQuery(id: number): Promise<AnalyticsQueryResult> {
    return this._analytics.executeSavedQuery(id);
  }

  getDataSchema(): Promise<SchemaInfo> {
    return this._analytics.getDataSchema();
  }

  createChart(data: CreateChartParams): Promise<Chart> {
    return this._analytics.createChart(data);
  }

  listCharts(params?: { search?: string }): Promise<ChartList> {
    return this._analytics.listCharts(params);
  }

  getChartData(id: number): Promise<ChartData> {
    return this._analytics.getChartData(id);
  }

  createDashboard(data: CreateDashboardParams): Promise<Dashboard> {
    return this._analytics.createDashboard(data);
  }

  listDashboards(params?: { search?: string }): Promise<DashboardList> {
    return this._analytics.listDashboards(params);
  }

  addDashboardWidget(dashboardId: number, data: AddDashboardWidgetParams): Promise<DashboardWidget> {
    return this._analytics.addDashboardWidget(dashboardId, data);
  }

  listSmartJobs() {
    return this._proactive.listSmartJobs();
  }
  createSmartJob(data: {
    name: string;
    prompt: string;
    cronExpression: string;
    timezone?: string;
    templateId?: number;
    channels?: string[];
  }) {
    return this._proactive.createSmartJob(data);
  }
  updateSmartJob(
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
  ) {
    return this._proactive.updateSmartJob(id, data);
  }
  deleteSmartJob(id: number) {
    return this._proactive.deleteSmartJob(id);
  }
  executeSmartJob(id: number) {
    return this._proactive.executeSmartJob(id);
  }
  listReportTemplates() {
    return this._proactive.listReportTemplates();
  }
  createReportTemplate(data: {
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
  }) {
    return this._proactive.createReportTemplate(data);
  }
  createSmartJobWithTemplate(data: {
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
  }) {
    return this._proactive.createSmartJobWithTemplate(data);
  }
  getReportTemplate(id: number) {
    return this._proactive.getReportTemplate(id);
  }
  updateReportTemplate(
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
  ) {
    return this._proactive.updateReportTemplate(id, data);
  }
  deleteReportTemplate(id: number) {
    return this._proactive.deleteReportTemplate(id);
  }
  listJobExecutions(jobId: number, params?: { limit?: number; offset?: number }) {
    return this._proactive.listJobExecutions(jobId, params);
  }
  getExecution(jobId: number, executionId: number) {
    return this._proactive.getExecution(jobId, executionId);
  }
}
