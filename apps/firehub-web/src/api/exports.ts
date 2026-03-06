import type {
  ExportEstimate,
  ExportRequest,
  QueryResultExportRequest,
} from '../types/export';
import { client } from './client';

export const exportsApi = {
  estimateExport: (datasetId: number, search?: string) =>
    client.get<ExportEstimate>(`/datasets/${datasetId}/export/estimate`, {
      params: { search },
    }),

  exportDataset: (datasetId: number, request: ExportRequest) =>
    client.post(`/datasets/${datasetId}/export`, request, {
      responseType: 'blob',
    }),

  exportDatasetAsync: (datasetId: number, request: ExportRequest) =>
    client.post<{ jobId: string }>(`/datasets/${datasetId}/export`, request),

  downloadExportFile: (jobId: string) =>
    client.get(`/exports/${jobId}/file`, { responseType: 'blob' }),

  exportQueryResult: (request: QueryResultExportRequest) =>
    client.post('/query-results/export', request, { responseType: 'blob' }),
};
