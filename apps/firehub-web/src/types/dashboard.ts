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
    /** 최근 7일간 일자별 파이프라인 실행 건수 (과거→오늘 순, 홈 대시보드 스파크라인용, #669) */
    trend: number[];
  };
  datasetHealth: {
    total: number;
    fresh: number;
    stale: number;
    empty: number;
    /** 최근 7일간 일자별 데이터셋 임포트/변경 건수 (과거→오늘 순, 홈 대시보드 스파크라인용, #669) */
    trend: number[];
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
