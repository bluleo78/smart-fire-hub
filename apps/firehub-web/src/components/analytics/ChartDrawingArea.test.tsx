/**
 * 차트 그림 영역 숨김 범위 단위 테스트 (WD-826)
 *
 * aria-hidden 은 각 뷰의 그림 영역(ChartDrawingArea)에만 걸려야 한다 — 뷰가 띄우는 안내문(파이 음수 제외 안내,
 * 숫자 아닌 값 안내)은 화면에 보이는 정보라 figure 안에서 스크린리더에 그대로 노출돼야 하고,
 * 숨긴 그림 안에는 Tab 으로 닿는 요소가 없어야 한다(WCAG 4.1.2, axe aria-hidden-focus).
 */
import { render, screen, within } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ChartAccessibleFigure } from './ChartAccessibleFigure';
import { ChartDrawingArea } from './ChartDrawingArea';
import { ChartRenderer } from './ChartRenderer';

/** 숨긴 영역 안에서 Tab 정지점이 될 수 있는 요소 */
const TABBABLE = '[tabindex]:not([tabindex="-1"]), button, a[href], input, select, textarea';

/**
 * ResizeObserver 스텁을 만든다 — size 를 주면 관찰 즉시 그 크기를 알리고, 없으면 아무것도 알리지 않는다(빈 구현).
 * jsdom 에는 ResizeObserver 가 없고 크기도 0 이라, 마운트만 필요하면 빈 구현을, svg 까지 그려야 하면 크기를 준다.
 */
function createResizeObserverStub(size?: { width: number; height: number }) {
  return class StubResizeObserver {
    private readonly callback: ResizeObserverCallback;
    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }
    observe(target: Element) {
      if (!size) return;
      const entry = { target, contentRect: { ...size, top: 0, left: 0 } } as unknown as ResizeObserverEntry;
      this.callback([entry], this as unknown as ResizeObserver);
    }
    unobserve() {}
    disconnect() {}
  };
}

beforeAll(() => {
  // recharts ResponsiveContainer·커스텀 뷰가 마운트되도록 빈 구현을 둔다
  if (!('ResizeObserver' in globalThis)) {
    Object.defineProperty(globalThis, 'ResizeObserver', { value: createResizeObserverStub(), configurable: true });
  }
});

describe('ChartDrawingArea — 그림만 숨기고 안내문은 남긴다 (WD-826)', () => {
  it('파이 음수 제외 안내는 figure 안에 있고 aria-hidden 조상이 없다', () => {
    render(
      <ChartRenderer
        chartType="PIE"
        config={{ xAxis: 'area', yAxis: ['sum'] }}
        data={[
          { area: '서울', sum: 10 },
          { area: '인천', sum: -5 },
        ]}
        columns={['area', 'sum']}
      />,
    );
    const figure = screen.getByTestId('chart-figure');
    const notice = screen.getByTestId('pie-negative-notice');
    expect(figure).toContainElement(notice);
    expect(notice.closest('[aria-hidden="true"]')).toBeNull();
    // 그림 영역은 figure 안에서 숨겨지고, 그 안에는 Tab 정지점이 없다
    const drawing = figure.querySelector('[data-chart-drawing]');
    expect(drawing).toHaveAttribute('aria-hidden', 'true');
    expect(drawing!.querySelectorAll(TABBABLE)).toHaveLength(0);
    // figure 의 자식 래퍼 자체는 더 이상 통째로 숨기지 않는다
    expect(figure.querySelectorAll(':scope > [aria-hidden="true"]')).toHaveLength(0);
  });

  it('게이지의 숫자 아닌 값 안내는 figure 안에서 읽히고 그림 영역은 없다', () => {
    render(
      <ChartRenderer
        chartType="GAUGE"
        config={{ xAxis: 'area', yAxis: ['area'] }}
        data={[{ area: '서울' }]}
        columns={['area']}
      />,
    );
    const figure = screen.getByTestId('chart-figure');
    const notice = screen.getByTestId('chart-non-numeric-value');
    expect(figure).toContainElement(notice);
    expect(notice.closest('[aria-hidden="true"]')).toBeNull();
    expect(figure.querySelector('[data-chart-drawing]')).toBeNull();
  });

  it('숫자 게이지는 그림 영역만 숨기고 그 안에 Tab 정지점이 없다', () => {
    render(
      <ChartRenderer chartType="GAUGE" config={{ xAxis: 'area', yAxis: ['sum'] }} data={[{ area: '서울', sum: 42 }]} columns={['area', 'sum']} />,
    );
    const drawing = screen.getByTestId('chart-figure').querySelector('[data-chart-drawing]');
    expect(drawing).toHaveAttribute('aria-hidden', 'true');
    expect(drawing!.querySelectorAll(TABBABLE)).toHaveLength(0);
  });
});

/**
 * 그림 대신 안내만 그릴 때 (WD-826 코드 리뷰 지적 2)
 * 화면에 차트가 없는데 스크린리더만 「차트 이름 + 값 표」를 읽으면 시각 사용자와 정보가 어긋난다 —
 * 뷰가 그림 영역(ChartDrawingArea)을 마운트했을 때만 figure 가 이름·표를 내야 한다.
 */
describe('ChartAccessibleFigure — 그림이 있을 때만 차트 이름·값 표 (WD-826)', () => {
  /** 이름 있는 figure·값 표가 없고, 안내문은 그대로 읽힌다 */
  function expectNoticeOnly(noticeText: string) {
    expect(screen.queryByRole('figure')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
    const figure = screen.getByTestId('chart-figure');
    expect(figure).not.toHaveAttribute('aria-label');
    expect(figure).toHaveAttribute('role', 'presentation');
    expect(figure).toHaveTextContent(noticeText);
  }

  it('게이지 값이 숫자가 아니면(안내만) figure 이름·값 표가 없다', () => {
    render(
      <ChartRenderer chartType="GAUGE" config={{ xAxis: 'area', yAxis: ['area'] }} data={[{ area: '서울' }]} columns={['area']} />,
    );
    expectNoticeOnly('숫자 값 컬럼을 선택하세요.');
  });

  it('히스토그램 X 가 숫자가 아니면(안내만) figure 이름·값 표가 없다', () => {
    render(
      <ChartRenderer chartType="HISTOGRAM" config={{ xAxis: 'area', yAxis: ['area'] }} data={[{ area: '서울' }]} columns={['area']} />,
    );
    expectNoticeOnly('X축은 숫자 컬럼이어야 합니다');
  });

  it('박스 플롯 통계 컬럼이 없으면(안내만) figure 이름·값 표가 없다', () => {
    render(
      <ChartRenderer chartType="BOXPLOT" config={{ xAxis: 'area', yAxis: ['sum'] }} data={[{ area: '서울', sum: 1 }]} columns={['area', 'sum']} />,
    );
    expectNoticeOnly('박스 플롯은 쿼리 결과에');
  });

  it('그림을 그리면 이름 있는 figure 와 차트가 보여 주는 값의 표가 있다', () => {
    render(
      <ChartRenderer chartType="GAUGE" config={{ xAxis: 'area', yAxis: ['sum'] }} data={[{ area: '서울', sum: 42 }]} columns={['area', 'sum']} />,
    );
    const figure = screen.getByRole('figure', { name: '게이지: area별 sum' });
    const table = within(figure).getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual(['sum', '최솟값', '최댓값']);
    expect(within(table).getAllByRole('cell').map((td) => td.textContent)).toEqual(['42', '0', '51']);
  });

  it('그림·안내가 바뀌어도 figure 와 뷰 자식이 다시 마운트되지 않는다', () => {
    const mounts = { count: 0 };
    /** 마운트될 때만 세는 자식 — 다시 마운트되면 count 가 늘어난다 */
    function MountCounter() {
      useEffect(() => {
        mounts.count += 1;
      }, []);
      return null;
    }
    /** 실제 뷰처럼 상태에 따라 그림 영역 또는 안내를 그린다 */
    function View({ drawing }: { drawing: boolean }) {
      return (
        <>
          <MountCounter />
          {drawing ? (
            <ChartDrawingArea>
              <svg />
            </ChartDrawingArea>
          ) : (
            <p>안내</p>
          )}
        </>
      );
    }
    const table = { caption: 'N 데이터', headers: ['a'], rows: [['1']] };
    const { rerender } = render(
      <ChartAccessibleFigure name="N" table={table}>
        <View drawing={false} />
      </ChartAccessibleFigure>,
    );
    const figure = screen.getByTestId('chart-figure');
    expect(screen.queryByRole('figure')).toBeNull();

    rerender(
      <ChartAccessibleFigure name="N" table={table}>
        <View drawing />
      </ChartAccessibleFigure>,
    );
    expect(screen.getByRole('figure', { name: 'N' })).toBe(figure);
    expect(within(figure).getByRole('table')).toBeInTheDocument();

    rerender(
      <ChartAccessibleFigure name="N" table={table}>
        <View drawing={false} />
      </ChartAccessibleFigure>,
    );
    expect(screen.getByTestId('chart-figure')).toBe(figure);
    expect(screen.queryByRole('figure')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
    expect(mounts.count).toBe(1);
  });
});

/**
 * 숨긴 recharts svg 에 조작 요소가 남지 않는다 (WD-826 코드 리뷰 지적 4·8)
 * accessibilityLayer(기본 true)는 svg 에 role="application"·tabindex=0 을 붙인다 — HIDDEN_CHART_ROOT_PROPS 로 끈다.
 * jsdom 은 크기가 0 이라 recharts 가 svg 를 그리지 않으므로, 이 묶음에서만 관찰 즉시 크기를 알려 주는 ResizeObserver 를 둔다.
 */
describe('HIDDEN_CHART_ROOT_PROPS — 숨긴 recharts svg 는 role=application·포커스가 없다 (WD-826)', () => {
  let original: unknown;
  beforeEach(() => {
    original = globalThis.ResizeObserver;
    Object.defineProperty(globalThis, 'ResizeObserver', { value: createResizeObserverStub({ width: 400, height: 300 }), configurable: true });
  });
  afterEach(() => {
    Object.defineProperty(globalThis, 'ResizeObserver', { value: original, configurable: true });
  });

  const XY = { xAxis: 'area', yAxis: ['sum'] };
  const ROWS = [
    { area: '서울', sum: 10 },
    { area: '부산', sum: 20 },
  ];
  const cases = [
    { type: 'BAR', config: XY },
    { type: 'LINE', config: XY },
    { type: 'AREA', config: XY },
    { type: 'PIE', config: XY },
    { type: 'DONUT', config: XY },
    { type: 'SCATTER', config: { xAxis: 'sum', yAxis: ['sum'] } },
    { type: 'RADAR', config: XY },
    { type: 'HISTOGRAM', config: { xAxis: 'sum', yAxis: [] } },
    { type: 'WATERFALL', config: XY },
    { type: 'FUNNEL', config: XY },
    { type: 'GAUGE', config: XY },
  ] as const;

  it.each(cases)('$type — 숨긴 그림의 recharts svg 에 role=application·tabindex 가 없다', ({ type, config }) => {
    render(<ChartRenderer chartType={type} config={{ ...config, yAxis: [...config.yAxis] }} data={ROWS} columns={['area', 'sum']} />);
    const drawing = screen.getByTestId('chart-figure').querySelector('[data-chart-drawing]');
    expect(drawing).toHaveAttribute('aria-hidden', 'true');
    const surfaces = drawing!.querySelectorAll('svg.recharts-surface');
    expect(surfaces.length).toBeGreaterThan(0);
    surfaces.forEach((svg) => {
      expect(svg).not.toHaveAttribute('role', 'application');
      // tabindex 는 아예 없거나 -1 이어야 한다(Tab 정지점 아님)
      const tabindex = svg.getAttribute('tabindex');
      expect(tabindex === null || tabindex === '-1').toBe(true);
    });
    expect(drawing!.querySelectorAll(TABBABLE)).toHaveLength(0);
  });
});
