import { z } from 'zod/v4';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance, AnyZodRawShape, InferShape } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from './api-client.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function safeTool<Schema extends AnyZodRawShape>(
  name: string,
  description: string,
  schema: Schema,
  handler: (args: InferShape<Schema>) => Promise<ToolResult>,
) {
  return tool(name, description, schema, async (args: InferShape<Schema>): Promise<ToolResult> => {
    try {
      return await handler(args);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[MCP Tool] ${name} failed: ${message}`);
      return { content: [{ type: 'text', text: message }], isError: true };
    }
  });
}

function jsonResult(data: unknown): ToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}

export function createFireHubMcpServer(apiClient: FireHubApiClient): McpSdkServerConfigWithInstance {
  return createSdkMcpServer({
    name: 'firehub',
    version: '1.0.0',
    tools: [
      safeTool(
        'list_categories',
        '데이터셋 카테고리 목록을 조회합니다',
        {},
        async () => {
          const result = await apiClient.listCategories();
          return jsonResult(result);
        }
      ),

      safeTool(
        'create_category',
        '새 데이터셋 카테고리를 생성합니다',
        {
          name: z.string().describe('카테고리 이름'),
          description: z.string().optional().describe('카테고리 설명'),
        },
        async (args: { name: string; description?: string }) => {
          const result = await apiClient.createCategory(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_category',
        '데이터셋 카테고리를 수정합니다',
        {
          id: z.number().describe('카테고리 ID'),
          name: z.string().describe('카테고리 이름'),
          description: z.string().optional().describe('카테고리 설명'),
        },
        async (args: { id: number; name: string; description?: string }) => {
          const { id, ...data } = args;
          const result = await apiClient.updateCategory(id, data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'list_datasets',
        '데이터셋 목록을 조회합니다',
        {
          categoryId: z.number().optional().describe('카테고리 ID'),
          datasetType: z.string().optional().describe('데이터셋 타입 (SOURCE 또는 DERIVED)'),
          search: z.string().optional().describe('검색어'),
          status: z.string().optional().describe('상태 (NONE, CERTIFIED, DEPRECATED)'),
          favoriteOnly: z.boolean().optional().describe('즐겨찾기만 조회 (기본값: false)'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { categoryId?: number; datasetType?: string; search?: string; status?: string; favoriteOnly?: boolean; page?: number; size?: number }) => {
          const result = await apiClient.listDatasets(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_dataset',
        '데이터셋 상세 정보를 조회합니다. 컬럼 정보도 포함됩니다.',
        {
          id: z.number().describe('데이터셋 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getDataset(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'query_dataset_data',
        '데이터셋의 데이터를 조회합니다',
        {
          id: z.number().describe('데이터셋 ID'),
          search: z.string().optional().describe('검색어'),
          sortBy: z.string().optional().describe('정렬 기준 컬럼명'),
          sortDir: z.string().optional().describe('정렬 방향 (ASC 또는 DESC, 기본값: ASC)'),
          includeTotalCount: z.boolean().optional().describe('전체 행 수 포함 여부 (기본값: true)'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { id: number; search?: string; sortBy?: string; sortDir?: string; includeTotalCount?: boolean; page?: number; size?: number }) => {
          const { id, ...params } = args;
          const result = await apiClient.queryDatasetData(id, params);
          return jsonResult(result);
        }
      ),

      safeTool(
        'create_dataset',
        '새 데이터셋을 생성합니다. 데이터셋 생성 시 data 스키마에 실제 PostgreSQL 테이블이 생성됩니다.',
        {
          name: z.string().describe('데이터셋 이름'),
          tableName: z.string().describe('테이블 이름 ([a-z][a-z0-9_]* 패턴)'),
          description: z.string().optional().describe('데이터셋 설명'),
          categoryId: z.number().optional().describe('카테고리 ID'),
          datasetType: z.string().optional().describe('데이터셋 타입 (SOURCE 또는 DERIVED, 기본값: SOURCE)'),
          columns: z.array(z.object({
            columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
            displayName: z.string().describe('표시 이름'),
            dataType: z.string().describe('데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR)'),
            maxLength: z.number().optional().describe('VARCHAR 타입의 최대 길이'),
            isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: false)'),
            isIndexed: z.boolean().optional().describe('인덱스 생성 여부 (기본값: false)'),
            isPrimaryKey: z.boolean().optional().describe('기본키 여부 (기본값: false). 기본키 컬럼은 isNullable이 false여야 합니다'),
            description: z.string().optional().describe('컬럼 설명'),
          })).describe('컬럼 목록 (필수)'),
        },
        async (args: {
          name: string; tableName: string; description?: string; categoryId?: number; datasetType?: string;
          columns: Array<{ columnName: string; displayName: string; dataType: string; maxLength?: number; isNullable?: boolean; isIndexed?: boolean; isPrimaryKey?: boolean; description?: string }>;
        }) => {
          const result = await apiClient.createDataset(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_dataset',
        '데이터셋 정보를 수정합니다 (이름, 설명, 카테고리)',
        {
          id: z.number().describe('데이터셋 ID'),
          name: z.string().optional().describe('데이터셋 이름'),
          description: z.string().optional().describe('데이터셋 설명'),
          categoryId: z.number().optional().describe('카테고리 ID'),
        },
        async (args: { id: number; name?: string; description?: string; categoryId?: number }) => {
          const { id, ...data } = args;
          const result = await apiClient.updateDataset(id, data);
          return jsonResult(result);
        }
      ),

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
        }
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
        }
      ),

      safeTool(
        'create_pipeline',
        '새 파이프라인을 생성합니다. 스텝(SQL/PYTHON/API_CALL)과 DAG 의존성을 포함합니다.',
        {
          name: z.string().describe('파이프라인 이름'),
          description: z.string().optional().describe('파이프라인 설명'),
          steps: z.array(z.object({
            name: z.string().describe('스텝 이름 (의존성 참조에 사용)'),
            description: z.string().optional().describe('스텝 설명'),
            scriptType: z.enum(['SQL', 'PYTHON', 'API_CALL']).describe('스텝 유형'),
            scriptContent: z.string().optional().describe('SQL 또는 Python 스크립트 (API_CALL은 불필요)'),
            outputDatasetId: z.number().optional().describe('출력 데이터셋 ID'),
            inputDatasetIds: z.array(z.number()).optional().describe('입력 데이터셋 ID 목록'),
            dependsOnStepNames: z.array(z.string()).optional().describe('의존하는 스텝 이름 목록 (DAG)'),
            loadStrategy: z.enum(['REPLACE', 'APPEND']).optional().describe('적재 전략 (기본: REPLACE)'),
            apiConfig: z.object({
              url: z.string().describe('API 엔드포인트 URL'),
              method: z.enum(['GET', 'POST', 'PUT', 'DELETE']).describe('HTTP 메서드'),
              headers: z.record(z.string(), z.string()).optional().describe('커스텀 헤더'),
              queryParams: z.record(z.string(), z.string()).optional().describe('쿼리 파라미터'),
              body: z.string().optional().describe('요청 본문 (POST/PUT)'),
              responseFormat: z.string().optional().describe('응답 형식 (기본: JSON)'),
              dataPath: z.string().describe('JSONPath로 데이터 배열 추출 (예: $.items)'),
              fieldMappings: z.array(z.object({
                sourceField: z.string().describe('소스 필드명'),
                targetColumn: z.string().describe('대상 컬럼명'),
                dataType: z.string().optional().describe('데이터 타입'),
                dateFormat: z.string().optional().describe('날짜 형식'),
              })).optional().describe('필드 매핑 목록'),
              sourceTimezone: z.string().optional().describe('소스 타임존'),
              pagination: z.object({
                type: z.enum(['OFFSET']).describe('페이지네이션 유형'),
                pageSize: z.number().describe('페이지 크기'),
                offsetParam: z.string().optional().describe('오프셋 파라미터명'),
                limitParam: z.string().optional().describe('리밋 파라미터명'),
                totalPath: z.string().optional().describe('전체 수 JSONPath'),
              }).optional().describe('페이지네이션 설정'),
              retry: z.object({
                maxRetries: z.number().optional().describe('최대 재시도 횟수'),
                initialBackoffMs: z.number().optional().describe('초기 백오프(ms)'),
                maxBackoffMs: z.number().optional().describe('최대 백오프(ms)'),
              }).optional().describe('재시도 설정'),
              timeoutMs: z.number().optional().describe('요청 타임아웃(ms)'),
              maxDurationMs: z.number().optional().describe('최대 실행 시간(ms)'),
              maxResponseSizeMb: z.number().optional().describe('최대 응답 크기(MB)'),
              inlineAuth: z.record(z.string(), z.string()).optional().describe('인라인 인증 정보'),
            }).optional().describe('API_CALL 스텝 설정'),
            apiConnectionId: z.number().optional().describe('저장된 API 연결 ID'),
          })).describe('파이프라인 스텝 목록'),
        },
        async (args) => {
          const result = await apiClient.createPipeline(args as any);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_pipeline',
        '파이프라인을 수정합니다. steps를 제공하면 전체 스텝이 교체됩니다.',
        {
          id: z.number().describe('파이프라인 ID'),
          name: z.string().optional().describe('파이프라인 이름'),
          description: z.string().optional().describe('파이프라인 설명'),
          isActive: z.boolean().optional().describe('활성화 여부'),
          steps: z.array(z.object({
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
          })).optional().describe('스텝 목록 (전체 교체)'),
        },
        async (args) => {
          const { id, ...data } = args;
          const result = await apiClient.updatePipeline(id, data as any);
          return jsonResult(result);
        }
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
        }
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
          fieldMappings: z.array(z.object({
            sourceField: z.string().describe('소스 필드명'),
            targetColumn: z.string().describe('대상 컬럼명'),
            dataType: z.string().optional().describe('데이터 타입'),
          })).optional().describe('필드 매핑'),
          apiConnectionId: z.number().optional().describe('저장된 API 연결 ID'),
          inlineAuth: z.record(z.string(), z.string()).optional().describe('인라인 인증 정보'),
          timeoutMs: z.number().optional().describe('타임아웃(ms)'),
        },
        async (args) => {
          const result = await apiClient.previewApiCall(args as any);
          return jsonResult(result);
        }
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
        }
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
        }
      ),

      // Triggers
      safeTool(
        'list_triggers',
        '파이프라인의 트리거 목록을 조회합니다',
        {
          pipelineId: z.number().describe('파이프라인 ID'),
        },
        async (args: { pipelineId: number }) => {
          const result = await apiClient.listTriggers(args.pipelineId);
          return jsonResult(result);
        }
      ),

      safeTool(
        'create_trigger',
        '파이프라인 트리거를 생성합니다. 유형: SCHEDULE(크론), API(토큰), PIPELINE_CHAIN(연쇄), WEBHOOK(웹훅), DATASET_CHANGE(데이터 변경)',
        {
          pipelineId: z.number().describe('파이프라인 ID'),
          name: z.string().describe('트리거 이름'),
          triggerType: z.enum(['SCHEDULE', 'API', 'PIPELINE_CHAIN', 'WEBHOOK', 'DATASET_CHANGE']).describe('트리거 유형'),
          description: z.string().optional().describe('트리거 설명'),
          config: z.record(z.string(), z.unknown()).describe('트리거 설정. SCHEDULE: {cronExpression}, API: {}, PIPELINE_CHAIN: {upstreamPipelineId}, WEBHOOK: {secret?}, DATASET_CHANGE: {datasetId}'),
        },
        async (args) => {
          const { pipelineId, ...data } = args;
          const result = await apiClient.createTrigger(pipelineId, data as any);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_trigger',
        '파이프라인 트리거를 수정합니다',
        {
          pipelineId: z.number().describe('파이프라인 ID'),
          triggerId: z.number().describe('트리거 ID'),
          name: z.string().optional().describe('트리거 이름'),
          isEnabled: z.boolean().optional().describe('활성화 여부'),
          description: z.string().optional().describe('트리거 설명'),
          config: z.record(z.string(), z.unknown()).optional().describe('트리거 설정'),
        },
        async (args) => {
          const { pipelineId, triggerId, ...data } = args;
          const result = await apiClient.updateTrigger(pipelineId, triggerId, data as any);
          return jsonResult(result);
        }
      ),

      safeTool(
        'delete_trigger',
        '파이프라인 트리거를 삭제합니다',
        {
          pipelineId: z.number().describe('파이프라인 ID'),
          triggerId: z.number().describe('트리거 ID'),
        },
        async (args: { pipelineId: number; triggerId: number }) => {
          const result = await apiClient.deleteTrigger(args.pipelineId, args.triggerId);
          return jsonResult(result);
        }
      ),

      // API Connections
      safeTool(
        'list_api_connections',
        '저장된 API 연결 목록을 조회합니다',
        {},
        async () => {
          const result = await apiClient.listApiConnections();
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_api_connection',
        'API 연결 상세 정보를 조회합니다 (인증 값은 마스킹됨)',
        {
          id: z.number().describe('API 연결 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getApiConnection(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'create_api_connection',
        '새 API 연결을 생성합니다. 인증 정보는 암호화되어 저장됩니다.',
        {
          name: z.string().describe('연결 이름'),
          description: z.string().optional().describe('연결 설명'),
          authType: z.enum(['API_KEY', 'BEARER']).describe('인증 유형'),
          authConfig: z.record(z.string(), z.string()).describe('인증 설정. API_KEY: {placement, headerName/paramName, apiKey}, BEARER: {token}'),
        },
        async (args) => {
          const result = await apiClient.createApiConnection(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_api_connection',
        'API 연결을 수정합니다. authConfig를 제공하면 인증 정보가 갱신됩니다.',
        {
          id: z.number().describe('API 연결 ID'),
          name: z.string().optional().describe('연결 이름'),
          description: z.string().optional().describe('연결 설명'),
          authType: z.string().optional().describe('인증 유형 (API_KEY 또는 BEARER)'),
          authConfig: z.record(z.string(), z.string()).optional().describe('인증 설정'),
        },
        async (args) => {
          const { id, ...data } = args;
          const result = await apiClient.updateApiConnection(id, data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'delete_api_connection',
        'API 연결을 삭제합니다',
        {
          id: z.number().describe('API 연결 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.deleteApiConnection(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'list_imports',
        '데이터셋의 임포트 이력을 조회합니다',
        {
          datasetId: z.number().describe('데이터셋 ID'),
        },
        async (args: { datasetId: number }) => {
          const result = await apiClient.listImports(args.datasetId);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_dashboard',
        '대시보드 통계를 조회합니다',
        {},
        async () => {
          const result = await apiClient.getDashboard();
          return jsonResult(result);
        }
      ),

      safeTool(
        'execute_sql_query',
        '데이터셋 테이블에 SQL 쿼리를 실행합니다. SELECT, INSERT, UPDATE, DELETE를 지원합니다. 테이블명은 데이터셋의 tableName을 사용하세요 (get_dataset으로 확인).',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          sql: z.string().describe('실행할 SQL 쿼리. 테이블명은 data."{tableName}" 형식으로 사용'),
          maxRows: z.number().min(1).max(1000).optional().describe('최대 반환 행 수 (SELECT 시, 기본 1000)'),
        },
        async (args: { datasetId: number; sql: string; maxRows?: number }) => {
          const result = await apiClient.executeQuery(args.datasetId, args.sql, args.maxRows);
          return jsonResult(result);
        }
      ),

      safeTool(
        'add_row',
        '데이터셋에 단일 행을 추가합니다. 컬럼명-값 쌍으로 데이터를 전달합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          data: z.record(z.string(), z.unknown()).describe('컬럼명-값 쌍. 예: {"name": "홍길동", "age": 30}'),
        },
        async (args: { datasetId: number; data: Record<string, unknown> }) => {
          const result = await apiClient.addRow(args.datasetId, args.data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'add_rows',
        '데이터셋에 여러 행을 한번에 추가합니다. 최대 100행까지 지원합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rows: z.array(z.record(z.string(), z.unknown())).min(1).max(100).describe('추가할 행 배열. 각 항목은 컬럼명-값 쌍'),
        },
        async (args: { datasetId: number; rows: Record<string, unknown>[] }) => {
          const result = await apiClient.addRowsBatch(args.datasetId, args.rows);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_row',
        '데이터셋의 기존 행을 수정합니다. 모든 필수(non-nullable) 컬럼 값을 포함해야 합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rowId: z.number().describe('수정할 행의 ID'),
          data: z.record(z.string(), z.unknown()).describe('수정할 컬럼명-값 쌍'),
        },
        async (args: { datasetId: number; rowId: number; data: Record<string, unknown> }) => {
          const result = await apiClient.updateRow(args.datasetId, args.rowId, args.data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'delete_rows',
        '데이터셋에서 행을 삭제합니다. 최대 1000행까지 한번에 삭제 가능합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rowIds: z.array(z.number()).min(1).max(1000).describe('삭제할 행 ID 배열'),
        },
        async (args: { datasetId: number; rowIds: number[] }) => {
          const result = await apiClient.deleteRows(args.datasetId, args.rowIds);
          return jsonResult(result);
        }
      ),

      safeTool(
        'truncate_dataset',
        '데이터셋의 모든 데이터를 삭제합니다. 테이블 구조(스키마)는 유지됩니다. 전체 삭제 시 delete_rows 대신 이 도구를 사용하세요.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
        },
        async (args: { datasetId: number }) => {
          const result = await apiClient.truncateDataset(args.datasetId);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_row_count',
        '데이터셋의 행 수를 조회합니다. 데이터를 조회하지 않고 빠르게 행 수만 확인할 때 사용합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
        },
        async (args: { datasetId: number }) => {
          const result = await apiClient.getRowCount(args.datasetId);
          return jsonResult(result);
        }
      ),

      safeTool(
        'replace_dataset_data',
        '데이터셋의 모든 데이터를 새 데이터로 교체합니다. 기존 데이터 전체 삭제 후 새 데이터를 삽입합니다 (원자적 트랜잭션). 최대 100행.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rows: z.array(z.record(z.string(), z.unknown())).min(1).max(100).describe('새로 삽입할 행 배열. 각 항목은 컬럼명-값 쌍'),
        },
        async (args: { datasetId: number; rows: Record<string, unknown>[] }) => {
          const result = await apiClient.replaceDatasetData(args.datasetId, args.rows);
          return jsonResult(result);
        }
      ),
    ],
  });
}
