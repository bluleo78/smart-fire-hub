import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * CSV/XLSX 임포트 관련 MCP 도구들. dataset-manager 서브에이전트가 대화형으로
 * 미리보기 -> 매핑 -> 검증 -> 적재 -> 상태확인 흐름을 주도할 때 사용한다.
 * 백엔드는 multipart 업로드를 요구하므로 facade 에서 fileId -> Buffer 브리지를 수행한다.
 */
// 공통 파스 옵션 스키마/타입 — 모든 도구(preview/validate/start)가 동일 옵션 셋을 공유하므로
// 여기에서 한 번만 정의하고, 각 도구의 스키마·핸들러 인자 타입에서 재사용한다.
const parseOptionsSchema = {
  delimiter: z
    .string()
    .optional()
    .describe('CSV 구분자 (",", "\\t", ";", "|"). 기본값: ","'),
  encoding: z
    .enum(['AUTO', 'UTF-8', 'EUC-KR', 'CP949'])
    .optional()
    .describe('파일 인코딩. 기본값: AUTO'),
  hasHeader: z.boolean().optional().describe('첫 행이 헤더인지 여부. 기본값: true'),
  skipRows: z.number().optional().describe('헤더 전 건너뛸 행 수. 기본값: 0'),
} as const;

// 위 parseOptionsSchema 와 동일한 필드 셋의 TS 타입.
// z.infer 를 사용하면 const assertion 과 조합이 번거로우므로 수동으로 선언한다.
type ParseOptionsArgs = {
  delimiter?: string;
  encoding?: 'AUTO' | 'UTF-8' | 'EUC-KR' | 'CP949';
  hasHeader?: boolean;
  skipRows?: number;
};

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
      '임포트 작업의 상태/진행률을 조회합니다. start_import 이후 주기적으로 호출.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        importId: z.number().describe('임포트 레코드 ID'),
      },
      async (args: { datasetId: number; importId: number }) => {
        const result = await apiClient.getImportStatus(args.datasetId, args.importId);
        return jsonResult(result);
      },
    ),
  ];
}
