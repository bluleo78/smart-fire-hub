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
  // 백엔드는 { errors: ValidationErrorDetail[] } 를 JSON 문자열로 이중 인코딩해 내려준다 —
  // parseErrorDetails() 로 안전 파싱한다 (src/lib/errorDetails.ts).
  errorDetails: string | Record<string, unknown> | null;
  errorMessage: string | null;
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
  sampleSize: number; // 검사한 샘플 행 수 (= min(200, 파일 행수))
  validRows: number;
  errorRows: number; // 오류 상세(errors) 건수 — 실패 컬럼 단위이며 실패 행 수와 다를 수 있음
  sampled: boolean; // 항상 true — 전량이 아닌 샘플 검사임을 표기
  errors: ValidationErrorDetail[];
}

export interface ValidationErrorDetail {
  rowNumber: number;
  columnName: string;
  value: string;
  error: string;
}
