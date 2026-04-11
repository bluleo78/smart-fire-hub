import type { AxiosInstance, AxiosRequestConfig } from 'axios';

/**
 * 멀티파트 요청 설정. FireHubApiClient 가 기본 Content-Type 을 application/json 으로
 * 고정해 두었기 때문에, FormData 본문을 전송하려면 요청 단위로 Content-Type 을
 * 언세팅해야 한다. undefined 로 설정하면 axios 가 FormData 를 감지해 boundary 가
 * 포함된 multipart/form-data 헤더를 자동 생성한다.
 */
const MULTIPART_REQUEST_CONFIG: AxiosRequestConfig = {
  headers: { 'Content-Type': undefined },
};

/**
 * 백엔드 ParseOptions 미러. CSV/XLSX 파싱 시 구분자/인코딩/헤더 여부 등을
 * 제어한다. 모든 필드는 선택적이며 백엔드에서 안전한 기본값이 적용된다.
 */
export interface ImportParseOptions {
  delimiter?: string; // ",", "\t", ";", "|" (default ",")
  encoding?: 'AUTO' | 'UTF-8' | 'EUC-KR' | 'CP949';
  hasHeader?: boolean;
  skipRows?: number;
}

/**
 * 백엔드 ColumnMappingEntry 미러. 파일 컬럼을 데이터셋 컬럼에 매핑하는 엔트리.
 */
export interface ColumnMappingEntry {
  fileColumn: string;
  datasetColumn: string;
}

/** 백엔드 ColumnMappingDto 미러. preview 에서 suggested mapping 으로 반환된다. */
export interface ColumnMappingSuggestion {
  fileColumn: string;
  datasetColumn: string;
  matchType: string;
  confidence: number;
}

/** 백엔드 ImportPreviewResponse 미러. */
export interface ImportPreviewResponse {
  fileHeaders: string[];
  sampleRows: Array<Record<string, string>>;
  suggestedMappings: ColumnMappingSuggestion[];
  totalRows: number;
}

/** 백엔드 ValidationErrorDetail 미러. */
export interface ImportValidationError {
  rowNumber: number;
  columnName: string;
  value: string;
  error: string;
}

/** 백엔드 ImportValidateResponse 미러. */
export interface ImportValidateResponse {
  totalRows: number;
  validRows: number;
  errorRows: number;
  errors: ImportValidationError[];
}

/** 백엔드 ImportStartResponse 미러. jobId 는 Jobrunr 백그라운드 작업 식별자. */
export interface ImportStartResponse {
  jobId: string;
  status: string;
}

/** 백엔드 ImportResponse 미러. 임포트 이력/상태 조회 응답. */
export interface ImportResponse {
  id: number;
  datasetId: number;
  fileName: string;
  fileSize: number;
  fileType: string;
  status: string;
  totalRows: number | null;
  successRows: number | null;
  errorRows: number | null;
  errorDetails: unknown;
  errorMessage: string | null;
  importedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export type ImportMode = 'APPEND' | 'UPSERT' | 'REPLACE';

export interface PreviewImportArgs {
  datasetId: number;
  fileBytes: Buffer;
  fileName: string;
  parseOptions?: ImportParseOptions;
}

export interface ValidateImportArgs extends PreviewImportArgs {
  mappings: ColumnMappingEntry[];
}

export interface StartImportArgs extends PreviewImportArgs {
  mappings?: ColumnMappingEntry[];
  importMode?: ImportMode;
}

/**
 * 백엔드는 multipart/form-data 로 파일을 받는다. ai-agent 는 이미 업로드된
 * 첨부 파일을 fileId 로 다운로드한 후 Buffer 를 그대로 FormData 에 실어 백엔드로
 * 전달하는 브리지 구조를 사용한다. 상위 facade(FireHubApiClient) 에서 fileId ->
 * Buffer 변환을 담당한다.
 */
function buildImportFormData(
  fileBytes: Buffer,
  fileName: string,
  parseOptions: ImportParseOptions | undefined,
  extra: Record<string, string> = {},
): FormData {
  const form = new FormData();
  // Blob 은 Node 18+ 글로벌에서 제공되며 axios 1.7 은 이를 multipart 로 그대로 전송한다.
  const blob = new Blob([new Uint8Array(fileBytes)]);
  form.append('file', blob, fileName);

  if (parseOptions?.delimiter !== undefined) form.append('delimiter', parseOptions.delimiter);
  if (parseOptions?.encoding !== undefined) form.append('encoding', parseOptions.encoding);
  if (parseOptions?.hasHeader !== undefined)
    form.append('hasHeader', String(parseOptions.hasHeader));
  if (parseOptions?.skipRows !== undefined) form.append('skipRows', String(parseOptions.skipRows));

  for (const [k, v] of Object.entries(extra)) {
    form.append(k, v);
  }
  return form;
}

export function createDataImportApi(client: AxiosInstance) {
  return {
    /**
     * CSV/XLSX 파일을 미리보기한다. 헤더, 샘플 행, 추론된 매핑을 반환.
     * 대화형 스키마 설계의 출발점이며 datasetId 는 매핑 추론의 기준이 되는
     * 기존 데이터셋(있다면) 의 컬럼을 가지고 suggestedMappings 를 생성한다.
     */
    async previewImport(args: PreviewImportArgs): Promise<ImportPreviewResponse> {
      const form = buildImportFormData(args.fileBytes, args.fileName, args.parseOptions);
      const { data } = await client.post<ImportPreviewResponse>(
        `/datasets/${args.datasetId}/imports/preview`,
        form,
        MULTIPART_REQUEST_CONFIG,
      );
      return data;
    },

    /**
     * 매핑이 확정된 상태에서 각 행이 데이터셋 스키마에 적합한지 검증한다.
     * 실제 적재 없이 에러 리포트만 반환한다.
     */
    async validateImport(args: ValidateImportArgs): Promise<ImportValidateResponse> {
      const form = buildImportFormData(args.fileBytes, args.fileName, args.parseOptions, {
        mappings: JSON.stringify(args.mappings),
      });
      const { data } = await client.post<ImportValidateResponse>(
        `/datasets/${args.datasetId}/imports/validate`,
        form,
        MULTIPART_REQUEST_CONFIG,
      );
      return data;
    },

    /**
     * 실제 임포트 작업을 시작한다. 백엔드는 Jobrunr 로 비동기 처리하고 jobId 를
     * 반환하므로 호출자는 getImportStatus 로 진행 상황을 폴링해야 한다.
     */
    async startImport(args: StartImportArgs): Promise<ImportStartResponse> {
      const extra: Record<string, string> = {};
      if (args.mappings) extra.mappings = JSON.stringify(args.mappings);
      if (args.importMode) extra.importMode = args.importMode;
      const form = buildImportFormData(args.fileBytes, args.fileName, args.parseOptions, extra);
      const { data } = await client.post<ImportStartResponse>(
        `/datasets/${args.datasetId}/imports`,
        form,
        MULTIPART_REQUEST_CONFIG,
      );
      return data;
    },

    /** 단일 임포트 이력/상태 조회. importId 는 백엔드 ImportResponse.id. */
    async getImportStatus(datasetId: number, importId: number): Promise<ImportResponse> {
      const { data } = await client.get<ImportResponse>(
        `/datasets/${datasetId}/imports/${importId}`,
      );
      return data;
    },
  };
}
