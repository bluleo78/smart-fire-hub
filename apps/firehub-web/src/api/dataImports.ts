import { client } from './client';
import type { ImportResponse } from '../types/dataImport';

export const dataImportsApi = {
  uploadFile: (datasetId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post<ImportResponse>(`/datasets/${datasetId}/imports`, formData, {
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
