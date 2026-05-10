/**
 * InlineChartWidget 단위 테스트 (이슈 #203)
 *
 * AI 채팅 인라인 차트의 헤더가 분석 제목(title)을 우선 표시하고,
 * title이 없으면 차트 유형명으로 폴백되는지 검증한다.
 *
 * 무겁고 외부 의존성이 많은 ChartRenderer는 vi.mock으로 대체하여
 * 헤더 라벨 렌더링 로직만 격리 테스트한다.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

// ChartRenderer는 nivo/recharts/Maplibre 등 무거운 의존성을 가지므로 단위 테스트에서는 모킹.
vi.mock('../analytics/ChartRenderer', () => ({
  ChartRenderer: () => <div data-testid="chart-renderer-mock" />,
}));

// react-syntax-highlighter는 ESM 동적 임포트 비용이 크므로 모킹.
vi.mock('react-syntax-highlighter/dist/esm/prism-light', () => ({
  default: Object.assign(({ children }: { children: React.ReactNode }) => <pre>{children}</pre>, {
    registerLanguage: vi.fn(),
  }),
}));
vi.mock('react-syntax-highlighter/dist/esm/languages/prism/sql', () => ({ default: {} }));
vi.mock('react-syntax-highlighter/dist/esm/styles/prism', () => ({ oneDark: {} }));

import { InlineChartWidget } from './InlineChartWidget';

const baseProps = {
  sql: 'SELECT a, b FROM data."t"',
  chartType: 'PIE' as const,
  config: { xAxis: 'a', yAxis: ['b'] },
  columns: ['a', 'b'],
  rows: [
    { a: 'x', b: 1 },
    { a: 'y', b: 2 },
    { a: 'z', b: 3 },
    { a: 'w', b: 4 },
    { a: 'v', b: 5 },
  ],
};

describe('InlineChartWidget 헤더 라벨', () => {
  it('title이 전달되면 헤더에 분석 제목을 표시하고 차트 유형·행수는 보조 라벨로 함께 노출한다', () => {
    render(<InlineChartWidget {...baseProps} title="출동 유형별 비율" />);

    // 메인 라벨: 사용자 의도가 드러나는 분석 제목
    expect(screen.getByTestId('inline-chart-title')).toHaveTextContent('출동 유형별 비율');
    // 보조 라벨: 차트 유형명(폴백 정보) + 행수
    expect(screen.getByTestId('inline-chart-sublabel')).toHaveTextContent('파이 차트 · 5건');
  });

  it('title이 없으면 헤더에 차트 유형명을 표시하고 보조 라벨은 행수만 표시한다 (회귀 폴백)', () => {
    render(<InlineChartWidget {...baseProps} />);

    expect(screen.getByTestId('inline-chart-title')).toHaveTextContent('파이 차트');
    expect(screen.getByTestId('inline-chart-sublabel')).toHaveTextContent('5건');
    // 보조 라벨에 차트 유형명이 중복되지 않는다 (title 없을 때는 행수만)
    expect(screen.getByTestId('inline-chart-sublabel').textContent).not.toContain('파이 차트');
  });

  it('title이 빈 문자열·공백이면 차트 유형명으로 폴백한다', () => {
    const { rerender } = render(<InlineChartWidget {...baseProps} title="" />);
    expect(screen.getByTestId('inline-chart-title')).toHaveTextContent('파이 차트');

    rerender(<InlineChartWidget {...baseProps} title="   " />);
    expect(screen.getByTestId('inline-chart-title')).toHaveTextContent('파이 차트');
  });
});

/**
 * SQL 보기 다이얼로그 동작 검증 (#204)
 *
 * 인라인 펼침 영역을 모달로 교체했으므로:
 * - 'SQL 보기' 클릭 시 다이얼로그가 열리고 전체 SQL이 표시되어야 한다
 * - 다이얼로그 외부에는 SQL 코드 블록이 노출되지 않아야 한다 (인라인 펼침 제거)
 * - 복사 버튼이 존재해야 한다
 */
describe('InlineChartWidget SQL 다이얼로그 (#204)', () => {
  const longSql = `WITH base AS (\n  SELECT region, type, COUNT(*) AS cnt\n  FROM data."dispatches"\n  WHERE occurred_at >= '2025-01-01'\n  GROUP BY region, type\n)\nSELECT region, type, cnt FROM base ORDER BY cnt DESC`;

  it('초기 상태에서는 SQL 코드 블록이 표시되지 않는다 (인라인 펼침 제거)', () => {
    render(<InlineChartWidget {...baseProps} sql={longSql} />);
    // 모달이 닫힌 상태에서는 SQL 본문이 DOM에 없거나 보이지 않아야 한다
    expect(screen.queryByTestId('inline-chart-sql-dialog-content')).toBeNull();
    expect(screen.getByTestId('inline-chart-sql-toggle')).toHaveTextContent('SQL 보기');
  });

  it('SQL 보기 클릭 시 다이얼로그가 열리고 전체 SQL과 복사 버튼이 표시된다', async () => {
    const user = userEvent.setup();
    render(<InlineChartWidget {...baseProps} sql={longSql} />);

    await user.click(screen.getByTestId('inline-chart-sql-toggle'));

    const dialogContent = await screen.findByTestId('inline-chart-sql-dialog-content');
    // 전체 SQL이 모달 본문에 들어가 있는지 (CTE 키워드 포함 검증)
    expect(dialogContent.textContent).toContain('WITH base AS');
    expect(dialogContent.textContent).toContain('ORDER BY cnt DESC');

    // 복사 버튼 노출
    expect(screen.getByTestId('inline-chart-sql-copy')).toBeInTheDocument();
  });
});
