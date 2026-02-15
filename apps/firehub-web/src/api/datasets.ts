import { client } from './client';
import type {
  CategoryResponse, CategoryRequest,
  DatasetResponse, DatasetDetailResponse,
  CreateDatasetRequest, UpdateDatasetRequest,
  DatasetColumnResponse, AddColumnRequest, UpdateColumnRequest,
  DataQueryResponse,
} from '../types/dataset';
import type { PageResponse } from '../types/common';

export const categoriesApi = {
  getCategories: () => client.get<CategoryResponse[]>('/dataset-categories'),
  createCategory: (data: CategoryRequest) => client.post<CategoryResponse>('/dataset-categories', data),
  updateCategory: (id: number, data: CategoryRequest) => client.put(`/dataset-categories/${id}`, data),
  deleteCategory: (id: number) => client.delete(`/dataset-categories/${id}`),
};

export const datasetsApi = {
  getDatasets: (params: { categoryId?: number; datasetType?: string; search?: string; page?: number; size?: number }) =>
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
  getDatasetData: (datasetId: number, params: { search?: string; page?: number; size?: number }) =>
    client.get<DataQueryResponse>(`/datasets/${datasetId}/data`, { params }),
};
