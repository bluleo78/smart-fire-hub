import type { ColumnMappingEntry,ImportPreviewResponse, ImportResponse, ImportStartResponse, ImportValidateResponse } from '../types/dataImport';
import { client } from './client';

export const dataImportsApi = {
  uploadFile: (datasetId: number, file: File, mappings?: ColumnMappingEntry[], importMode?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    if (mappings) {
      formData.append('mappings', JSON.stringify(mappings));
    }
    if (importMode) {
      formData.append('importMode', importMode);
    }
    return client.post<ImportStartResponse>(`/datasets/${datasetId}/imports`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  previewImport: (datasetId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post<ImportPreviewResponse>(`/datasets/${datasetId}/imports/preview`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  validateImport: (datasetId: number, file: File, mappings: ColumnMappingEntry[]) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('mappings', JSON.stringify(mappings));
    return client.post<ImportValidateResponse>(`/datasets/${datasetId}/imports/validate`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  getImports: (datasetId: number) =>
    client.get<ImportResponse[]>(`/datasets/${datasetId}/imports`),
  getImportById: (datasetId: number, importId: number) =>
    client.get<ImportResponse>(`/datasets/${datasetId}/imports/${importId}`),
  exportCsv: (datasetId: number) =>
    client.get(`/datasets/${datasetId}/data/export`, { responseType: 'blob' }),
};
