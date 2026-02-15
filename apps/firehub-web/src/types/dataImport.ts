export interface ImportResponse {
  id: number | null;
  datasetId: number | null;
  fileName: string;
  fileSize: number | null;
  fileType: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  totalRows: number | null;
  successRows: number | null;
  errorRows: number | null;
  errorDetails: Record<string, unknown> | null;
  importedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}
