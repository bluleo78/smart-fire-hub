import { client } from './client';
import type {
  CategoryResponse, CategoryRequest,
  DatasetResponse, DatasetDetailResponse,
  CreateDatasetRequest, UpdateDatasetRequest,
  DatasetColumnResponse, AddColumnRequest, UpdateColumnRequest,
  DataQueryResponse, DataDeleteResponse, ColumnStatsResponse,
  FavoriteToggleResponse, UpdateStatusRequest,
} from '../types/dataset';
import type { PageResponse } from '../types/common';

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
};
