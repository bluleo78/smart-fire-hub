import { useQuery, useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { datasetsApi, categoriesApi } from '../../api/datasets';
import { dataImportsApi } from '../../api/dataImports';
import type { ImportResponse, ImportStartResponse, ColumnMappingEntry, ImportMode } from '../../types/dataImport';
import type { CloneDatasetRequest, ApiImportRequest } from '../../types/dataset';

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.getCategories().then(r => r.data),
  });
}

export function useDatasets(params: { categoryId?: number; datasetType?: string; search?: string; page?: number; size?: number; favoriteOnly?: boolean; status?: string }) {
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

export function useDatasetData(datasetId: number, params: { search?: string; size?: number; sortBy?: string; sortDir?: string }) {
  const { search, size = 50, sortBy, sortDir } = params;
  return useInfiniteQuery({
    queryKey: ['datasets', datasetId, 'data', { search, size, sortBy, sortDir }],
    queryFn: ({ pageParam = 0 }) =>
      datasetsApi.getDatasetData(datasetId, {
        search,
        page: pageParam,
        size,
        sortBy,
        sortDir,
        includeTotalCount: pageParam === 0,
      }).then(r => r.data),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => {
      if (lastPage.totalPages > 0 && lastPage.page < lastPage.totalPages - 1) {
        return lastPage.page + 1;
      }
      // When totalPages is -1 (not included), check if we got a full page
      if (lastPage.totalPages === -1 && lastPage.rows.length >= (size || 50)) {
        return lastPage.page + 1;
      }
      return undefined;
    },
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

export function useUpdateColumn(datasetId: number, columnId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateColumn>[2]) => datasetsApi.updateColumn(datasetId, columnId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useReorderColumns(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (columnIds: number[]) => datasetsApi.reorderColumns(datasetId, columnIds),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] }),
  });
}

export function useDeleteDataRows(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (rowIds: number[]) => datasetsApi.deleteDataRows(datasetId, rowIds).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useColumnStats(datasetId: number, enabled = true) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'stats'],
    queryFn: () => datasetsApi.getDatasetStats(datasetId).then(r => r.data),
    enabled: !!datasetId && enabled,
    staleTime: 5 * 60 * 1000,
  });
}

export function useDeleteColumn(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (columnId: number) => datasetsApi.deleteColumn(datasetId, columnId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
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
  return useMutation<ImportStartResponse, unknown, { file: File; mappings?: ColumnMappingEntry[]; importMode?: ImportMode }>({
    mutationFn: ({ file, mappings, importMode }) =>
      dataImportsApi.uploadFile(datasetId, file, mappings, importMode).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'imports'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function usePreviewImport(datasetId: number) {
  return useMutation({
    mutationFn: (file: File) => dataImportsApi.previewImport(datasetId, file).then(r => r.data),
  });
}

export function useValidateImport(datasetId: number) {
  return useMutation({
    mutationFn: ({ file, mappings }: { file: File; mappings: ColumnMappingEntry[] }) =>
      dataImportsApi.validateImport(datasetId, file, mappings).then(r => r.data),
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

export function useToggleFavorite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => datasetsApi.toggleFavorite(id).then(r => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['datasets'] }),
  });
}

export function useUpdateStatus(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Parameters<typeof datasetsApi.updateStatus>[1]) => datasetsApi.updateStatus(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', id] });
    },
  });
}

export function useAddTag(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tagName: string) => datasetsApi.addTag(datasetId, tagName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}

export function useRemoveTag(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tagName: string) => datasetsApi.removeTag(datasetId, tagName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}

export function useTags() {
  return useQuery({
    queryKey: ['tags'],
    queryFn: () => datasetsApi.getAllTags().then(r => r.data),
  });
}

// Phase 1: SQL Query hooks
export function useExecuteQuery(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sql, maxRows }: { sql: string; maxRows?: number }) =>
      datasetsApi.executeQuery(datasetId, sql, maxRows).then(r => r.data),
    onSuccess: (data) => {
      // Always invalidate query history (all queries are logged)
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'queries'] });
      if (data.queryType !== 'SELECT') {
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      }
    },
  });
}

export function useQueryHistory(datasetId: number, page = 0, size = 20) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'queries', { page, size }],
    queryFn: () => datasetsApi.getQueryHistory(datasetId, page, size).then(r => r.data),
    enabled: !!datasetId,
  });
}

// Phase 2: Manual Row Entry hooks
export function useAddRow(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: Record<string, unknown>) =>
      datasetsApi.addRow(datasetId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

export function useUpdateRow(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ rowId, data }: { rowId: number; data: Record<string, unknown> }) =>
      datasetsApi.updateRow(datasetId, rowId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'data'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    },
  });
}

// Phase 3: API Import hook
export function useCreateApiImport(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: ApiImportRequest) => datasetsApi.createApiImport(datasetId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      queryClient.invalidateQueries({ queryKey: ['pipelines'] });
    },
  });
}

// Phase 3: Clone Dataset hook
export function useCloneDataset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ datasetId, data }: { datasetId: number; data: CloneDatasetRequest }) =>
      datasetsApi.cloneDataset(datasetId, data).then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets'] });
    },
  });
}
