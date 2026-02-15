import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { datasetsApi, categoriesApi } from '../../api/datasets';
import { dataImportsApi } from '../../api/dataImports';
import type { ImportResponse } from '../../types/dataImport';

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.getCategories().then(r => r.data),
  });
}

export function useDatasets(params: { categoryId?: number; datasetType?: string; search?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['datasets', params],
    queryFn: () => datasetsApi.getDatasets(params).then(r => r.data),
  });
}

export function useDataset(id: number) {
  return useQuery({
    queryKey: ['datasets', id],
    queryFn: () => datasetsApi.getDatasetById(id).then(r => r.data),
    enabled: !!id,
  });
}

export function useDatasetData(datasetId: number, params: { search?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'data', params],
    queryFn: () => datasetsApi.getDatasetData(datasetId, params).then(r => r.data),
    enabled: !!datasetId,
  });
}

export function useCreateDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: datasetsApi.createDataset,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

export function useUpdateDataset(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateDataset>[1]) => datasetsApi.updateDataset(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', id] });
    },
  });
}

export function useDeleteDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: datasetsApi.deleteDataset,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

export function useAddColumn(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.addColumn>[1]) => datasetsApi.addColumn(datasetId, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] }),
  });
}

export function useImports(datasetId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'imports'],
    queryFn: () => dataImportsApi.getImports(datasetId).then(r => r.data),
    enabled: !!datasetId,
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasPending = data?.some((i: ImportResponse) => i.status === 'PENDING' || i.status === 'PROCESSING');
      return hasPending ? 3000 : false;
    },
  });
}

export function useImport(datasetId: number, importId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'imports', importId],
    queryFn: () => dataImportsApi.getImportById(datasetId, importId).then(r => r.data),
    enabled: !!datasetId && !!importId,
  });
}

export function useUploadFile(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => dataImportsApi.uploadFile(datasetId, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'imports'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useCreateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: categoriesApi.createCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}

export function useUpdateCategory(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof categoriesApi.updateCategory>[1]) => categoriesApi.updateCategory(id, data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}

export function useDeleteCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: categoriesApi.deleteCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }),
  });
}
