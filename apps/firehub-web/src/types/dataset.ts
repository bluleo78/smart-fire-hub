import type { SecurityLevelSummary } from './security-level';

export interface CategoryResponse {
  id: number;
  name: string;
  description: string | null;
}

export interface DatasetResponse {
  id: number;
  name: string;
  tableName: string;
  description: string | null;
  category: CategoryResponse | null;
  storageType: 'TABLE' | 'DOCUMENT' | 'FILE';
  originType: 'SOURCE' | 'DERIVED' | 'TEMP';
  createdAt: string;
  isFavorite: boolean;
  tags: string[];
  status: 'NONE' | 'CERTIFIED' | 'DEPRECATED';
  statusNote: string | null;
  statusUpdatedBy: string | null;
  statusUpdatedAt: string | null;
  /** 보안 등급(S1). 서버가 항상 채우지만 구버전 응답·목 호환을 위해 optional. 생성 응답은 null 가능. */
  securityLevel?: SecurityLevelSummary | null;
  /** 파이프라인 입력 등급에 따른 자동 상향 시각(「보안」 탭 배너). */
  securityLevelAutoRaisedAt?: string | null;
  /**
   * 조회자 기준 내보내기 가능(EXPORT 정책 AND data:export 권한, S4). 웹은 `=== true` 일 때만 다운로드 UI 를 연다 —
   * 없거나 null 이면(구버전 응답·다른 생성 경로) 막는다(fail-closed, 스펙 §5-4).
   */
  exportAllowed?: boolean | null;
}

export interface FavoriteToggleResponse {
  favorited: boolean;
}

export interface UpdateStatusRequest {
  status: string;
  note?: string;
}

export interface TagRequest {
  tagName: string;
}

export interface DatasetColumnResponse {
  id: number;
  columnName: string;
  displayName: string | null;
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP' | 'GEOMETRY';
  maxLength: number | null;
  isNullable: boolean;
  isIndexed: boolean;
  isPrimaryKey: boolean;
  description: string | null;
  columnOrder: number;
}

export interface DatasetDetailResponse {
  id: number;
  name: string;
  tableName: string;
  description: string | null;
  category: CategoryResponse | null;
  storageType: 'TABLE' | 'DOCUMENT' | 'FILE';
  originType: 'SOURCE' | 'DERIVED' | 'TEMP';
  createdBy: string;
  columns: DatasetColumnResponse[];
  // DOCUMENT 데이터셋은 동적 테이블이 없어 백엔드가 null을 반환한다.
  rowCount: number | null;
  createdAt: string;
  updatedAt: string | null;
  updatedBy: string | null;
  isFavorite: boolean;
  tags: string[];
  status: 'NONE' | 'CERTIFIED' | 'DEPRECATED';
  statusNote: string | null;
  statusUpdatedBy: string | null;
  statusUpdatedAt: string | null;
  linkedPipelines: LinkedPipelineInfo[];
  /** 보안 등급(S1). 서버가 항상 채우지만 구버전 응답·목 호환을 위해 optional. 생성 응답은 null 가능. */
  securityLevel?: SecurityLevelSummary | null;
  /** 파이프라인 입력 등급에 따른 자동 상향 시각(「보안」 탭 배너). */
  securityLevelAutoRaisedAt?: string | null;
  /**
   * 조회자 기준 내보내기 가능(EXPORT 정책 AND data:export 권한, S4). 웹은 `=== true` 일 때만 다운로드 UI 를 연다 —
   * 없거나 null 이면(구버전 응답·다른 생성 경로) 막는다(fail-closed, 스펙 §5-4).
   */
  exportAllowed?: boolean | null;
}

export interface CreateDatasetRequest {
  name: string;
  tableName: string;
  description?: string;
  categoryId?: number;
  storageType: 'TABLE' | 'DOCUMENT' | 'FILE';
  originType: 'SOURCE' | 'DERIVED' | 'TEMP';
  columns: DatasetColumnRequest[];
  sourcePipelineStepId?: number;
  // FILE 타입 전용: 오브젝트 스토리지 버킷(미전송 시 백엔드 기본값). 프리픽스는 서버가 datasets/<id>/ 로 만들며 지정할 수 없다.
  bucket?: string;
}

export interface DatasetColumnRequest {
  columnName: string;
  displayName?: string;
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP' | 'GEOMETRY';
  maxLength?: number;
  isNullable: boolean;
  isIndexed: boolean;
  isPrimaryKey: boolean;
  description?: string;
}

export interface UpdateDatasetRequest {
  name: string;
  description?: string;
  categoryId?: number;
}

export interface AddColumnRequest {
  columnName: string;
  displayName?: string;
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP' | 'GEOMETRY';
  maxLength?: number;
  isNullable: boolean;
  isIndexed: boolean;
  isPrimaryKey: boolean;
  description?: string;
}

export interface UpdateColumnRequest {
  columnName?: string;
  displayName?: string;
  dataType?: string;
  maxLength?: number | null;
  isNullable?: boolean;
  isIndexed?: boolean;
  isPrimaryKey?: boolean;
  description?: string;
}

export interface CategoryRequest {
  name: string;
  description?: string;
}

export interface DataQueryResponse {
  columns: DatasetColumnResponse[];
  rows: Record<string, unknown>[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DataDeleteRequest {
  rowIds: number[];
}

export interface DataDeleteResponse {
  deletedCount: number;
}

// Phase 1: SQL Query
export interface SqlQueryRequest {
  sql: string;
  maxRows?: number;
}

export interface SqlQueryResponse {
  queryType: 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'OTHER';
  columns: string[];
  rows: Record<string, unknown>[];
  affectedRows: number;
  executionTimeMs: number;
  error: string | null;
}

export interface QueryHistoryResponse {
  id: number;
  sql: string;
  queryType: string;
  affectedRows: number;
  executionTimeMs: number;
  success: boolean;
  error: string | null;
  executedAt: string;
}

// Phase 2: Manual Row Entry
export interface RowDataRequest {
  data: Record<string, unknown>;
}

export interface RowDataResponse {
  id: number;
  data: Record<string, unknown>;
  createdAt: string;
}

// Phase 3: Clone Dataset
export interface CloneDatasetRequest {
  name: string;
  tableName: string;
  description?: string;
  includeData: boolean;
  includeTags: boolean;
}

export interface ColumnStatsValueCount {
  value: string;
  count: number;
}

export interface ColumnStatsResponse {
  columnName: string;
  dataType: string;
  totalCount: number;
  nullCount: number;
  nullPercent: number;
  distinctCount: number;
  minValue: string | null;
  maxValue: string | null;
  avgValue: number | null;
  topValues: ColumnStatsValueCount[];
  sampled: boolean;
}

// Phase 3: API Import
export interface LinkedPipelineInfo {
  id: number;
  name: string;
  isActive: boolean;
}

export interface ApiImportRequest {
  pipelineName?: string;
  pipelineDescription?: string;
  apiConfig: Record<string, unknown>;
  apiConnectionId?: number | null;
  loadStrategy?: string;
  executeImmediately: boolean;
  schedule?: {
    cronExpression: string;
    name?: string;
    description?: string;
  } | null;
}

export interface ApiImportResponse {
  pipelineId: number;
  executionId: number | null;
  triggerId: number | null;
}

// Phase 1-3: GeoJSON types (frontend conversion)
export interface GeoJsonFeature {
  type: 'Feature';
  geometry: Record<string, unknown>;
  properties: Record<string, unknown>;
}

export interface GeoJsonFeatureCollection {
  type: 'FeatureCollection';
  features: GeoJsonFeature[];
}

/** 데이터셋 행 검색 색인 상태(검색 탭). status: OFF | SYNCING | IDLE | ERROR */
export interface SearchIndexStatus {
  enabled: boolean;
  fields: string[];
  status: 'OFF' | 'SYNCING' | 'IDLE' | 'ERROR';
  indexedRows: number;
  totalRows: number;
  lastSyncedAt: string | null;
  lastError: string | null;
  embeddingModel: string | null;
}


/** 데이터셋을 쓰는 곳 한 건 — 파이프라인·대시보드·스마트 작업 공통(서버 DatasetReferencesResponse.ReferenceItem). */
export interface DatasetReferenceItem {
  id: number;
  name: string;
}

/** 이 데이터셋을 감시하는 DATASET_CHANGE 트리거 — 어느 파이프라인의 트리거인지 함께 준다. */
export interface DatasetTriggerReferenceItem {
  id: number;
  name: string;
  pipelineId: number;
  pipelineName: string;
}

/**
 * 데이터셋 참조 집계(GET /datasets/{id}/references, WD-143) — 삭제 확인 창이 "이 데이터셋을 쓰는 곳"을 보여 주는 데 쓴다.
 * pipelines 는 출력·입력(pipeline_step_input) 참조를 모두 포함한다(같은 파이프라인은 한 번).
 * dashboards 는 조회자가 볼 수 있는 것(본인 소유 또는 공유)만 이름이 오고, 볼 수 없는 것은 hiddenDashboardCount 로 개수만 온다.
 * totalCount 는 볼 수 없는 대시보드까지 센다.
 */
export interface DatasetReferencesResponse {
  datasetId: number;
  pipelines: DatasetReferenceItem[];
  dashboards: DatasetReferenceItem[];
  hiddenDashboardCount: number;
  proactiveJobs: DatasetReferenceItem[];
  triggers: DatasetTriggerReferenceItem[];
  totalCount: number;
}

/**
 * 데이터 저장소 기능 지원 여부 (GET /data-store/capabilities).
 * Tibero 처럼 일부 기능을 지원하지 않는 저장소일 때 화면이 해당 진입점을 숨기는 근거다. (WD-80)
 */
export interface DataStoreCapabilities {
  /** 저장소 백엔드 — api 가 DataStoreBackend 이름을 그대로 내려주므로 아직 구현이 없는 MYSQL 도 올 수 있다. */
  backend: 'POSTGRESQL' | 'TIBERO' | 'MYSQL';
  /** GEOMETRY 컬럼 타입 지원 여부 */
  geometry: boolean;
  /** 데이터셋 행 검색(검색 탭) 지원 여부 */
  rowSearch: boolean;
}
