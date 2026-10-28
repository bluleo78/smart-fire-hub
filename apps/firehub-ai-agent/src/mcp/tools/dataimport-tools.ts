import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import type { AsyncJobStatusResponse, ImportParseOptions } from '../api-client/dataimport-api.js';

/**
 * CSV/XLSX 임포트 관련 MCP 도구들. dataset-manager 서브에이전트가 대화형으로
 * 미리보기 -> 매핑 -> 검증 -> 적재 -> 상태확인 흐름을 주도할 때 사용한다.
 * 백엔드는 multipart 업로드를 요구하므로 facade 에서 fileId -> Buffer 브리지를 수행한다.
 */
// 공통 파스 옵션 스키마/타입 — 모든 도구(preview/validate/start)가 동일 옵션 셋을 공유하므로
// 여기에서 한 번만 정의하고, 각 도구의 스키마·핸들러 인자 타입에서 재사용한다.
const parseOptionsSchema = {
  delimiter: z.string().optional().describe('CSV 구분자 (",", "\\t", ";", "|"). 기본값: ","'),
  encoding: z
    .enum(['AUTO', 'UTF-8', 'EUC-KR', 'CP949'])
    .optional()
    .describe('파일 인코딩. 기본값: AUTO'),
  hasHeader: z.boolean().optional().describe('첫 행이 헤더인지 여부. 기본값: true'),
  skipRows: z.number().optional().describe('헤더 전 건너뛸 행 수. 기본값: 0'),
  // WD-253: 엑셀 시트 선택. 미지정 시 첫 번째 보이는 시트 — preview/validate/start 에 같은 값을 넘겨야 같은 시트를 읽는다.
  sheetName: z
    .string()
    .optional()
    .describe(
      '엑셀에서 가져올 시트 이름. 생략하면 첫 번째 보이는(숨기지 않은) 시트. preview_csv 결과의 sheets 목록에서 고르고, validate_import·start_import 에도 같은 값을 넘긴다. CSV는 무시',
    ),
} as const;

// 위 parseOptionsSchema 와 같은 필드 셋의 TS 타입 — API 클라이언트가 보내는 옵션 타입을 그대로 써서 두 곳이 어긋나지 않게 한다.
type ParseOptionsArgs = ImportParseOptions;

export function registerDataImportTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  // 매핑 엔트리 스키마. validate/start 에서 공통 사용.
  const mappingSchema = z
    .array(
      z.object({
        fileColumn: z.string().describe('파일의 컬럼 이름'),
        datasetColumn: z.string().describe('데이터셋의 대상 컬럼 이름'),
      }),
    )
    .describe('파일 컬럼 -> 데이터셋 컬럼 매핑 목록');

  return [
    safeTool(
      'preview_csv',
      'CSV/XLSX 파일의 첫 N행과 컬럼 타입 추론 결과를 가져옵니다. 스키마 설계 대화의 출발점.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID (매핑 추론의 기준)'),
        fileId: z.number().describe('채팅에 첨부된 파일 ID'),
        ...parseOptionsSchema,
      },
      async (args: { datasetId: number; fileId: number } & ParseOptionsArgs) => {
        const { datasetId, fileId, ...parseOptions } = args;
        const result = await apiClient.previewImport(datasetId, fileId, parseOptions);
        return jsonResult(result);
      },
    ),

    safeTool(
      'validate_import',
      '파일과 매핑 정보를 바탕으로 각 행이 데이터셋 스키마에 적합한지 검증합니다. 실제 적재 없이 에러 리포트만 반환.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        fileId: z.number().describe('채팅에 첨부된 파일 ID'),
        mappings: mappingSchema,
        ...parseOptionsSchema,
      },
      async (
        args: {
          datasetId: number;
          fileId: number;
          mappings: Array<{ fileColumn: string; datasetColumn: string }>;
        } & ParseOptionsArgs,
      ) => {
        const { datasetId, fileId, mappings, ...parseOptions } = args;
        const result = await apiClient.validateImport(datasetId, fileId, mappings, parseOptions);
        return jsonResult(result);
      },
    ),

    safeTool(
      'start_import',
      'CSV/XLSX 파일로부터 실제 임포트 작업을 시작합니다. 비동기 작업이며 jobId 를 반환합니다. 사용자 승인 후에만 호출.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        fileId: z.number().describe('채팅에 첨부된 파일 ID'),
        mappings: mappingSchema.optional(),
        importMode: z
          .enum(['APPEND', 'UPSERT', 'REPLACE'])
          .optional()
          .describe('적재 전략. APPEND(기본)/UPSERT/REPLACE'),
        ...parseOptionsSchema,
      },
      async (
        args: {
          datasetId: number;
          fileId: number;
          mappings?: Array<{ fileColumn: string; datasetColumn: string }>;
          importMode?: 'APPEND' | 'UPSERT' | 'REPLACE';
        } & ParseOptionsArgs,
      ) => {
        const { datasetId, fileId, mappings, importMode, ...parseOptions } = args;
        const result = await apiClient.startImport(datasetId, fileId, {
          mappings,
          importMode,
          parseOptions,
        });
        return jsonResult(result);
      },
    ),

    safeTool(
      'import_status',
      '임포트 작업의 단계·진행률·결과를 조회합니다. start_import 가 돌려준 jobId 를 그대로 넣으세요. ' +
        'done=false 이면 잠시 뒤 다시 조회하고, done=true 이면 summary(적재 행 수 또는 실패 사유)를 사용자에게 전합니다. ' +
        '(예전 방식: 가져오기 이력 id 를 알면 datasetId+importId 로도 조회 가능)',
      {
        jobId: z
          .string()
          .optional()
          .describe('start_import 응답의 jobId(UUID 문자열). 이것을 우선 사용'),
        datasetId: z.number().optional().describe('데이터셋 ID (importId 로 조회할 때 필수)'),
        importId: z
          .number()
          .optional()
          .describe('가져오기 이력(감사 기록) ID. jobId 가 없을 때만 사용'),
      },
      async (args: { jobId?: string; datasetId?: number; importId?: number }) => {
        // jobId 경로(기본): start_import 와 바로 이어지는 흐름(WD-254).
        // 이전에는 숫자 importId 만 받아 AI 가 jobId 를 넣으면 400 "Validation failed" 가 났다.
        const jobId = args.jobId?.trim();
        if (jobId) {
          const status = await apiClient.getImportJobStatus(jobId);
          if (status.jobType && status.jobType !== 'IMPORT') {
            throw new Error(
              `이 jobId 는 임포트 작업이 아닙니다(작업 종류: ${status.jobType}). start_import 가 돌려준 jobId 를 넣어 주세요.`,
            );
          }
          return jsonResult(summarizeImportJob(status));
        }
        // 하위 호환: 감사 기록 id 로 이력 단건을 조회하는 기존 경로.
        if (args.importId !== undefined) {
          if (args.datasetId === undefined) {
            throw new Error('importId 로 조회하려면 datasetId 도 함께 넣어 주세요.');
          }
          const result = await apiClient.getImportStatus(args.datasetId, args.importId);
          return jsonResult(result);
        }
        throw new Error(
          'jobId 가 필요합니다. start_import 응답의 jobId 를 넣어 주세요. (또는 datasetId 와 importId)',
        );
      },
    ),
  ];
}

/** 작업 단계 코드 → 사용자용 한국어 이름. 백엔드 message 는 영어("Parsing file...")라 그대로 전하지 않는다. */
const IMPORT_STAGE_LABELS: Record<string, string> = {
  PENDING: '대기 중',
  PARSING: '파일 읽는 중',
  VALIDATING: '검증 중',
  INSERTING: '적재 중',
  COMPLETED: '완료',
  FAILED: '실패',
};

/** metadata 의 숫자 값만 꺼낸다(없거나 숫자가 아니면 null). */
function numberOf(
  metadata: Record<string, unknown> | null | undefined,
  key: string,
): number | null {
  const v = metadata?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * 작업 상태 응답을 AI 가 바로 전할 수 있는 요약으로 바꾼다(WD-254).
 * - done: 완료·실패면 true — AI 가 폴링을 멈출 신호
 * - 완료: metadata 의 totalRows·successRows·errorRows 로 적재 결과 문장
 * - 실패: errorMessage(백엔드가 남긴 한국어 사용자용 사유)
 * - 진행 중: 단계 이름 + 진행률(+ 처리 행 수가 있으면 함께)
 */
export function summarizeImportJob(status: AsyncJobStatusResponse) {
  const stage = status.stage ?? 'PENDING';
  const stageLabel = IMPORT_STAGE_LABELS[stage] ?? stage;
  const progress = typeof status.progress === 'number' ? status.progress : 0;
  const md = status.metadata ?? {};
  const fmt = (n: number) => n.toLocaleString('ko-KR');
  // 진행 중 metadata 의 totalRows 는 0(아직 모름)일 수 있어, 결과 행 수는 완료 때만 싣는다.
  const done = stage === 'COMPLETED' || stage === 'FAILED';
  const totalRows = stage === 'COMPLETED' ? numberOf(md, 'totalRows') : null;
  const successRows = stage === 'COMPLETED' ? numberOf(md, 'successRows') : null;
  const errorRows = stage === 'COMPLETED' ? numberOf(md, 'errorRows') : null;
  const processedRows = done ? null : numberOf(md, 'processedRows');

  let summary: string;
  if (stage === 'COMPLETED') {
    const details: string[] = [];
    if (totalRows !== null) details.push(`전체 ${fmt(totalRows)}행`);
    if (errorRows) details.push(`오류 ${fmt(errorRows)}행`);
    summary =
      successRows !== null
        ? `임포트가 완료되었습니다. ${fmt(successRows)}행을 적재했습니다` +
          (details.length ? ` (${details.join(', ')})` : '') +
          '.'
        : '임포트가 완료되었습니다.';
  } else if (stage === 'FAILED') {
    summary = `임포트가 실패했습니다. 사유: ${status.errorMessage || '알 수 없는 오류'}`;
  } else {
    const inProgressTotal = numberOf(md, 'totalRows');
    const rowsPart =
      processedRows !== null && inProgressTotal
        ? ` (${fmt(processedRows)}/${fmt(inProgressTotal)}행)`
        : processedRows
          ? ` (${fmt(processedRows)}행 처리)`
          : '';
    summary = `임포트 진행 중 — ${stageLabel}, ${progress}%${rowsPart}. 잠시 뒤 다시 조회하세요.`;
  }

  return {
    jobId: status.jobId,
    stage,
    stageLabel,
    progress,
    done,
    processedRows,
    totalRows,
    successRows,
    errorRows,
    errorMessage: stage === 'FAILED' ? (status.errorMessage ?? null) : null,
    summary,
  };
}
