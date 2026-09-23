import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { RowSearchBody } from '../api-client/dataset-api.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerDatasetTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_datasets',
      '데이터셋 목록을 필터/페이징으로 조회합니다(목록·필터·CRUD 용도). 분석 대상을 찾는 검색은 find_datasets를 사용하세요.',
      {
        categoryId: z.number().optional().describe('카테고리 ID'),
        storageType: z
          .enum(['TABLE', 'DOCUMENT', 'FILE'])
          .optional()
          .describe('저장 방식 (TABLE=정형 테이블, DOCUMENT=비정형 문서, FILE=파일 오브젝트)'),
        originType: z
          .enum(['SOURCE', 'DERIVED', 'TEMP'])
          .optional()
          .describe('출처 (SOURCE=원본, DERIVED=파생, TEMP=임시)'),
        search: z
          .string()
          .optional()
          .describe('검색어 — 이름·설명·테이블명·컬럼명·태그·카테고리명에 매칭. 디스커버리 시 후보를 좁히는 데 사용'),
        status: z.string().optional().describe('상태 (NONE, CERTIFIED, DEPRECATED)'),
        favoriteOnly: z.boolean().optional().describe('즐겨찾기만 조회 (기본값: false)'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
        size: z.number().optional().describe('페이지 크기'),
      },
      async (args: {
        categoryId?: number;
        storageType?: string;
        originType?: string;
        search?: string;
        status?: string;
        favoriteOnly?: boolean;
        page?: number;
        size?: number;
      }) => {
        const result = await apiClient.listDatasets(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_dataset',
      '데이터셋 상세 정보를 조회합니다. 컬럼 정보도 포함됩니다.',
      {
        id: z.number().describe('데이터셋 ID'),
      },
      async (args: { id: number }) => {
        const result = (await apiClient.getDataset(args.id)) as Record<string, unknown>;
        // 행 검색 가능 여부(검색 대상 필드·색인 상태)를 함께 준다. 실패해도 상세 조회는 막지 않는다.
        // 행 검색은 TABLE 데이터셋 전용이다 — DOCUMENT/FILE 에 조회하면 매번 400 이므로 아예 부르지 않는다.
        const searchIndex =
          result.storageType === 'TABLE'
            ? await apiClient.getDatasetSearchIndex(args.id).catch(() => null)
            : null;
        return jsonResult({ ...result, searchIndex });
      },
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
      async (args: {
        id: number;
        search?: string;
        sortBy?: string;
        sortDir?: string;
        includeTotalCount?: boolean;
        page?: number;
        size?: number;
      }) => {
        const { id, ...params } = args;
        const result = await apiClient.queryDatasetData(id, params);
        return jsonResult(result);
      },
    ),

    // 행 검색 — 텍스트 필드의 의미+키워드 하이브리드 검색. 결과는 원본 행이므로 PII 정책 대상이다.
    safeTool(
      'search_dataset_rows',
      '데이터셋 행을 텍스트 내용으로 찾습니다(의미+키워드 하이브리드). "누수 관련 신고", "이 민원과 비슷한 건"처럼 ' +
        '내용·의미로 찾을 때 사용합니다. 정확한 조건 조회·집계는 execute_analytics_query, 데이터셋 자체를 찾을 때는 ' +
        'find_datasets 를 쓰세요. get_dataset 의 searchIndex.enabled 가 false 면 사용할 수 없습니다(사용자에게 ' +
        '데이터셋 상세의 "검색" 탭에서 필드를 지정하도록 안내). indexStatus.status 가 SYNCING 이면 일부만 색인된 결과, ' +
        'STALE 이면 원본이 방금 교체되어 잠시 후 다시 시도해야 함을, degraded 가 true 면 키워드 검색만 수행됐음을 알리세요. ' +
        'TIMESTAMP 필터 값은 시간대(Z/+09:00) 없이 데이터에 저장된 현지 시각 그대로 보내세요.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        query: z.string().min(1).describe('찾을 내용(자연어 또는 키워드)'),
        mode: z
          .enum(['HYBRID', 'SEMANTIC', 'KEYWORD'])
          .optional()
          .describe('HYBRID(기본)=의미+키워드, SEMANTIC=의미만, KEYWORD=정확 문자열(번호·코드)'),
        filters: z
          .array(
            z.object({
              column: z.string().describe('컬럼명(get_dataset 의 columnName)'),
              op: z.enum(['eq', 'neq', 'in', 'gt', 'gte', 'lt', 'lte', 'is_null', 'is_not_null']),
              value: z.unknown().optional().describe('비교 값. in 은 배열, is_null/is_not_null 은 생략'),
            }),
          )
          .optional()
          .describe('AND 로 결합되는 조건 필터'),
        columns: z.array(z.string()).optional().describe('반환할 컬럼(생략 시 전체)'),
        limit: z.number().int().min(1).max(100).optional().describe('최대 결과 수(기본 20)'),
      },
      async (args: RowSearchBody & { datasetId: number }) => {
        const { datasetId, ...body } = args;
        const result = await apiClient.searchDatasetRows(datasetId, body);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_dataset',
      '새 데이터셋을 생성합니다. 데이터셋 생성 시 data 스키마에 실제 PostgreSQL 테이블이 생성됩니다.',
      {
        name: z.string().describe('데이터셋 이름'),
        tableName: z.string().describe('테이블 이름 ([a-z][a-z0-9_]* 패턴)'),
        description: z.string().optional().describe('데이터셋 설명'),
        categoryId: z.number().optional().describe('카테고리 ID'),
        originType: z
          .enum(['SOURCE', 'DERIVED'])
          .optional()
          .describe('출처 (SOURCE=원본, DERIVED=파생, 기본값: SOURCE). 저장 방식은 항상 TABLE(정형 테이블)입니다.'),
        columns: z
          .array(
            z.object({
              columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
              displayName: z.string().describe('표시 이름'),
              dataType: z
                .string()
                .describe(
                  '데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR)',
                ),
              maxLength: z.number().optional().describe('VARCHAR 타입의 최대 길이'),
              isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: false)'),
              isIndexed: z.boolean().optional().describe('인덱스 생성 여부 (기본값: false)'),
              isPrimaryKey: z
                .boolean()
                .optional()
                .describe(
                  '기본키 여부 (기본값: false). 기본키 컬럼은 isNullable이 false여야 합니다',
                ),
              description: z.string().optional().describe('컬럼 설명'),
            }),
          )
          .describe('컬럼 목록 (필수)'),
      },
      async (args: {
        name: string;
        tableName: string;
        description?: string;
        categoryId?: number;
        originType?: string;
        columns: Array<{
          columnName: string;
          displayName: string;
          dataType: string;
          maxLength?: number;
          isNullable?: boolean;
          isIndexed?: boolean;
          isPrimaryKey?: boolean;
          description?: string;
        }>;
      }) => {
        // 에이전트는 정형 데이터셋만 생성하므로 storageType 은 항상 TABLE 로 고정한다.
        // (비정형 DOCUMENT 데이터셋은 파일 업로드가 필요해 이 도구로 생성할 수 없다.)
        const result = await apiClient.createDataset({ ...args, storageType: 'TABLE' });
        return jsonResult(result);
      },
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
      },
    ),

    safeTool(
      'delete_dataset',
      '데이터셋을 삭제합니다. 물리 테이블과 모든 데이터가 영구 제거됩니다. 사용자의 명시적 평문 확인 없이는 호출하지 마세요.',
      {
        id: z.number().describe('삭제할 데이터셋 ID'),
      },
      async (args: { id: number }) => {
        // dataset-manager rules.md의 "실행 후 요약" 절이 삭제된 객체 이름·시각을
        // 응답에 반드시 포함하도록 요구하지만, 삭제 후에는 이름을 다시 조회할 수 없으므로
        // 삭제 직전에 이름을 확보해 둔다 (#571).
        const dataset = (await apiClient.getDataset(args.id)) as { name?: string } | undefined;
        await apiClient.deleteDataset(args.id);
        // 백엔드 DELETE 응답은 본문이 없어(204) 삭제 시각을 제공하지 않는다.
        // ai-agent 서버가 삭제 요청을 처리 완료한 시각을 서버 타임스탬프로 기록해 반환한다.
        return jsonResult({
          success: true,
          datasetId: args.id,
          datasetName: dataset?.name ?? null,
          deletedAt: new Date().toISOString(),
        });
      },
    ),

    safeTool(
      'add_dataset_column',
      '데이터셋에 컬럼을 추가합니다. GEOMETRY 컬럼의 경우 SRID 4326을 권장합니다.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
        displayName: z.string().describe('표시 이름'),
        // #597: 자유 문자열이면 지원하지 않는 값(예: MONEY)이 그대로 백엔드로 전달되어
        // DB CHECK 제약조건 위반(409, opaque 메시지)으로만 걸러졌다.
        // 백엔드 DB 제약조건(V29__enable_postgis.sql)·프론트엔드 검증 스키마와 동일한
        // 허용 목록을 z.enum으로 강제해 MCP 레이어에서 즉시 명확한 오류를 반환한다.
        dataType: z
          .enum(['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'VARCHAR', 'GEOMETRY'])
          .describe(
            '데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR, GEOMETRY)',
          ),
        maxLength: z.number().optional().describe('VARCHAR 최대 길이'),
        isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: true)'),
        isIndexed: z.boolean().optional().describe('인덱스 생성 여부'),
        description: z.string().optional().describe('컬럼 설명'),
      },
      async (args: {
        datasetId: number;
        columnName: string;
        displayName: string;
        dataType: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP' | 'VARCHAR' | 'GEOMETRY';
        maxLength?: number;
        isNullable?: boolean;
        isIndexed?: boolean;
        description?: string;
      }) => {
        const { datasetId, ...column } = args;
        const result = await apiClient.addDatasetColumn(datasetId, column);
        return jsonResult(result);
      },
    ),

    safeTool(
      'drop_dataset_column',
      '데이터셋에서 컬럼을 제거합니다. 해당 컬럼의 모든 데이터가 영구 삭제됩니다. 사용자의 명시적 평문 확인 없이 호출하지 마세요.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        columnId: z.number().describe('컬럼 ID'),
      },
      async (args: { datasetId: number; columnId: number }) => {
        await apiClient.dropDatasetColumn(args.datasetId, args.columnId);
        return jsonResult({ success: true });
      },
    ),

    safeTool(
      'get_dataset_references',
      '데이터셋을 참조하는 파이프라인/대시보드/스마트잡/DATASET_CHANGE 트리거를 조회합니다. 삭제 전 영향 범위 확인 필수. triggers는 FK가 아니라 트리거 config의 datasetIds 배열로만 연결되므로 다른 참조가 없어도 존재할 수 있다 — totalCount=0이 아니면 반드시 고지할 것.',
      { id: z.number().describe('데이터셋 ID') },
      async (args: { id: number }) => {
        const result = await apiClient.getDatasetReferences(args.id);
        return jsonResult(result);
      },
    ),
  ];
}
