import type { PageResponse } from '../types/common';
import type {
AddColumnRequest,   ApiImportRequest, ApiImportResponse,
CategoryRequest,
  CategoryResponse, CloneDatasetRequest,
ColumnStatsResponse,
  CreateDatasetRequest, DataDeleteResponse,   DataQueryResponse,   DatasetColumnResponse, DatasetDetailResponse,
  DatasetResponse,   FavoriteToggleResponse, QueryHistoryResponse,
  RowDataResponse,   SqlQueryResponse, UpdateColumnRequest,
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

export const datasetsApi = {
  getDatasets: (params: { categoryId?: number; datasetType?: string; search?: string; page?: number; size?: number; favoriteOnly?: boolean; status?: string }) =>
    client.get<PageResponse<DatasetResponse>>('/datasets', { params }),
  getDatasetById: (id: number) => client.get<DatasetDetailResponse>(`/datasets/${id}`),
  createDataset: (data: CreateDatasetRequest) => client.post<DatasetDetailResponse>('/datasets', data),
  updateDataset: (id: number, data: UpdateDatasetRequest) => client.put(`/datasets/${id}`, data),
  deleteDataset: (id: number) => client.delete(`/datasets/${id}`),
  addColumn: (datasetId: number, data: AddColumnRequest) =>
    client.post<DatasetColumnResponse>(`/datasets/${datasetId}/columns`, data),
  updateColumn: (datasetId: number, colId: number, data: UpdateColumnRequest) =>
    client.put(`/datasets/${datasetId}/columns/${colId}`, data),
  deleteColumn: (datasetId: number, columnId: number) =>
    client.delete(`/datasets/${datasetId}/columns/${columnId}`),
  reorderColumns: (datasetId: number, columnIds: number[]) =>
    client.put(`/datasets/${datasetId}/columns/reorder`, { columnIds }),
  getDatasetData: (datasetId: number, params: { search?: string; page?: number; size?: number; sortBy?: string; sortDir?: string; includeTotalCount?: boolean }) =>
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
};
