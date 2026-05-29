import { z } from 'zod/v4';
import { canvasSchema } from './shared-schemas.js';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

// 17종 차트 타입 상수 — z.enum 세 곳에서 공유하여 중복 방지
const CHART_TYPE_VALUES = [
  'BAR', 'LINE', 'PIE', 'AREA', 'SCATTER', 'DONUT', 'TABLE', 'MAP',
  'HISTOGRAM', 'BOXPLOT', 'HEATMAP', 'TREEMAP', 'FUNNEL', 'RADAR', 'WATERFALL', 'GAUGE', 'CANDLESTICK',
] as const;
type ChartTypeValue = typeof CHART_TYPE_VALUES[number];

// execute_analytics_query 응답 크기 가드 (이슈 #251)
// Claude Agent SDK는 single tool_result에 토큰 한도(약 25K tokens)가 있어 큰 결과는 자동 truncate.
// 1) maxRows 미지정 시 기본값 1000으로 명시 (백엔드 정책과 별개로 tool 레이어에서 cap)
// 2) 응답 직렬화 후 토큰 추정치가 임계치 초과 시 행 배열을 잘라서 LLM에 truncated 메타 제공
//    → LLM이 trial-and-error 재시도 없이 즉시 SUMMARY/AGGREGATE 권유 행동으로 분기 가능
// 3) 회귀 방지(#251 재처리): clamp 측정과 최종 직렬화 형식을 모두 **compact JSON**으로 통일.
// 4) 회귀 방지(#251 2차 재처리): 바이트 단위 임계치만으로는 한국어 등 다바이트 문자에서
//    토큰 한도 초과를 막을 수 없다. 한글 1자(3 UTF-8 bytes)는 약 2-3 토큰을 차지하므로
//    63KB compact JSON이 25K 토큰을 초과한 사례가 발생(trace crosscheck-251r1-s2-biglimit.sse).
//    → 문자(코드포인트) 클래스별 가중치 기반 토큰 추정 + 이진탐색식 retry 루프로 강화.
const ANALYTICS_DEFAULT_MAX_ROWS = 1000;
// SDK single tool_result 토큰 한도는 ~25K. 안전 마진 포함 18K를 운영 한도로 사용한다.
// (한국어 데이터 비중이 높을수록 토큰 효율이 떨어지므로 보수적으로 설정)
const ANALYTICS_RESPONSE_MAX_TOKENS = 18_000;
// 바이트 임계치는 ASCII 위주 데이터에 대한 빠른 1차 게이트 — 토큰 추정보다 저렴하다.
// 한국어 등 다바이트 케이스에서는 토큰 추정이 결정적이므로 바이트 게이트는 보수적으로 30KB.
const ANALYTICS_RESPONSE_MAX_BYTES = 30_000;

/**
 * 직렬화된 문자열의 토큰 수 추정.
 * Claude tokenizer를 직접 호출하지 않고 코드포인트 클래스별 가중치로 보수적 추정한다.
 * - ASCII 인쇄 가능 문자: ≈ 0.25 토큰/char (영문은 약 4 chars/token)
 * - 그 외(한글·한자·일본어·이모지 등 BMP 비-ASCII): ≈ 1.8 토큰/char (한글 1자 ≈ 2-3 토큰)
 * - 서로게이트 페어/이모지: 한 코드포인트로 묶어 약 3 토큰 가산.
 * 실제 BPE 토크나이저보다 약간 후하게(많게) 추정하는 보수적 함수로, 한도 초과를 사전에 차단한다.
 */
export function estimateTokens(text: string): number {
  let ascii = 0;
  let wide = 0;
  let surrogateCodepoints = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff) {
      // high surrogate — 다음 low surrogate와 함께 하나의 코드포인트
      surrogateCodepoints += 1;
      i += 1; // skip low surrogate
      continue;
    }
    if (code < 0x80) ascii += 1;
    else wide += 1;
  }
  // ASCII: 4 chars/token, wide: 0.55 chars/token (≈ 1.8 tokens/char), surrogate: 3 tokens/codepoint
  return Math.ceil(ascii * 0.25 + wide * 1.8 + surrogateCodepoints * 3);
}

interface AnalyticsQueryResult {
  queryType?: string;
  columns?: string[];
  rows?: Array<Record<string, unknown>>;
  affectedRows?: number;
  executionTimeMs?: number;
  totalRows?: number;
  truncated?: boolean;
  error?: string | null;
  [key: string]: unknown;
}

/**
 * 응답이 너무 크면 행 배열을 절단해 token 한도 초과를 방지한다.
 * - 1차 게이트: 직렬화 바이트 길이 vs maxBytes (ASCII 위주 데이터에 효과적, 저렴)
 * - 2차 게이트: 코드포인트 가중치 기반 토큰 추정 vs maxTokens (한국어 등 다바이트 케이스 결정적)
 * - 어느 한쪽이라도 초과하면 행 수를 비율 추정으로 줄이고, 결과를 다시 측정해 양쪽 모두 통과할 때까지
 *   이진탐색식 retry (최대 16회 — O(log n))
 * - truncated/returnedRows/totalRows/hint 메타를 동봉해 LLM에 명시적 신호 전달
 *
 * 회귀 방지(#251 2차): 한국어 데이터 63KB compact JSON이 25K 토큰 초과한 사례 대응 —
 * 바이트만으로 측정하면 한글이 들어간 결과를 차단하지 못한다.
 */
export function clampAnalyticsResult(
  result: AnalyticsQueryResult,
  maxBytes: number = ANALYTICS_RESPONSE_MAX_BYTES,
  maxTokens: number = ANALYTICS_RESPONSE_MAX_TOKENS,
): AnalyticsQueryResult {
  const rows = Array.isArray(result?.rows) ? result.rows : [];
  const originalRowCount = rows.length;
  const serialized = JSON.stringify(result);
  const initialTokens = estimateTokens(serialized);

  // 양쪽 한도 모두 이내면 통과
  if ((serialized.length <= maxBytes && initialTokens <= maxTokens) || originalRowCount === 0) {
    return result;
  }

  // 두 제약 중 더 빡빡한 쪽 기준으로 초기 keepRows를 추정한다.
  // - 바이트 비율: safeBudget(bytes) / avgRowBytes
  // - 토큰 비율: safeTokenBudget / avgRowTokens
  // 둘 중 작은 값을 채택하여 retry 횟수를 줄인다.
  const safeBytesBudget = Math.floor(maxBytes * 0.8);
  const safeTokenBudget = Math.floor(maxTokens * 0.8);
  const avgRowBytes = Math.max(1, Math.floor(serialized.length / originalRowCount));
  const avgRowTokens = Math.max(1, Math.floor(initialTokens / originalRowCount));
  const byBytes = Math.max(1, Math.floor(safeBytesBudget / avgRowBytes));
  const byTokens = Math.max(1, Math.floor(safeTokenBudget / avgRowTokens));
  let keepRows = Math.min(byBytes, byTokens);
  if (keepRows >= originalRowCount) {
    keepRows = Math.max(1, originalRowCount - 1);
  }

  let truncatedRows = rows.slice(0, keepRows);
  let candidate: AnalyticsQueryResult = {
    ...result,
    rows: truncatedRows,
    truncated: true,
    returnedRows: truncatedRows.length,
    totalRows: typeof result.totalRows === 'number' ? result.totalRows : originalRowCount,
    hint: '결과가 큽니다. LIMIT/집계(GROUP BY, COUNT, AVG 등)/조건절을 추가하거나 차트 시각화(show_chart)를 권장합니다.',
  };

  // 양쪽(바이트·토큰) 모두 한도 이하가 될 때까지 행을 절반씩 줄임 (이진탐색식, 최대 16회)
  let safety = 16;
  while (truncatedRows.length > 1 && safety-- > 0) {
    const candidateStr = JSON.stringify(candidate);
    const candidateTokens = estimateTokens(candidateStr);
    if (candidateStr.length <= maxBytes && candidateTokens <= maxTokens) break;
    truncatedRows = truncatedRows.slice(0, Math.max(1, Math.floor(truncatedRows.length / 2)));
    candidate = { ...candidate, rows: truncatedRows, returnedRows: truncatedRows.length };
  }

  return candidate;
}

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
          .describe('최대 반환 행 수 (기본 1000, 최대 10000). 1000을 초과하면 token 한도 초과 위험 — 집계 SQL이나 show_chart 사용을 우선 검토.'),
      },
      async (args: { sql: string; maxRows?: number }) => {
        // 이슈 #251: maxRows 미지정 시 기본값 1000으로 cap (tool 레이어 안전망).
        // LLM이 명시적으로 큰 값을 보낸 경우에만 백엔드 @Max(10000)까지 허용.
        const effectiveMaxRows = args.maxRows ?? ANALYTICS_DEFAULT_MAX_ROWS;
        const result = await apiClient.executeAnalyticsQuery(args.sql, effectiveMaxRows);
        // 응답 직렬화 크기가 임계치 초과면 자동 truncate + 메타 동봉.
        const clamped = clampAnalyticsResult(result as AnalyticsQueryResult);
        // 회귀 방지(#251): jsonResult의 pretty-print는 clamp 측정 형식과 불일치하므로
        // 여기서만 compact 직렬화로 ToolResult를 직접 구성한다 (다른 tool의 pretty-print는 유지).
        return { content: [{ type: 'text' as const, text: JSON.stringify(clamped) }] };
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

    // 5. 스키마 정보 조회 — datasetIds 필수 (refs #267, #272)
    safeTool(
      'get_data_schema',
      '특정 데이터셋들의 테이블·컬럼 정보를 반환합니다. ' +
        '**datasetIds 파라미터는 필수**입니다 — 생략 시 전체 스키마(수십 KB)가 반환되어 ' +
        '컨텍스트 토큰 한도를 초과해 작업 불가. 먼저 `list_datasets` 로 분석 대상 ID를 확인한 뒤 호출하세요. ' +
        'JOIN 분석은 관련된 모든 datasetId 를 한 번에 전달하면 한 응답으로 받습니다.',
      {
        datasetIds: z
          .array(z.number())
          .min(1)
          .describe(
            '조회할 데이터셋 ID 배열 (필수, 1개 이상). 예: 단일 분석 [11], JOIN 분석 [7, 11]',
          ),
      },
      async (args: { datasetIds: number[] }) => {
        const result = await apiClient.getDataSchema(args.datasetIds);
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
        chartType: z.enum(CHART_TYPE_VALUES).describe('차트 유형'),
        config: z.object({
          xAxis: z.string().describe('X축 컬럼명'),
          yAxis: z.array(z.string()).describe('Y축 컬럼명 목록'),
          groupBy: z.string().optional().describe('그룹화 컬럼명 (선택)'),
          stacked: z.boolean().optional().describe('스택형 차트 여부 (선택)'),
          spatialColumn: z.string().optional().describe('GEOMETRY 컬럼명 (MAP 차트 필수)'),
          // 신규 차트 타입용 선택 필드
          bins: z.number().optional().describe('HISTOGRAM: 구간 수 (기본 20)'),
          valueColumn: z.string().optional().describe('HEATMAP: 셀 색상 기준 컬럼명'),
          min: z.number().optional().describe('GAUGE: 최솟값'),
          max: z.number().optional().describe('GAUGE: 최댓값'),
          target: z.number().optional().describe('GAUGE: 목표값'),
          open: z.string().optional().describe('CANDLESTICK: 시가 컬럼명'),
          high: z.string().optional().describe('CANDLESTICK: 고가 컬럼명'),
          low: z.string().optional().describe('CANDLESTICK: 저가 컬럼명'),
          close: z.string().optional().describe('CANDLESTICK: 종가 컬럼명'),
        }),
        description: z.string().optional().describe('차트 설명'),
        isShared: z.boolean().optional().describe('다른 사용자와 공유 여부 (기본 false)'),
      },
      async (args: {
        name: string;
        savedQueryId: number;
        chartType: ChartTypeValue;
        config: { xAxis: string; yAxis: string[]; groupBy?: string; stacked?: boolean; spatialColumn?: string;
          bins?: number; valueColumn?: string; min?: number; max?: number; target?: number;
          open?: string; high?: string; low?: string; close?: string };
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

    // 12. 채팅 인라인 차트 표시 (프론트엔드 전용, 백엔드 호출 없음)
    safeTool(
      'show_chart',
      '채팅에 인라인 차트를 표시합니다. execute_analytics_query로 조회한 데이터를 차트로 시각화할 때 사용합니다.',
      {
        sql: z.string().describe('차트 데이터를 조회한 SQL 쿼리 (참조용 표시)'),
        title: z.string().optional().describe('차트 헤더에 표시할 분석 제목 (예: "출동 유형별 비율"). 미전달 시 차트 유형명으로 폴백.'),
        chartType: z.enum(CHART_TYPE_VALUES).describe('차트 유형'),
        config: z.object({
          xAxis: z.string().describe('X축 컬럼명'),
          yAxis: z.array(z.string()).describe('Y축 컬럼명 목록'),
          groupBy: z.string().optional().describe('그룹화 컬럼명'),
          stacked: z.boolean().optional().describe('스택형 차트 여부'),
          spatialColumn: z.string().optional().describe('GEOMETRY 컬럼명 (MAP 차트 필수)'),
          // 신규 차트 타입용 선택 필드
          bins: z.number().optional().describe('HISTOGRAM: 구간 수 (기본 20)'),
          valueColumn: z.string().optional().describe('HEATMAP: 셀 색상 기준 컬럼명'),
          min: z.number().optional().describe('GAUGE: 최솟값'),
          max: z.number().optional().describe('GAUGE: 최댓값'),
          target: z.number().optional().describe('GAUGE: 목표값'),
          open: z.string().optional().describe('CANDLESTICK: 시가 컬럼명'),
          high: z.string().optional().describe('CANDLESTICK: 고가 컬럼명'),
          low: z.string().optional().describe('CANDLESTICK: 저가 컬럼명'),
          close: z.string().optional().describe('CANDLESTICK: 종가 컬럼명'),
        }),
        columns: z.array(z.string()).describe('결과 컬럼 목록'),
        rows: z.array(z.record(z.string(), z.unknown())).max(2000, '최대 2000행까지 지원합니다').describe('결과 데이터 행 배열 (최대 2000행)'),
        canvas: canvasSchema,
      },
      async (args: {
        sql: string;
        title?: string;
        chartType: string;
        config: { xAxis: string; yAxis: string[]; groupBy?: string; stacked?: boolean; spatialColumn?: string;
          bins?: number; valueColumn?: string; min?: number; max?: number; target?: number;
          open?: string; high?: string; low?: string; close?: string };
        columns: string[];
        rows: Record<string, unknown>[];
      }) => {
        // 프론트엔드에서 tool_use 이벤트의 input으로 직접 렌더링.
        // 명시적 Zod 검증 수행 (SDK가 우회될 경우를 위한 안전망)
        const chartTypeSchema = z.enum(CHART_TYPE_VALUES);
        chartTypeSchema.parse(args.chartType);
        return jsonResult({
          displayed: true,
          chartType: args.chartType,
          rowCount: args.rows.length,
          title: args.title,
        });
      },
    ),
  ];
}
