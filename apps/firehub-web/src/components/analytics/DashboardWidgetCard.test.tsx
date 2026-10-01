/**
 * DashboardWidgetCard 단위 테스트 (#764 회귀 방지)
 *
 * 저장 쿼리가 실패하면 API는 HTTP 200 + `queryResult.error` 로 사유를 준다.
 * 위젯은 이 사유를 "데이터가 없습니다."(정상 0행 안내)로 뭉개지 말고 오류 상태로 보여야 한다.
 *
 * #778: 위젯은 자기 단건 쿼리(useChartData) 하나로 표시 데이터·신선도 바·새로고침을 모두 처리한다.
 * 신선도 바의 실패 배지는 이 위젯 쿼리의 isError 만 따르고, 새로고침 버튼은 이 위젯 쿼리의 refetch 를 부른다.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Chart, ChartDataResponse, DashboardWidget } from '../../types/analytics';

// 차트 메타·단건 데이터 훅을 모킹해 위젯 본문 분기만 검증한다
const useChartMock = vi.fn();
const useChartDataMock = vi.fn();
vi.mock('../../hooks/queries/useAnalytics', () => ({
  useChart: (...args: unknown[]) => useChartMock(...args),
  useChartData: (...args: unknown[]) => useChartDataMock(...args),
}));
vi.mock('../../hooks/useWidgetVisibility', () => ({ useWidgetVisibility: () => true }));
// 수동 새로고침 실패 토스트 호출 여부만 확인
const handleApiErrorMock = vi.fn();
vi.mock('../../lib/api-error', () => ({
  handleApiError: (...args: unknown[]) => handleApiErrorMock(...args),
}));
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

/** useChartData 반환값 스텁 — 카드가 쓰는 필드(data·상태·dataUpdatedAt·refetch)를 모두 채운다 */
function chartDataState(overrides: Record<string, unknown> = {}) {
  return {
    data: undefined,
    isLoading: false,
    isFetching: false,
    isError: false,
    dataUpdatedAt: Date.now(),
    refetch: vi.fn().mockResolvedValue({ isError: false }),
    ...overrides,
  };
}

describe('DashboardWidgetCard — queryResult.error 표시 (#764)', () => {
  beforeEach(() => {
    useChartMock.mockReturnValue({ data: chart, isLoading: false });
    useChartDataMock.mockReturnValue(chartDataState());
  });

  it('queryResult.error 가 있으면 사유를 오류 상태로 표시한다', () => {
    useChartDataMock.mockReturnValue(
      chartDataState({ data: { chart, queryResult: queryResult({ columns: [], error: DIV_ZERO }) } }),
    );
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('쿼리 오류');
    expect(alert).toHaveTextContent('ERROR: division by zero');
    expect(alert).toHaveTextContent('SQLState: 22012');
    expect(screen.queryByText('데이터가 없습니다.')).not.toBeInTheDocument();
    expect(screen.queryByTestId('chart-renderer')).not.toBeInTheDocument();
  });

  it('정상 0행 결과는 오류 없이 빈 결과 안내를 그대로 표시한다', () => {
    useChartDataMock.mockReturnValue(chartDataState({ data: { chart, queryResult: queryResult() } }));
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    expect(screen.getByText('데이터가 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('쿼리 오류')).not.toBeInTheDocument();
  });
});

describe('DashboardWidgetCard — 위젯 단위 신선도·새로고침 (#778)', () => {
  beforeEach(() => {
    useChartMock.mockReturnValue({ data: chart, isLoading: false });
    handleApiErrorMock.mockReset();
  });

  it('이 위젯의 단건 쿼리를 대시보드 자동 새로고침 주기로 조회한다', () => {
    useChartDataMock.mockReturnValue(chartDataState({ data: { chart, queryResult: queryResult() } }));
    render(<DashboardWidgetCard widget={widget} isEditing={false} autoRefreshSeconds={30} />);

    expect(useChartDataMock).toHaveBeenCalledWith(1, { refetchInterval: 30_000, enabled: true });
  });

  it('새로고침 버튼은 이 위젯 쿼리의 refetch 를 부르고, 성공하면 토스트를 띄우지 않는다', async () => {
    const refetch = vi.fn().mockResolvedValue({ isError: false });
    useChartDataMock.mockReturnValue(
      chartDataState({ data: { chart, queryResult: queryResult() }, refetch }),
    );
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    fireEvent.click(screen.getByTitle('새로고침'));
    await vi.waitFor(() => expect(refetch).toHaveBeenCalledTimes(1));
    expect(handleApiErrorMock).not.toHaveBeenCalled();
  });

  it('새로고침이 실패하면 1회 토스트로 알린다', async () => {
    const error = new Error('boom');
    const refetch = vi.fn().mockResolvedValue({ isError: true, error });
    useChartDataMock.mockReturnValue(
      chartDataState({ data: { chart, queryResult: queryResult() }, refetch }),
    );
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);

    fireEvent.click(screen.getByTitle('새로고침'));
    await vi.waitFor(() => expect(handleApiErrorMock).toHaveBeenCalledTimes(1));
    expect(handleApiErrorMock).toHaveBeenCalledWith(error, '위젯 데이터를 불러오지 못했습니다.');
  });

  it('신선도 바 실패 배지는 이 위젯 쿼리의 isError 만 따른다', () => {
    useChartDataMock.mockReturnValue(
      chartDataState({ data: { chart, queryResult: queryResult() }, isError: false }),
    );
    const { unmount } = render(<DashboardWidgetCard widget={widget} isEditing={false} />);
    expect(screen.queryByText('새로고침 실패')).not.toBeInTheDocument();
    unmount();

    useChartDataMock.mockReturnValue(
      chartDataState({ data: { chart, queryResult: queryResult() }, isError: true }),
    );
    render(<DashboardWidgetCard widget={widget} isEditing={false} />);
    expect(screen.getByText('새로고침 실패')).toBeInTheDocument();
  });
});
