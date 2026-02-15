export interface ImportResponse {
  id: number;
  datasetId: number;
  fileName: string;
  fileSize: number;
  fileType: 'CSV' | 'XLSX';
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  totalRows: number | null;
  successRows: number | null;
  errorRows: number | null;
  errorDetails: Record<string, string> | null;
  importedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}
