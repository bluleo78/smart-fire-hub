// ============================================================
// Phase 1: Saved Queries
// ============================================================

export interface SavedQuery {
  id: number;
  name: string;
  description: string | null;
  sqlText: string;
  datasetId: number | null;
  datasetName: string | null;
  folder: string | null;
  isShared: boolean;
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
  chartCount: number;
}

export interface SavedQueryListItem {
  id: number;
  name: string;
  description: string | null;
  folder: string | null;
  datasetId: number | null;
  datasetName: string | null;
  isShared: boolean;
  createdByName: string;
  /** 작성자 id — 현재 사용자와 비교해 남의 공유 항목 줄에서 편집·삭제 버튼을 숨긴다(WD-196, useIsOwner). */
  createdBy: number;
  createdAt: string;
  updatedAt: string;
  chartCount: number;
}

export interface CreateSavedQueryRequest {
  name: string;
  description?: string;
  sqlText: string;
  datasetId?: number | null;
  folder?: string | null;
  isShared: boolean;
}

export interface UpdateSavedQueryRequest {
  name?: string;
  description?: string;
  sqlText?: string;
  datasetId?: number | null;
  folder?: string | null;
  isShared?: boolean;
  /**
   * 편집을 시작할 때 읽은 상세의 updatedAt(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT.
   * 서버가 준 문자열을 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). 빼면 비교하지 않는다(덮어쓰기).
   */
  expectedUpdatedAt?: string | null;
}

export interface AnalyticsQueryRequest {
  sql: string;
  maxRows?: number;
}

export interface AnalyticsQueryResult {
  queryType: string;
  columns: string[];
  rows: Record<string, unknown>[];
  affectedRows: number;
  executionTimeMs: number;
  totalRows: number;
  truncated: boolean;
  error: string | null;
  /**
   * 조회자 기준 내보내기 가능(S4). 애드혹·저장 쿼리 실행 응답에만 실린다 — 대시보드 공유 캐시 결과는 null.
   * `=== true` 일 때만 허용한다(fail-closed).
   */
  exportAllowed?: boolean | null;
  /** 애드혹 실행 기록 id — 쿼리 결과 내보내기는 이 id 로 서버가 재판정·재실행한다. 저장 쿼리 실행 등은 null. */
  runId?: string | null;
  /**
   * 결과 표 전용 원문(WD-105) — 행 인덱스 → { 컬럼명: 원문 텍스트 }. rows 의 NUMERIC·BIGINT 는 차트·대시보드가 숫자로 쓰므로
   * JSON 숫자로 오는데, 그중 브라우저 double 로 깎이는 칸(예: 123456789012.123456, 9007199254740993)만 원문이 여기 실린다.
   * 애드혹·저장 쿼리 실행 응답에만 있고, 깎이는 칸이 없으면 생략된다. 차트·대시보드 경로는 쓰지 않는다.
   */
  exactValues?: Record<string, Record<string, string>>;
}

export interface SchemaTable {
  tableName: string;
  datasetName: string | null;
  datasetId: number | null;
  columns: SchemaColumn[];
}

export interface SchemaColumn {
  columnName: string;
  dataType: string;
  displayName: string | null;
}

export interface SchemaInfo {
  tables: SchemaTable[];
  /** 테넌트 분석 저장소 방언 — 없으면 PostgreSQL 로 본다(WD-76) */
  dialect?: 'POSTGRESQL' | 'TIBERO';
  /** 테이블을 한정할 스키마명(Tibero 는 `schema.table` 로 참조) */
  schema?: string;
}

// ============================================================
// Phase 2: Charts
// ============================================================

export type ChartType =
  | 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP'
  | 'HISTOGRAM' | 'BOXPLOT' | 'HEATMAP' | 'TREEMAP' | 'FUNNEL'
  | 'RADAR' | 'WATERFALL' | 'GAUGE' | 'CANDLESTICK';

/**
 * 대시보드 자동 새로고침 주기 상한(초) — 24시간(WD-327, 서버 CreateDashboardRequest.MAX_AUTO_REFRESH_SECONDS 와 같다).
 * 상한이 없으면 약 24.8일(2^31 ms)을 넘는 값에서 브라우저 타이머 지연이 32비트로 감겨 0 이 되어, 위젯 데이터를 쉬지 않고 다시 요청했다.
 */
export const MAX_AUTO_REFRESH_SECONDS = 86_400;

/** 차트 타입 → 한국어 레이블 매핑. 단일 원본으로 InlineChartWidget, ChartListPage 등에서 공유. */
export const CHART_TYPE_LABELS: Record<ChartType, string> = {
  BAR: '막대 차트', LINE: '꺾은선 차트', AREA: '영역 차트',
  PIE: '파이 차트', DONUT: '도넛 차트', SCATTER: '산점도',
  TABLE: '테이블', MAP: '지도',
  HISTOGRAM: '히스토그램', BOXPLOT: '박스플롯', HEATMAP: '히트맵',
  TREEMAP: '트리맵', FUNNEL: '퍼널', RADAR: '레이더',
  WATERFALL: '워터폴', GAUGE: '게이지', CANDLESTICK: '캔들스틱',
};

export interface ChartConfig {
  xAxis: string;
  yAxis: string[];
  groupBy?: string;
  colors?: string[];
  showLegend?: boolean;
  showGrid?: boolean;
  xAxisLabel?: string;
  yAxisLabel?: string;
  stacked?: boolean;
  spatialColumn?: string;    // MAP 차트: GEOMETRY 컬럼명 (필수)
  colorByColumn?: string;    // MAP 차트: 색상 기준 컬럼 (선택, points 모드 전용)

  // MAP 차트 표시 모드 — 'points' (기존 점/폴리곤) vs 'heatmap' (좌표 밀도). 기본 'points' (#119)
  mapDisplayMode?: 'points' | 'heatmap';
  // MAP+heatmap 전용 가중치 컬럼 (numeric, 선택). 미지정 시 균등 가중치 1. (#119)
  weightColumn?: string;
  // HISTOGRAM: 구간 수 (기본 20)
  bins?: number;
  // HEATMAP: 셀 색상 기준 컬럼 (xAxis=행, yAxis[0]=열)
  valueColumn?: string;
  // GAUGE: 값 범위 및 목표
  min?: number;
  max?: number;
  target?: number;
  // CANDLESTICK: 시가/고가/저가/종가 컬럼명
  open?: string;
  high?: string;
  low?: string;
  close?: string;
}

export interface Chart {
  id: number;
  name: string;
  description: string | null;
  savedQueryId: number;
  /** 조회자가 원본 데이터셋을 볼 수 없어 denied 인 차트 데이터 응답(`ChartDataResponse.chart`)에서는 null — 원본 메타 비노출(보안 등급) */
  savedQueryName: string | null;
  chartType: ChartType;
  /** 조회자가 저장 쿼리의 데이터를 볼 수 없으면 null — configWithheld 가 true 다(WD-31②). */
  config: ChartConfig | null;
  /** 서버가 설정을 가렸는가. true 면 빌더는 편집을 잠그고 저장 시 config 를 보내지 않는다(기존 값 유지). */
  configWithheld?: boolean;
  isShared: boolean;
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

/** 삭제 영향 범위 항목 — 조회자가 볼 수 있는 차트·대시보드의 id 와 이름(WD-86). */
/**
 * 대시보드 공유를 켜거나 끌 때 함께 영향받는 내 차트·저장 쿼리 개수(WD-325, GET /dashboards/{id}/share-impact).
 * 공유를 켜면 위젯이 쓰는 내 비공개 차트·쿼리가 함께 공개되고, 꺼도 되돌리지 않는다 — 설정 창이 그 사실을 개수와 함께 안내한다.
 */
export interface DashboardShareImpact {
  /** 공유를 켜면 새로 공개되는 내 차트 수 */
  privateChartCount: number;
  /** 공유를 켜면 새로 공개되는 내 저장 쿼리 수 */
  privateQueryCount: number;
  /** 공유를 꺼도 계속 공개로 남는 내 차트 수 */
  sharedChartCount: number;
  /** 공유를 꺼도 계속 공개로 남는 내 저장 쿼리 수 */
  sharedQueryCount: number;
}

export interface DeleteImpactItem {
  id: number;
  name: string;
}

/**
 * 저장 쿼리·차트 삭제 시 DB CASCADE 로 함께 사라지는 항목(WD-86). 개수는 전부 세고, 이름은 조회자가 볼 수 있는 것(본인 소유 또는
 * 공유)만 온다 — 볼 수 없는 것은 hidden*Count 로 개수만. 차트 삭제 영향에서는 charts 가 항상 비어 있다.
 */
export interface DeleteImpact {
  charts: DeleteImpactItem[];
  hiddenChartCount: number;
  widgetCount: number;
  dashboards: DeleteImpactItem[];
  hiddenDashboardCount: number;
}

/**
 * 워크스페이스 분석 자산 관리(WD-199, analytics:admin)의 자산 종류 — 관리자 API 경로 조각과 같다.
 * 대시보드·차트·저장 쿼리 목록 화면이 「워크스페이스 전체」 보기에서 같은 표 컴포넌트를 쓴다.
 */
export type AnalyticsAdminKind = 'dashboards' | 'charts' | 'queries';

/**
 * 워크스페이스 전체 분석 자산 목록의 행 (WD-199, analytics:admin 전용).
 * 남의 자산 내용(SQL·차트 설정·위젯·설명)은 서버가 싣지 않는다 — 관리자는 공유 해제·삭제만 할 수 있다.
 */
export interface AnalyticsAdminItem {
  id: number;
  name: string;
  ownerId: number;
  /** 작성자 이름 — 계정이 지워졌으면 null */
  ownerName: string | null;
  /** 작성자가 지금 이 워크스페이스의 활성 멤버인가 — 계정 비활성·정지·제거면 false */
  ownerActive: boolean;
  isShared: boolean;
  updatedAt: string;
}

export interface ChartListItem {
  id: number;
  name: string;
  description: string | null;
  savedQueryId: number;
  savedQueryName: string;
  chartType: ChartType;
  /** 서버가 설정을 가린 차트인가(WD-31②) — 목록에는 config 가 없어 표시용 플래그만 둔다. */
  configWithheld?: boolean;
  isShared: boolean;
  createdByName: string;
  /** 작성자 id — 현재 사용자와 비교해 남의 공유 항목 줄에서 편집·삭제 버튼을 숨긴다(WD-196, useIsOwner). */
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChartRequest {
  name: string;
  description?: string;
  savedQueryId: number;
  chartType: ChartType;
  config: ChartConfig;
  isShared: boolean;
}

export interface UpdateChartRequest {
  name?: string;
  description?: string;
  chartType?: ChartType;
  config?: ChartConfig;
  isShared?: boolean;
  /**
   * 편집을 시작할 때 읽은 상세의 updatedAt(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT.
   * 서버가 준 문자열을 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). 빼면 비교하지 않는다(덮어쓰기).
   */
  expectedUpdatedAt?: string | null;
}

export interface ChartDataResponse {
  /** 데이터 응답의 chart 는 config 를 항상 객체로 준다(denied 면 빈 객체) — 단건 조회의 null 가림과 타입을 나눈다(WD-31②). */
  chart: Omit<Chart, 'config' | 'configWithheld'> & { config: ChartConfig };
  queryResult: AnalyticsQueryResult;
  /**
   * 조회자가 원본 데이터셋을 볼 수 없음 — 위젯은 "원본 데이터를 볼 수 없음" 잠금 상태를 그린다(스펙 §5-4).
   * 권한 부족과 원본 데이터셋 삭제를 구분하지 않는다(서버 SQL 관문의 존재 은닉, 스펙 §2.5 — WD-143).
   * true 면 서버가 chart 를 최소 메타로 준다: config 는 빈 객체(원본 컬럼명 비노출), savedQueryName 은 null.
   * 그래서 denied 분기는 chart.config 를 읽지 않고 먼저 반환해야 한다(DashboardWidgetCard).
   */
  denied?: boolean;
  /** 조회자 기준 차트 데이터 내보내기 가능 — 공유 캐시와 무관하게 매 요청 계산된다(S4). 없으면 불가로 본다. */
  exportAllowed?: boolean;
}

// ============================================================
// Phase 3: Dashboards
// ============================================================

export interface Dashboard {
  id: number;
  name: string;
  description: string | null;
  isShared: boolean;
  autoRefreshSeconds: number | null;
  widgets: DashboardWidget[];
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardListItem {
  id: number;
  name: string;
  description: string | null;
  isShared: boolean;
  autoRefreshSeconds: number | null;
  widgetCount: number;
  createdByName: string;
  /** 작성자 id — 현재 사용자와 비교해 남의 공유 항목 줄에서 편집·삭제 버튼을 숨긴다(WD-196, useIsOwner). */
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardWidget {
  id: number;
  chartId: number;
  /** 조회자가 볼 수 없는 차트(accessible=false)면 서버가 이름을 싣지 않는다(null) — WD-195 */
  chartName: string | null;
  /** 조회자가 볼 수 없는 차트면 null — WD-195 */
  chartType: ChartType | null;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  /**
   * 조회자가 이 위젯의 차트를 볼 수 있는지(본인 소유 또는 공유) — WD-195.
   * false 면 차트 이름·종류·데이터를 볼 수 없고 자리(위치·크기)만 남는다.
   * 보안 등급으로 원본 데이터를 못 보는 경우(차트 데이터의 denied)와는 다른 축이다 — 그 위젯은 accessible=true 다.
   */
  accessible: boolean;
}

export interface CreateDashboardRequest {
  name: string;
  description?: string;
  isShared: boolean;
  autoRefreshSeconds?: number | null;
}

export interface UpdateDashboardRequest {
  name?: string;
  description?: string;
  isShared?: boolean;
  autoRefreshSeconds?: number | null;
  /** autoRefreshSeconds가 null일 때 "값 미제공"이 아니라 "명시적으로 지움(수동 전환)"임을 알리는 플래그 (#568) */
  clearAutoRefresh?: boolean;
  /**
   * 편집을 시작할 때 읽은 상세의 updatedAt(WD-364 낙관적 잠금) — 서버 값과 다르면 409 EDIT_CONFLICT.
   * 서버가 준 문자열을 그대로 보낸다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). 빼면 비교하지 않는다(덮어쓰기).
   */
  expectedUpdatedAt?: string | null;
}

export interface AddWidgetRequest {
  chartId: number;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
}

export interface UpdateWidgetRequest {
  positionX?: number;
  positionY?: number;
  width?: number;
  height?: number;
}

// ============================================================
// Phase 4: Dashboard Batch Data
// ============================================================

/**
 * `GET /analytics/dashboards/{id}/data` 의 실제 API 계약 (#778).
 * API: `DashboardDataResponse(DashboardResponse dashboard, List<WidgetData> widgetData)`,
 * `WidgetData(Long widgetId, ChartDataResponse chartData)` 와 1:1 로 맞춘다.
 * 과거 프론트 타입(`dashboardId`/`widgets[].queryResult`)이 실제 응답과 달라 일괄 응답이 한 번도 쓰이지 못했다.
 * 현재 대시보드 화면은 위젯별 `/charts/{id}/data` 로 데이터를 가져오므로 이 엔드포인트를 호출하지 않는다.
 */
export interface WidgetData {
  widgetId: number;
  chartData: ChartDataResponse;
}

export interface DashboardDataResponse {
  dashboard: Dashboard;
  widgetData: WidgetData[];
}
