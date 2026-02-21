export type ImportMode = 'APPEND' | 'UPSERT' | 'REPLACE';

export interface ImportStartResponse {
  jobId: string;
  status: string;
}

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

export interface ImportPreviewResponse {
  fileHeaders: string[];
  sampleRows: Record<string, string>[];
  suggestedMappings: ColumnMappingDto[];
  totalRows: number;
}

export interface ColumnMappingDto {
  fileColumn: string;
  datasetColumn: string | null;
  matchType: 'EXACT' | 'CASE_INSENSITIVE' | 'DISPLAY_NAME' | 'NORMALIZED' | 'NONE';
  confidence: number;
}

export interface ColumnMappingEntry {
  fileColumn: string;
  datasetColumn: string | null;
}

export interface ImportValidateResponse {
  totalRows: number;
  validRows: number;
  errorRows: number;
  errors: ValidationErrorDetail[];
}

export interface ValidationErrorDetail {
  rowNumber: number;
  columnName: string;
  value: string;
  error: string;
}
