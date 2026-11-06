/**
 * ChartRenderer 컬럼 누락 안내 단위 테스트 (WD-88 회귀 방지)
 *
 * 설정 컬럼이 쿼리 결과에 없으면 차트를 그리지 않고 안내를 보여야 한다. 0행이어도 컬럼 안내가 우선이고,
 * columns 가 비었거나(가림) 설정이 비어 자동 추론하는 경우에는 기존 동작을 유지해야 한다.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// 막대 차트는 recharts 대신 받은 축만 노출하는 스텁으로 대체 — "차트가 그려졌는가"만 본다
vi.mock('./BarChartView', () => ({
  BarChartView: ({ config }: { config: { xAxis: string; yAxis: string[] } }) => (
    <div data-testid="bar-chart">{`${config.xAxis}|${config.yAxis.join(',')}`}</div>
  ),
}));

import type { ChartConfig } from '../../types/analytics';
import { ChartRenderer } from './ChartRenderer';

const ROWS = [{ area: '서울', sum: 10 }];

describe('ChartRenderer — 컬럼 누락 안내 (WD-88)', () => {
  it('설정 컬럼이 결과에 없으면 차트 대신 없는 컬럼 목록과 안내를 보여 준다', () => {
    render(
      <ChartRenderer chartType="BAR" config={{ xAxis: 'region', yAxis: ['total'] }} data={ROWS} columns={['area', 'sum']} />,
    );
    const notice = screen.getByTestId('chart-missing-columns');
    expect(notice).toHaveTextContent('차트에 쓰는 컬럼이 쿼리 결과에 없습니다');
    expect(notice).toHaveTextContent('원본 쿼리의 컬럼 이름이 바뀌었을 수 있습니다. 차트 편집에서 축을 다시 고르세요.');
    expect(Array.from(notice.querySelectorAll('code')).map((c) => c.textContent)).toEqual(['region', 'total']);
    expect(screen.queryByTestId('bar-chart')).toBeNull();
  });

  it('0행이어도 컬럼 이름이 틀렸으면 「데이터가 없습니다」보다 컬럼 안내가 우선한다', () => {
    render(<ChartRenderer chartType="BAR" config={{ xAxis: 'region', yAxis: ['sum'] }} data={[]} columns={['area', 'sum']} />);
    expect(screen.getByTestId('chart-missing-columns')).toBeInTheDocument();
    expect(screen.queryByText('데이터가 없습니다.')).toBeNull();
  });

  it('컬럼이 모두 있으면 안내 없이 차트를 그린다', () => {
    render(<ChartRenderer chartType="BAR" config={{ xAxis: 'area', yAxis: ['sum'] }} data={ROWS} columns={['area', 'sum']} />);
    expect(screen.queryByTestId('chart-missing-columns')).toBeNull();
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('area|sum');
  });

  it('columns 가 비어 있으면 검사하지 않는다(기존 빈 상태 유지)', () => {
    render(<ChartRenderer chartType="BAR" config={{ xAxis: 'region', yAxis: ['total'] }} data={[]} columns={[]} />);
    expect(screen.queryByTestId('chart-missing-columns')).toBeNull();
    expect(screen.getByText('데이터가 없습니다.')).toBeInTheDocument();
  });

  it('설정이 비어 자동 추론하면 검사 없이 결과 컬럼으로 그린다', () => {
    render(<ChartRenderer chartType="BAR" config={{ xAxis: '', yAxis: [] }} data={ROWS} columns={['area', 'sum']} />);
    expect(screen.queryByTestId('chart-missing-columns')).toBeNull();
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('area|sum');
  });

  it('columns 가 표시 라벨이어도 row 키에 설정 컬럼이 있으면 안내 없이 그린다(AI 챗 오탐 방지)', () => {
    render(
      <ChartRenderer
        chartType="BAR"
        config={{ xAxis: 'region', yAxis: ['total'] }}
        data={[{ region: '서울', total: 10 }]}
        columns={['지역', '합계']}
      />,
    );
    expect(screen.queryByTestId('chart-missing-columns')).toBeNull();
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('region|total');
  });

  it('yAxis 가 없는 설정에서도 TypeError 없이 결과 컬럼으로 추론해 그린다', () => {
    const noYAxis = { xAxis: 'area' } as unknown as ChartConfig;
    render(<ChartRenderer chartType="BAR" config={noYAxis} data={ROWS} columns={['area', 'sum']} />);
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('area|sum');
  });

  it('yAxis 가 없고 컬럼이 하나뿐이면 TypeError 없이 축 설정 안내를 보여 준다', () => {
    const noYAxis = { xAxis: 'area' } as unknown as ChartConfig;
    render(<ChartRenderer chartType="BAR" config={noYAxis} data={ROWS} columns={['area']} />);
    expect(screen.getByText('X축과 Y축을 설정하세요.')).toBeInTheDocument();
  });

  it('missingColumnsHint 로 해결 문구만 바꿀 수 있고 제목·컬럼 목록은 같다', () => {
    render(
      <ChartRenderer
        chartType="BAR"
        config={{ xAxis: 'region', yAxis: ['sum'] }}
        data={ROWS}
        columns={['area', 'sum']}
        missingColumnsHint="AI에게 다시 요청하세요."
      />,
    );
    const notice = screen.getByTestId('chart-missing-columns');
    expect(notice).toHaveTextContent('차트에 쓰는 컬럼이 쿼리 결과에 없습니다');
    expect(notice).toHaveTextContent('AI에게 다시 요청하세요.');
    expect(notice).not.toHaveTextContent('차트 편집에서 축을 다시 고르세요');
    expect(Array.from(notice.querySelectorAll('code')).map((c) => c.textContent)).toEqual(['region']);
  });
});

describe('ChartRenderer — inferAxes=false 축 미선택 안내 (WD-505)', () => {
  // 문자 컬럼(region)이 섞인 결과 — 추론하면 region 을 값으로 읽어 NaN 이 그려졌다
  const COLS = ['region', 'cnt', 'amt'];
  const DATA = [
    { region: '서울', cnt: 5, amt: 100 },
    { region: '부산', cnt: 3, amt: 50 },
  ];

  it.each([
    ['GAUGE', { xAxis: 'region', yAxis: [] }, '값 컬럼(Y축)을 선택하세요.'],
    ['FUNNEL', { xAxis: 'region', yAxis: [] }, '값 컬럼(Y축)을 선택하세요.'],
    ['BAR', { xAxis: 'region', yAxis: [] }, 'Y축 컬럼을 선택하세요.'],
  ] as const)('%s 에서 yAxis 를 비우면 추론하지 않고 안내를 보여 준다', (type, config, message) => {
    const { container } = render(
      <ChartRenderer chartType={type} config={{ ...config, yAxis: [] }} data={DATA} columns={COLS} inferAxes={false} />,
    );
    expect(screen.getByTestId('chart-axis-required')).toHaveTextContent(message);
    expect(container.textContent).not.toContain('NaN');
    expect(screen.queryByTestId('bar-chart')).toBeNull();
  });

  it.each(['GAUGE', 'FUNNEL'] as const)('%s 값 컬럼으로 문자 컬럼을 고르면 NaN 대신 숫자 컬럼 안내', (type) => {
    const { container } = render(
      <ChartRenderer chartType={type} config={{ xAxis: 'region', yAxis: ['region'] }} data={DATA} columns={COLS} inferAxes={false} />,
    );
    expect(screen.getByTestId('chart-non-numeric-value')).toHaveTextContent('숫자 값 컬럼을 선택하세요.');
    expect(container.textContent).not.toContain('NaN');
  });

  it.each(['GAUGE', 'FUNNEL'] as const)('%s 값 컬럼이 빈 값이 섞인 문자 컬럼이어도 NaN·0 대신 숫자 컬럼 안내(WD-505 리뷰)', (type) => {
    // 첫 행이 빈 값이면 Number(null ?? 0) = 0 으로 가드를 통과해 게이지 0·퍼널 「이름: NaN」 이 그려지던 경우
    const data = [{ region: '서울', label: null }, { region: '부산', label: '가' }];
    const { container } = render(
      <ChartRenderer chartType={type} config={{ xAxis: 'region', yAxis: ['label'] }} data={data} columns={['region', 'label']} inferAxes={false} />,
    );
    expect(screen.getByTestId('chart-non-numeric-value')).toBeInTheDocument();
    expect(container.textContent).not.toContain('NaN');
  });

  it('산점도 X 가 숫자가 아니면 축 안내 대신 「X축은 숫자 컬럼이어야 합니다」(WD-308)를 보여 준다', () => {
    render(
      <ChartRenderer
        chartType="SCATTER"
        config={{ xAxis: 'city', yAxis: [] }}
        data={[{ city: '서울', label: 'a' }]}
        columns={['city', 'label']}
        inferAxes={false}
      />,
    );
    expect(screen.queryByTestId('chart-axis-required')).toBeNull();
    expect(screen.getByText('산점도의 X축은 숫자 컬럼이어야 합니다')).toBeInTheDocument();
  });

  it('산점도 X 가 숫자이고 Y 가 비면 축 안내를 보여 준다', () => {
    render(
      <ChartRenderer chartType="SCATTER" config={{ xAxis: 'cnt', yAxis: [] }} data={DATA} columns={COLS} inferAxes={false} />,
    );
    expect(screen.getByTestId('chart-axis-required')).toHaveTextContent('Y축 컬럼을 선택하세요.');
  });

  it('축이 모두 있으면 inferAxes=false 여도 차트를 그린다', () => {
    render(
      <ChartRenderer chartType="BAR" config={{ xAxis: 'region', yAxis: ['cnt'] }} data={DATA} columns={COLS} inferAxes={false} />,
    );
    expect(screen.queryByTestId('chart-axis-required')).toBeNull();
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('region|cnt');
  });

  it('inferAxes 기본(true)은 기존처럼 비운 yAxis 를 추론한다', () => {
    render(<ChartRenderer chartType="BAR" config={{ xAxis: 'region', yAxis: [] }} data={DATA} columns={COLS} />);
    expect(screen.queryByTestId('chart-axis-required')).toBeNull();
    expect(screen.getByTestId('bar-chart')).toHaveTextContent('region|cnt,amt');
  });
});
