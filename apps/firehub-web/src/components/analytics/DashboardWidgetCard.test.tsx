/**
 * DashboardWidgetCard 단위 테스트 (#764 회귀 방지)
 *
 * 저장 쿼리가 실패하면 API는 HTTP 200 + `queryResult.error` 로 사유를 준다.
 * 위젯은 이 사유를 "데이터가 없습니다."(정상 0행 안내)로 뭉개지 말고 오류 상태로 보여야 한다.
 * 단건(useChartData) 폴백 경로와 배치(batchData) 경로 모두 검증한다.
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Chart, ChartDataResponse, DashboardWidget, WidgetData } from '../../types/analytics';

// 차트 메타·단건 데이터 훅을 모킹해 위젯 본문 분기만 검증한다
const useChartMock = vi.fn();
const useChartDataMock = vi.fn();
vi.mock('../../hooks/queries/useAnalytics', () => ({
  useChart: (...args: unknown[]) => useChartMock(...args),
  useChartData: (...args: unknown[]) => useChartDataMock(...args),
}));
vi.mock('../../hooks/useWidgetVisibility', () => ({ useWidgetVisibility: () => true }));
// 차트 렌더러는 rows 수만 노출하는 스텁으로 대체 — "빈 결과 → 데이터가 없습니다." 문구만 흉내
vi.mock('./ChartRenderer', () => ({
  ChartRenderer: ({ data }: { data: unknown[] }) => (
    <div data-testid="chart-renderer">{data.length === 0 ? '데이터가 없습니다.' : `rows:${data.length}`}</div>
  ),
}));

import { DashboardWidgetCard } from './DashboardWidgetCard';

const DIV_ZERO = 'ERROR: division by zero\nSQLState: 22012';

const chart: Chart = {
  id: 1,
  name: '차트',
  description: null,
  savedQueryId: 1,
  savedQueryName: '쿼리',
  chartType: 'BAR',
  config: { xAxis: 'cat', yAxis: ['v'] },
  isShared: false,
  createdByName: 'u',
  createdBy: 1,
  createdAt: '2026-10-01T00:00:00',
  updatedAt: '2026-10-01T00:00:00',
} as Chart;

const widget: DashboardWidget = {
  id: 1,
  chartId: 1,
  chartName: '위젯',
  chartType: 'BAR',
  positionX: 0,
  positionY: 0,
  width: 6,
  height: 4,
};

function queryResult(overrides: Partial<ChartDataResponse['queryResult']> = {}) {
  return {
    queryType: 'SELECT',
    columns: ['cat', 'v'],
    rows: [],
    affectedRows: 0,
    executionTimeMs: 1,
    totalRows: 0,
    truncated: false,
    error: null,
    ...overrides,
  };
}

describe('DashboardWidgetCard — queryResult.error 표시 (#764)', () => {
  beforeEach(() => {
    useChartMock.mockReturnValue({ data: chart, isLoading: false });
    useChartDataMock.mockReturnValue({ data: undefined, isLoading: false, isFetching: false });
  });

  it('단건 폴백 경로에서 queryResult.error 가 있으면 사유를 오류 상태로 표시한다', () => {
    useChartDataMock.mockReturnValue({
      data: { chart, queryResult: queryResult({ columns: [], error: DIV_ZERO }) },
      isLoading: false,
      isFetching: false,
    });
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('쿼리 오류');
    expect(alert).toHaveTextContent('ERROR: division by zero');
    expect(alert).toHaveTextContent('SQLState: 22012');
    expect(screen.queryByText('데이터가 없습니다.')).not.toBeInTheDocument();
    expect(screen.queryByTestId('chart-renderer')).not.toBeInTheDocument();
  });

  it('배치 경로에서 queryResult.error 가 있으면 사유를 오류 상태로 표시한다', () => {
    const batchData: WidgetData = {
      widgetId: 1,
      chartId: 1,
      queryResult: queryResult({ columns: [], error: '결과가 너무 큽니다(응답 한도 32MB 초과)' }),
    };
    render(<DashboardWidgetCard widget={widget} batchData={batchData} isEditing={false} />);

    expect(screen.getByRole('alert')).toHaveTextContent('결과가 너무 큽니다(응답 한도 32MB 초과)');
    expect(screen.queryByText('데이터가 없습니다.')).not.toBeInTheDocument();
  });

  it('정상 0행 결과는 오류 없이 빈 결과 안내를 그대로 표시한다', () => {
    useChartDataMock.mockReturnValue({
      data: { chart, queryResult: queryResult() },
      isLoading: false,
      isFetching: false,
    });
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    expect(screen.getByText('데이터가 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('쿼리 오류')).not.toBeInTheDocument();
  });
});
