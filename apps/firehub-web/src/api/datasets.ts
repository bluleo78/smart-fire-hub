import type { PageResponse } from '../types/common';
import type {
AddColumnRequest,   ApiImportRequest, ApiImportResponse,
CategoryRequest,
  CategoryResponse, CloneDatasetRequest,
ColumnStatsResponse,
  CreateDatasetRequest, DataDeleteResponse,   DataQueryResponse,   DatasetColumnResponse, DatasetDetailResponse,
  DatasetReferencesResponse,
  DatasetResponse,   FavoriteToggleResponse, QueryHistoryResponse,
  RowDataResponse,   SearchIndexStatus, SqlQueryResponse, UpdateColumnRequest,
UpdateDatasetRequest,
UpdateStatusRequest,
} from '../types/dataset';
import { client } from './client';

export const categoriesApi = {
  getCategories: () => client.get<CategoryResponse[]>('/dataset-categories'),
  createCategory: (data: CategoryRequest) => client.post<CategoryResponse>('/dataset-categories', data),
  updateCategory: (id: number, data: CategoryRequest) => client.put(`/dataset-categories/${id}`, data),
  deleteCategory: (id: number) => client.delete(`/dataset-categories/${id}`),
};

/** 참조 조회 시간 제한 — 넘으면 조회 실패로 보고 안내만 남긴 채 삭제를 허용한다(조회가 삭제를 무한정 막지 않게, WD-143). */
export const DATASET_REFERENCES_TIMEOUT_MS = 10_000;

export const datasetsApi = {
  getDatasets: (params: { categoryId?: number; storageType?: string; originType?: string; search?: string; page?: number; size?: number; favoriteOnly?: boolean; status?: string; securityLevelId?: number }) =>
    client.get<PageResponse<DatasetResponse>>('/datasets', { params }),
  getDatasetById: (id: number) => client.get<DatasetDetailResponse>(`/datasets/${id}`),
  createDataset: (data: CreateDatasetRequest) => client.post<DatasetDetailResponse>('/datasets', data),
  updateDataset: (id: number, data: UpdateDatasetRequest) => client.put(`/datasets/${id}`, data),
  deleteDataset: (id: number) => client.delete(`/datasets/${id}`),
  /** 이 데이터셋을 쓰는 파이프라인(입력·출력)·대시보드·스마트 작업·트리거(WD-143) — 삭제 확인 창이 열릴 때만 부른다. */
  getReferences: (id: number, signal?: AbortSignal) =>
    client.get<DatasetReferencesResponse>(`/datasets/${id}/references`, {
      signal,
      timeout: DATASET_REFERENCES_TIMEOUT_MS,
    }),
  addColumn: (datasetId: number, data: AddColumnRequest) =>
    client.post<DatasetColumnResponse>(`/datasets/${datasetId}/columns`, data),
  updateColumn: (datasetId: number, colId: number, data: UpdateColumnRequest) =>
    client.put(`/datasets/${datasetId}/columns/${colId}`, data),
  deleteColumn: (datasetId: number, columnId: number) =>
    client.delete(`/datasets/${datasetId}/columns/${columnId}`),
  reorderColumns: (datasetId: number, columnIds: number[]) =>
    client.put(`/datasets/${datasetId}/columns/reorder`, { columnIds }),
  // 데이터셋의 기본 키 컬럼 집합을 한 번에 갱신 (#117). 복합 PK 변경에 사용.
  updatePrimaryKeys: (datasetId: number, columnIds: number[]) =>
    client.put(`/datasets/${datasetId}/primary-keys`, { columnIds }),
  getDatasetData: (datasetId: number, params: {
    search?: string; page?: number; size?: number;
    sortBy?: string; sortDir?: string; includeTotalCount?: boolean;
    // Phase 1-2: spatial filter (optional)
    spatialColumn?: string;
    nearbyLon?: number; nearbyLat?: number; nearbyRadius?: number;
    bboxMinLon?: number; bboxMinLat?: number; bboxMaxLon?: number; bboxMaxLat?: number;
  }) =>
    client.get<DataQueryResponse>(`/datasets/${datasetId}/data`, { params }),
  deleteDataRows: (datasetId: number, rowIds: number[]) =>
    client.post<DataDeleteResponse>(`/datasets/${datasetId}/data/delete`, { rowIds }),
  getDatasetStats: (datasetId: number) =>
    client.get<ColumnStatsResponse[]>(`/datasets/${datasetId}/stats`),
  toggleFavorite: (id: number) =>
    client.post<FavoriteToggleResponse>(`/datasets/${id}/favorite`),
  updateStatus: (id: number, data: UpdateStatusRequest) =>
    client.put(`/datasets/${id}/status`, data),
  addTag: (id: number, tagName: string) =>
    client.post(`/datasets/${id}/tags`, { tagName }),
  removeTag: (id: number, tagName: string) =>
    client.delete(`/datasets/${id}/tags/${tagName}`),
  getAllTags: () =>
    client.get<string[]>('/datasets/tags'),
  // Phase 1: SQL Query
  executeQuery: (datasetId: number, sql: string, maxRows?: number) =>
    client.post<SqlQueryResponse>(`/datasets/${datasetId}/query`, { sql, maxRows: maxRows ?? 1000 }),
  getQueryHistory: (datasetId: number, page = 0, size = 20) =>
    client.get<PageResponse<QueryHistoryResponse>>(`/datasets/${datasetId}/queries`, { params: { page, size } }),
  // Phase 2: Manual Row Entry
  addRow: (datasetId: number, data: Record<string, unknown>) =>
    client.post<RowDataResponse>(`/datasets/${datasetId}/data/rows`, { data }),
  updateRow: (datasetId: number, rowId: number, data: Record<string, unknown>) =>
    client.put(`/datasets/${datasetId}/data/rows/${rowId}`, { data }),
  getRow: (datasetId: number, rowId: number) =>
    client.get<RowDataResponse>(`/datasets/${datasetId}/data/rows/${rowId}`),
  // Phase 3: Clone Dataset
  cloneDataset: (datasetId: number, data: CloneDatasetRequest) =>
    client.post<DatasetDetailResponse>(`/datasets/${datasetId}/clone`, data),
  // Phase 3: API Import
  createApiImport: (datasetId: number, data: ApiImportRequest) =>
    client.post<ApiImportResponse>(`/datasets/${datasetId}/api-import`, data),
  // 행 검색: 검색 대상 필드·색인 상태
  getSearchIndex: (datasetId: number) =>
    client.get<SearchIndexStatus>(`/datasets/${datasetId}/search-index`),
  updateSearchIndex: (datasetId: number, fields: string[]) =>
    client.put<SearchIndexStatus>(`/datasets/${datasetId}/search-index`, { fields }),
  reindexSearchIndex: (datasetId: number) => client.post(`/datasets/${datasetId}/search-index/reindex`),
};
