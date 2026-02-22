import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerPipelineTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_pipelines',
      '파이프라인 목록을 조회합니다',
      {
        page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
        size: z.number().optional().describe('페이지 크기'),
      },
      async (args: { page?: number; size?: number }) => {
        const result = await apiClient.listPipelines(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_pipeline',
      '파이프라인 상세 정보를 조회합니다',
      {
        id: z.number().describe('파이프라인 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.getPipeline(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_pipeline',
      '새 파이프라인을 생성합니다. 스텝(SQL/PYTHON/API_CALL)과 DAG 의존성을 포함합니다.',
      {
        name: z.string().describe('파이프라인 이름'),
        description: z.string().optional().describe('파이프라인 설명'),
        steps: z
          .array(
            z.object({
              name: z.string().describe('스텝 이름 (의존성 참조에 사용)'),
              description: z.string().optional().describe('스텝 설명'),
              scriptType: z.enum(['SQL', 'PYTHON', 'API_CALL']).describe('스텝 유형'),
              scriptContent: z
                .string()
                .optional()
                .describe('SQL 또는 Python 스크립트 (API_CALL은 불필요)'),
              outputDatasetId: z.number().optional().describe('출력 데이터셋 ID'),
              inputDatasetIds: z.array(z.number()).optional().describe('입력 데이터셋 ID 목록'),
              dependsOnStepNames: z
                .array(z.string())
                .optional()
                .describe('의존하는 스텝 이름 목록 (DAG)'),
              loadStrategy: z
                .enum(['REPLACE', 'APPEND'])
                .optional()
                .describe('적재 전략 (기본: REPLACE)'),
              apiConfig: z
                .object({
                  url: z.string().describe('API 엔드포인트 URL'),
                  method: z.enum(['GET', 'POST', 'PUT', 'DELETE']).describe('HTTP 메서드'),
                  headers: z.record(z.string(), z.string()).optional().describe('커스텀 헤더'),
                  queryParams: z
                    .record(z.string(), z.string())
                    .optional()
                    .describe('쿼리 파라미터'),
                  body: z.string().optional().describe('요청 본문 (POST/PUT)'),
                  responseFormat: z.string().optional().describe('응답 형식 (기본: JSON)'),
                  dataPath: z.string().describe('JSONPath로 데이터 배열 추출 (예: $.items)'),
                  fieldMappings: z
                    .array(
                      z.object({
                        sourceField: z.string().describe('소스 필드명'),
                        targetColumn: z.string().describe('대상 컬럼명'),
                        dataType: z.string().optional().describe('데이터 타입'),
                        dateFormat: z.string().optional().describe('날짜 형식'),
                      }),
                    )
                    .optional()
                    .describe('필드 매핑 목록'),
                  sourceTimezone: z.string().optional().describe('소스 타임존'),
                  pagination: z
                    .object({
                      type: z.enum(['OFFSET']).describe('페이지네이션 유형'),
                      pageSize: z.number().describe('페이지 크기'),
                      offsetParam: z.string().optional().describe('오프셋 파라미터명'),
                      limitParam: z.string().optional().describe('리밋 파라미터명'),
                      totalPath: z.string().optional().describe('전체 수 JSONPath'),
                    })
                    .optional()
                    .describe('페이지네이션 설정'),
                  retry: z
                    .object({
                      maxRetries: z.number().optional().describe('최대 재시도 횟수'),
                      initialBackoffMs: z.number().optional().describe('초기 백오프(ms)'),
                      maxBackoffMs: z.number().optional().describe('최대 백오프(ms)'),
                    })
                    .optional()
                    .describe('재시도 설정'),
                  timeoutMs: z.number().optional().describe('요청 타임아웃(ms)'),
                  maxDurationMs: z.number().optional().describe('최대 실행 시간(ms)'),
                  maxResponseSizeMb: z.number().optional().describe('최대 응답 크기(MB)'),
                  inlineAuth: z
                    .record(z.string(), z.string())
                    .optional()
                    .describe('인라인 인증 정보'),
                })
                .optional()
                .describe('API_CALL 스텝 설정'),
              apiConnectionId: z.number().optional().describe('저장된 API 연결 ID'),
            }),
          )
          .describe('파이프라인 스텝 목록'),
      },
      async (args: {
        name: string;
        description?: string;
        steps: Array<{
          name: string;
          description?: string;
          scriptType: string;
          scriptContent?: string;
          outputDatasetId?: number;
          inputDatasetIds?: number[];
          dependsOnStepNames?: string[];
          loadStrategy?: string;
          apiConfig?: Record<string, unknown>;
          apiConnectionId?: number;
        }>;
      }) => {
        const result = await apiClient.createPipeline(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_pipeline',
      '파이프라인을 수정합니다. steps를 제공하면 전체 스텝이 교체됩니다.',
      {
        id: z.number().describe('파이프라인 ID'),
        name: z.string().optional().describe('파이프라인 이름'),
        description: z.string().optional().describe('파이프라인 설명'),
        isActive: z.boolean().optional().describe('활성화 여부'),
        steps: z
          .array(
            z.object({
              name: z.string().describe('스텝 이름'),
              description: z.string().optional().describe('스텝 설명'),
              scriptType: z.enum(['SQL', 'PYTHON', 'API_CALL']).describe('스텝 유형'),
              scriptContent: z.string().optional().describe('스크립트'),
              outputDatasetId: z.number().optional().describe('출력 데이터셋 ID'),
              inputDatasetIds: z.array(z.number()).optional().describe('입력 데이터셋 ID 목록'),
              dependsOnStepNames: z.array(z.string()).optional().describe('의존 스텝 이름 목록'),
              loadStrategy: z.enum(['REPLACE', 'APPEND']).optional().describe('적재 전략'),
              apiConfig: z.record(z.string(), z.unknown()).optional().describe('API_CALL 설정'),
              apiConnectionId: z.number().optional().describe('API 연결 ID'),
            }),
          )
          .optional()
          .describe('스텝 목록 (전체 교체)'),
      },
      async (args: {
        id: number;
        name?: string;
        description?: string;
        isActive?: boolean;
        steps?: Array<{
          name: string;
          description?: string;
          scriptType: string;
          scriptContent?: string;
          outputDatasetId?: number;
          inputDatasetIds?: number[];
          dependsOnStepNames?: string[];
          loadStrategy?: string;
          apiConfig?: Record<string, unknown>;
          apiConnectionId?: number;
        }>;
      }) => {
        const { id, ...data } = args;
        const result = await apiClient.updatePipeline(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_pipeline',
      '파이프라인을 삭제합니다. 연결된 체인 트리거도 비활성화됩니다.',
      {
        id: z.number().describe('파이프라인 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.deletePipeline(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'preview_api_call',
      'API 호출을 미리보기합니다. 파이프라인에 저장하기 전에 응답 데이터를 확인할 수 있습니다.',
      {
        url: z.string().describe('API 엔드포인트 URL'),
        method: z.enum(['GET', 'POST', 'PUT', 'DELETE']).describe('HTTP 메서드'),
        headers: z.record(z.string(), z.string()).optional().describe('커스텀 헤더'),
        queryParams: z.record(z.string(), z.string()).optional().describe('쿼리 파라미터'),
        body: z.string().optional().describe('요청 본문'),
        dataPath: z.string().describe('JSONPath로 데이터 배열 추출 (예: $.data)'),
        fieldMappings: z
          .array(
            z.object({
              sourceField: z.string().describe('소스 필드명'),
              targetColumn: z.string().describe('대상 컬럼명'),
              dataType: z.string().optional().describe('데이터 타입'),
            }),
          )
          .optional()
          .describe('필드 매핑'),
        apiConnectionId: z.number().optional().describe('저장된 API 연결 ID'),
        inlineAuth: z.record(z.string(), z.string()).optional().describe('인라인 인증 정보'),
        timeoutMs: z.number().optional().describe('타임아웃(ms)'),
      },
      async (args: {
        url: string;
        method: string;
        headers?: Record<string, string>;
        queryParams?: Record<string, string>;
        body?: string;
        dataPath: string;
        fieldMappings?: Array<{ sourceField: string; targetColumn: string; dataType?: string }>;
        apiConnectionId?: number;
        inlineAuth?: Record<string, string>;
        timeoutMs?: number;
      }) => {
        const result = await apiClient.previewApiCall(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'execute_pipeline',
      '파이프라인을 실행합니다',
      {
        id: z.number().describe('파이프라인 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.executePipeline(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_execution_status',
      '파이프라인 실행 상태를 조회합니다',
      {
        pipelineId: z.number().describe('파이프라인 ID'),
        executionId: z.number().describe('실행 ID'),
      },
      async (args: { pipelineId: number; executionId: number }) => {
        const result = await apiClient.getExecutionStatus(args.pipelineId, args.executionId);
        return jsonResult(result);
      },
    ),
  ];
}
