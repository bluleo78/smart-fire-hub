import type { ExportEstimate, ExportFormat, ExportRequest } from '../types/export';
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

  /**
   * 쿼리 결과 내보내기 — 화면의 rows 를 보내지 않고 실행 기록 id 만 보낸다. 서버가 내보내기 시점 자격으로 다시 판정하고
   * 다시 실행해 파일을 만든다(스펙 §4.4). 남의 id·없는 id·만료는 404 QUERY_RUN_NOT_FOUND.
   */
  exportQueryRun: (runId: string, format: ExportFormat) =>
    client.post(`/analytics/queries/runs/${runId}/export`, { format }, { responseType: 'blob' }),
};
