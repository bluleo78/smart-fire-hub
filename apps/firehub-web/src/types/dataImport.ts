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
  // 실제로 읽은 시트 이름 — 엑셀에서만 값이 있고 CSV 는 null (WD-253)
  sheetName: string | null;
  // 통합 문서의 모든 시트(문서 순서) — 시트 선택 목록용. CSV 는 null (WD-253)
  sheets: ImportSheetInfo[] | null;
}

/** 엑셀 통합 문서의 시트 한 장 — 숨김 시트도 목록에 오며 hidden 으로 구분한다 (WD-253) */
export interface ImportSheetInfo {
  name: string;
  hidden: boolean;
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
