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

export interface SystemHealthResponse {
  pipelineHealth: {
    total: number;
    healthy: number;
    failing: number;
    running: number;
    disabled: number;
  };
  datasetHealth: {
    total: number;
    fresh: number;
    stale: number;
    empty: number;
  };
}

export interface AttentionItemResponse {
  type: string;
  severity: 'CRITICAL' | 'WARNING';
  title: string;
  description: string;
  entityId: number;
  entityType: 'PIPELINE' | 'DATASET';
  occurredAt: string;
}

export interface ActivityItem {
  id: number;
  eventType: string;
  title: string;
  description: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  entityType: string;
  entityId: number;
  occurredAt: string;
  isResolved: boolean;
}

export interface ActivityFeedResponse {
  items: ActivityItem[];
  totalCount: number;
  hasMore: boolean;
}

export interface ActivityFeedParams {
  type?: string;
  severity?: string;
  page?: number;
  size?: number;
}
