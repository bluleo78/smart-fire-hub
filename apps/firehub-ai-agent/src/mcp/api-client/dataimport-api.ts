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
  // 엑셀에서 읽을 시트 이름(WD-253). 생략하면 백엔드가 첫 번째 보이는(숨기지 않은) 시트를 읽는다.
  // CSV 는 무시된다.
  sheetName?: string;
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
  /** 실제로 읽은 시트 이름(WD-253). CSV 면 null. */
  sheetName: string | null;
  /** 통합 문서의 시트 목록(통합 문서 순서, 숨김 여부 포함). CSV 면 null. */
  sheets: Array<{ name: string; hidden: boolean }> | null;
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

/**
 * 백엔드 AsyncJobStatusResponse 미러(GET /jobs/{jobId}/status).
 * start_import 가 돌려주는 jobId 로 진행 상황을 조회할 때 쓴다(WD-254).
 * stage: PENDING·PARSING·VALIDATING·INSERTING·COMPLETED·FAILED.
 * 완료 시 metadata 에 totalRows·successRows·errorRows, 실패 시 errorMessage 에 사용자용 사유가 담긴다.
 */
export interface AsyncJobStatusResponse {
  jobId: string;
  jobType: string;
  stage: string;
  progress: number;
  message: string | null;
  metadata: Record<string, unknown> | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  userId: number;
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
  // 빈 문자열·공백만 있는 값은 "지정 안 함"으로 보고 보내지 않는다 — 백엔드가 첫 번째 보이는 시트를 고르게 둔다.
  // 시트 이름 자체에는 앞뒤 공백이 있을 수 있어 값은 다듬지 않고 그대로 보낸다(WD-253).
  if (parseOptions?.sheetName !== undefined && parseOptions.sheetName.trim() !== '')
    form.append('sheetName', parseOptions.sheetName);

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
     * 실제 임포트 작업을 시작한다. 백엔드는 Jobrunr 로 비동기 처리하고 jobId(UUID)를
     * 반환하므로 호출자는 getImportJobStatus(jobId) 로 진행 상황을 폴링해야 한다(WD-254).
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

    /**
     * 비동기 작업 상태 조회(start_import 가 준 jobId). 백엔드는 작업을 시작한 사용자만 보게 하므로
     * 대행 호출(X-On-Behalf-Of)이면 그 사용자의 작업만 조회된다. 다른 사용자 작업 403, 없는 작업 404.
     */
    async getImportJobStatus(jobId: string): Promise<AsyncJobStatusResponse> {
      const { data } = await client.get<AsyncJobStatusResponse>(
        `/jobs/${encodeURIComponent(jobId)}/status`,
      );
      return data;
    },
  };
}
