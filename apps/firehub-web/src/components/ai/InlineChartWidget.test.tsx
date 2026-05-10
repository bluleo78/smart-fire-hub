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
