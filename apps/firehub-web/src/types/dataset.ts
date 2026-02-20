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
  datasetType: 'SOURCE' | 'DERIVED';
  createdAt: string;
  isFavorite: boolean;
  tags: string[];
  status: 'NONE' | 'CERTIFIED' | 'DEPRECATED';
  statusNote: string | null;
  statusUpdatedBy: string | null;
  statusUpdatedAt: string | null;
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
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
  maxLength: number | null;
  isNullable: boolean;
  isIndexed: boolean;
  description: string | null;
  columnOrder: number;
}

export interface DatasetDetailResponse {
  id: number;
  name: string;
  tableName: string;
  description: string | null;
  category: CategoryResponse | null;
  datasetType: 'SOURCE' | 'DERIVED';
  createdBy: string;
  columns: DatasetColumnResponse[];
  rowCount: number;
  createdAt: string;
  updatedAt: string | null;
  updatedBy: string | null;
  isFavorite: boolean;
  tags: string[];
  status: 'NONE' | 'CERTIFIED' | 'DEPRECATED';
  statusNote: string | null;
  statusUpdatedBy: string | null;
  statusUpdatedAt: string | null;
}

export interface CreateDatasetRequest {
  name: string;
  tableName: string;
  description?: string;
  categoryId?: number;
  datasetType: 'SOURCE' | 'DERIVED';
  columns: DatasetColumnRequest[];
}

export interface DatasetColumnRequest {
  columnName: string;
  displayName?: string;
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
  maxLength?: number;
  isNullable: boolean;
  isIndexed: boolean;
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
  dataType: 'TEXT' | 'VARCHAR' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
  maxLength?: number;
  isNullable: boolean;
  isIndexed: boolean;
  description?: string;
}

export interface UpdateColumnRequest {
  columnName?: string;
  displayName?: string;
  dataType?: string;
  maxLength?: number | null;
  isNullable?: boolean;
  isIndexed?: boolean;
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

