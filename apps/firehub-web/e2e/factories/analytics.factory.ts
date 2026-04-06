/**
 * 분석(Analytics) 도메인 모킹 데이터 팩토리
 * src/types/analytics.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type {
  AnalyticsQueryResult,
  Chart,
  ChartListItem,
  Dashboard,
  DashboardListItem,
  DashboardWidget,
  SavedQuery,
  SavedQueryListItem,
  SchemaInfo,
} from '@/types/analytics';

/** 저장된 쿼리(SavedQuery) 전체 응답 객체 생성 */
export function createSavedQuery(overrides?: Partial<SavedQuery>): SavedQuery {
  return {
    id: 1,
    name: '테스트 쿼리',
    description: '테스트용 저장 쿼리',
    sqlText: 'SELECT * FROM test_table LIMIT 100',
    datasetId: 1,
    datasetName: '테스트 데이터셋',
    folder: null,
    isShared: false,
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    chartCount: 0,
    ...overrides,
  };
}

/** 저장된 쿼리 목록 아이템 객체 생성 */
export function createSavedQueryListItem(overrides?: Partial<SavedQueryListItem>): SavedQueryListItem {
  return {
    id: 1,
    name: '테스트 쿼리',
    description: '테스트용 저장 쿼리',
    folder: null,
    datasetId: 1,
    datasetName: '테스트 데이터셋',
    isShared: false,
    createdByName: '테스트 사용자',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    chartCount: 0,
    ...overrides,
  };
}

/** SQL 쿼리 실행 결과 객체 생성 */
export function createQueryResult(overrides?: Partial<AnalyticsQueryResult>): AnalyticsQueryResult {
  return {
    queryType: 'SELECT',
    columns: ['id', 'name', 'value'],
    rows: [
      { id: 1, name: '항목 1', value: 100 },
      { id: 2, name: '항목 2', value: 200 },
    ],
    affectedRows: 0,
    executionTimeMs: 42,
    totalRows: 2,
    truncated: false,
    error: null,
    ...overrides,
  };
}

/** 차트(Chart) 전체 응답 객체 생성 */
export function createChart(overrides?: Partial<Chart>): Chart {
  return {
    id: 1,
    name: '테스트 차트',
    description: '테스트용 막대 차트',
    savedQueryId: 1,
    savedQueryName: '테스트 쿼리',
    chartType: 'BAR',
    config: {
      xAxis: 'name',
      yAxis: ['value'],
    },
    isShared: false,
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 차트 목록 아이템 객체 생성 */
export function createChartListItem(overrides?: Partial<ChartListItem>): ChartListItem {
  return {
    id: 1,
    name: '테스트 차트',
    description: '테스트용 막대 차트',
    savedQueryId: 1,
    savedQueryName: '테스트 쿼리',
    chartType: 'BAR',
    isShared: false,
    createdByName: '테스트 사용자',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 대시보드 위젯 객체 생성 */
export function createWidget(overrides?: Partial<DashboardWidget>): DashboardWidget {
  return {
    id: 1,
    chartId: 1,
    chartName: '테스트 차트',
    chartType: 'BAR',
    positionX: 0,
    positionY: 0,
    width: 6,
    height: 4,
    ...overrides,
  };
}

/** 위젯 목록을 포함한 대시보드 전체 응답 객체 생성 */
export function createDashboard(overrides?: Partial<Dashboard>): Dashboard {
  return {
    id: 1,
    name: '테스트 대시보드',
    description: '테스트용 대시보드',
    isShared: false,
    autoRefreshSeconds: null,
    widgets: [createWidget()],
    createdByName: '테스트 사용자',
    createdBy: 1,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 대시보드 목록 아이템 객체 생성 */
export function createDashboardListItem(overrides?: Partial<DashboardListItem>): DashboardListItem {
  return {
    id: 1,
    name: '테스트 대시보드',
    description: '테스트용 대시보드',
    isShared: false,
    autoRefreshSeconds: null,
    widgetCount: 1,
    createdByName: '테스트 사용자',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 스키마 정보 객체 생성 (테이블 2개 포함) */
export function createSchemaInfo(): SchemaInfo {
  return {
    tables: [
      {
        tableName: 'test_table',
        datasetName: '테스트 데이터셋',
        datasetId: 1,
        columns: [
          { columnName: 'id', dataType: 'INTEGER', displayName: 'ID' },
          { columnName: 'name', dataType: 'TEXT', displayName: '이름' },
        ],
      },
      {
        tableName: 'another_table',
        datasetName: '다른 데이터셋',
        datasetId: 2,
        columns: [
          { columnName: 'id', dataType: 'INTEGER', displayName: 'ID' },
          { columnName: 'value', dataType: 'DECIMAL', displayName: '값' },
        ],
      },
    ],
  };
}

/** SavedQueryListItem 여러 개를 한 번에 생성 */
export function createSavedQueryList(count: number): SavedQueryListItem[] {
  return Array.from({ length: count }, (_, i) =>
    createSavedQueryListItem({
      id: i + 1,
      name: `저장 쿼리 ${i + 1}`,
    }),
  );
}
