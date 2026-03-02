import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerAnalyticsTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // 1. 임시 SQL 실행 (SELECT-only, 저장 없이)
    // CRITICAL: AI 도구는 readOnly=true로 호출. DML(INSERT/UPDATE/DELETE) 차단.
    // 전체 data 스키마에서 실행되므로 DML 허용 시 보안 위험.
    safeTool(
      'execute_analytics_query',
      'data 스키마의 모든 테이블에서 SELECT 쿼리를 실행합니다. cross-dataset JOIN 가능. DML은 지원하지 않습니다.',
      {
        sql: z.string().describe('실행할 SELECT SQL 쿼리. 테이블명은 data 스키마에서 그냥 "tableName" 형식으로 사용'),
        maxRows: z
          .number()
          .min(1)
          .max(10000)
          .optional()
          .describe('최대 반환 행 수 (기본 1000, 최대 10000)'),
      },
      async (args: { sql: string; maxRows?: number }) => {
        const result = await apiClient.executeAnalyticsQuery(args.sql, args.maxRows);
        return jsonResult(result);
      },
    ),

    // 2. 저장된 쿼리 생성
    safeTool(
      'create_saved_query',
      'SQL 쿼리를 저장합니다. 차트의 데이터 소스로 사용할 수 있습니다.',
      {
        name: z.string().describe('쿼리 이름'),
        sqlText: z.string().describe('저장할 SQL 쿼리 텍스트'),
        description: z.string().optional().describe('쿼리 설명'),
        datasetId: z.number().optional().describe('주 데이터셋 ID (선택적, cross-dataset 쿼리는 null)'),
        folder: z.string().optional().describe('폴더/카테고리 (예: "주간보고", "모니터링")'),
        isShared: z.boolean().optional().describe('다른 사용자와 공유 여부 (기본 false)'),
      },
      async (args: {
        name: string;
        sqlText: string;
        description?: string;
        datasetId?: number;
        folder?: string;
        isShared?: boolean;
      }) => {
        const result = await apiClient.createSavedQuery(args);
        return jsonResult(result);
      },
    ),

    // 3. 저장된 쿼리 목록
    safeTool(
      'list_saved_queries',
      '저장된 쿼리 목록을 조회합니다.',
      {
        search: z.string().optional().describe('이름/설명 검색어'),
        folder: z.string().optional().describe('폴더 필터 (정확 일치)'),
      },
      async (args: { search?: string; folder?: string }) => {
        const result = await apiClient.listSavedQueries(args);
        return jsonResult(result);
      },
    ),

    // 4. 저장된 쿼리 실행
    safeTool(
      'run_saved_query',
      '저장된 쿼리를 실행하고 결과를 반환합니다.',
      {
        queryId: z.number().describe('실행할 저장된 쿼리 ID'),
      },
      async (args: { queryId: number }) => {
        const result = await apiClient.executeSavedQuery(args.queryId);
        return jsonResult(result);
      },
    ),

    // 5. 스키마 정보 조회 (AI가 쿼리 작성 시 참조)
    safeTool(
      'get_data_schema',
      'data 스키마의 모든 테이블과 컬럼 목록을 반환합니다. SQL 쿼리 작성 시 참조하세요.',
      {},
      async () => {
        const result = await apiClient.getDataSchema();
        return jsonResult(result);
      },
    ),

    // 6. 차트 생성
    safeTool(
      'create_chart',
      '저장된 쿼리를 기반으로 차트를 생성합니다.',
      {
        name: z.string().describe('차트 이름'),
        savedQueryId: z.number().describe('데이터 소스로 사용할 저장된 쿼리 ID'),
        chartType: z
          .enum(['BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE', 'MAP'])
          .describe('차트 유형'),
        config: z.object({
          xAxis: z.string().describe('X축 컬럼명'),
          yAxis: z.array(z.string()).describe('Y축 컬럼명 목록'),
          groupBy: z.string().optional().describe('그룹화 컬럼명 (선택)'),
          stacked: z.boolean().optional().describe('스택형 차트 여부 (선택)'),
          spatialColumn: z.string().optional().describe('GEOMETRY 컬럼명 (MAP 차트 필수)'),
        }),
        description: z.string().optional().describe('차트 설명'),
        isShared: z.boolean().optional().describe('다른 사용자와 공유 여부 (기본 false)'),
      },
      async (args: {
        name: string;
        savedQueryId: number;
        chartType: 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP';
        config: { xAxis: string; yAxis: string[]; groupBy?: string; stacked?: boolean; spatialColumn?: string };
        description?: string;
        isShared?: boolean;
      }) => {
        const result = await apiClient.createChart(args);
        return jsonResult(result);
      },
    ),

    // 7. 차트 목록 조회
    safeTool(
      'list_charts',
      '차트 목록을 조회합니다.',
      {
        search: z.string().optional().describe('이름/설명 검색어'),
      },
      async (args: { search?: string }) => {
        const result = await apiClient.listCharts(args);
        return jsonResult(result);
      },
    ),

    // 8. 차트 데이터 조회
    safeTool(
      'get_chart_data',
      '차트 데이터를 조회합니다. 저장된 쿼리를 재실행하고 차트 설정과 함께 반환합니다.',
      {
        chartId: z.number().describe('데이터를 조회할 차트 ID'),
      },
      async (args: { chartId: number }) => {
        const result = await apiClient.getChartData(args.chartId);
        return jsonResult(result);
      },
    ),

    // 9. 대시보드 생성
    safeTool(
      'create_dashboard',
      '새 대시보드를 생성합니다.',
      {
        name: z.string().describe('대시보드 이름'),
        description: z.string().optional().describe('대시보드 설명'),
        isShared: z.boolean().optional().describe('다른 사용자와 공유 여부 (기본 false)'),
        autoRefreshSeconds: z.number().optional().describe('자동 새로고침 간격(초). null이면 비활성'),
      },
      async (args: { name: string; description?: string; isShared?: boolean; autoRefreshSeconds?: number }) => {
        const result = await apiClient.createDashboard(args);
        return jsonResult(result);
      },
    ),

    // 10. 대시보드에 차트 추가
    safeTool(
      'add_chart_to_dashboard',
      '대시보드에 차트를 추가합니다.',
      {
        dashboardId: z.number().describe('차트를 추가할 대시보드 ID'),
        chartId: z.number().describe('추가할 차트 ID'),
        positionX: z.number().optional().describe('위젯 X 위치 (기본 0)'),
        positionY: z.number().optional().describe('위젯 Y 위치 (기본 0)'),
        width: z.number().optional().describe('위젯 너비 (기본 6)'),
        height: z.number().optional().describe('위젯 높이 (기본 4)'),
      },
      async (args: { dashboardId: number; chartId: number; positionX?: number; positionY?: number; width?: number; height?: number }) => {
        const result = await apiClient.addDashboardWidget(args.dashboardId, {
          chartId: args.chartId,
          positionX: args.positionX ?? 0,
          positionY: args.positionY ?? 0,
          width: args.width ?? 6,
          height: args.height ?? 4,
        });
        return jsonResult(result);
      },
    ),

    // 11. 대시보드 목록
    safeTool(
      'list_dashboards',
      '대시보드 목록을 조회합니다.',
      {
        search: z.string().optional().describe('이름/설명 검색어'),
      },
      async (args: { search?: string }) => {
        const result = await apiClient.listDashboards(args);
        return jsonResult(result);
      },
    ),
  ];
}
