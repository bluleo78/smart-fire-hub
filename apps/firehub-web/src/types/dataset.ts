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
}

export interface DatasetColumnResponse {
  id: number;
  columnName: string;
  displayName: string | null;
  dataType: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
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
  dataType: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
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
  dataType: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
  isNullable: boolean;
  isIndexed: boolean;
  description?: string;
}

export interface UpdateColumnRequest {
  displayName?: string;
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

