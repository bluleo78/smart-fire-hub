export interface RecentImportResponse {
  id: number;
  datasetName: string;
  fileName: string;
  status: string;
  createdAt: string;
}

export interface RecentExecutionResponse {
  id: number;
  pipelineName: string;
  status: string;
  createdAt: string;
}

export interface DashboardStatsResponse {
  totalDatasets: number;
  sourceDatasets: number;
  derivedDatasets: number;
  totalPipelines: number;
  activePipelines: number;
  recentImports: RecentImportResponse[];
  recentExecutions: RecentExecutionResponse[];
}
