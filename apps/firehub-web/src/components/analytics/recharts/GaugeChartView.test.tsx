/**
 * GaugeChartView 단위 테스트 (WD-846)
 *
 * 높이가 숫자로 주어지면(AI 채팅 인라인 차트 등) 그 높이 그대로 그려야 한다. 게이지는 폭을 알아야 해
 * ResponsiveWrapper(react-virtualized-auto-sizer)로 부모를 재는데, jsdom 은 크기가 0 이라 그대로 두면 아무것도
 * 그리지 않은 채 높이만 맞아 통과한다 — 그래서 부모 크기를 모킹해 실제로 그림(svg·바늘·값)이 있는지까지 본다.
 * fillParent(높이 미지정) 경로는 실제 레이아웃이 필요해 E2E(dashboard-widget-fill-height)가 검증한다.
 */
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { mockElementSize } from '../test-element-size';
import { GaugeChartView } from './GaugeChartView';

let restoreSize: (() => void) | undefined;

/** 부모 크기를 모킹한다 — 시험이 끝나면 afterEach 가 되돌린다 */
function mockParentSize(width: number, height: number) {
  restoreSize = mockElementSize(width, height);
}

afterEach(() => {
  restoreSize?.();
  restoreSize = undefined;
});

const CONFIG = { xAxis: 'name', yAxis: ['v'], min: 0, max: 100 };

describe('GaugeChartView — 고정 높이 (WD-846)', () => {
  it('height={240} 이면 드로잉 영역 높이가 240px 이고 호·바늘·값을 그린다', () => {
    mockParentSize(400, 240);
    const { container } = render(<GaugeChartView data={[{ name: 'A', v: 70 }]} config={CONFIG} height={240} />);
    const drawing = container.querySelector<HTMLElement>('[data-chart-drawing]');
    expect(drawing).not.toBeNull();
    expect(drawing!.style.height).toBe('240px');
    // 측정 뒤에 그렸는지 — 측정 표식, 호(recharts svg), 바늘(line), 값 글자
    expect(container.querySelector('[data-chart-measured="true"]')).not.toBeNull();
    expect(container.querySelector('.recharts-wrapper svg')).not.toBeNull();
    const needle = container.querySelector('line');
    expect(needle).not.toBeNull();
    // 바늘 중심은 (W/2, 0.75h) = (200, 180)
    expect(Number(needle!.getAttribute('x1'))).toBe(200);
    expect(Number(needle!.getAttribute('y1'))).toBe(180);
    expect(container.textContent).toContain('70');
  });

  it('좁은 폭(90px)에서 큰 값은 폭에 맞게 글자 크기를 줄인다', () => {
    mockParentSize(90, 240);
    const { container } = render(
      <GaugeChartView data={[{ name: 'A', v: 12345678 }]} config={{ ...CONFIG, max: 20000000 }} height={240} />,
    );
    const valueText = Array.from(container.querySelectorAll('text')).find((t) => t.textContent === '12,345,678');
    expect(valueText).toBeDefined();
    const fontSize = Number(valueText!.getAttribute('font-size'));
    // 줄이지 않으면 16px — 폭 90px 에 10글자를 넣으려면 약 13px 이어야 한다(굵은 숫자 폭 ≈ 0.62em 기준)
    expect(fontSize).toBeLessThan(14);
    expect(fontSize).toBeGreaterThanOrEqual(8);
    // 범위 글자(「0 ~ 20,000,000」 14글자·10px ≈ 87px)는 81px 에 들어가지 않아 숨긴다
    expect(container.textContent).not.toContain('20,000,000');
  });
});

describe('GaugeChartView — 범위 잘못됨 안내 (WD-848)', () => {
  const NOTICE = 'chart-gauge-invalid-range';

  it.each([
    ['min = max', { min: 100, max: 100 }, '게이지 최댓값(100)이 최솟값(100)보다 커야 합니다.'],
    ['max < min', { min: 100, max: 60 }, '게이지 최댓값(60)이 최솟값(100)보다 커야 합니다.'],
  ])('%s 이면 두 값을 담은 안내를 보이고 바늘·호를 그리지 않는다', (_name, range, text) => {
    mockParentSize(400, 240);
    const { container, getByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: 50 }]} config={{ ...CONFIG, ...range }} height={240} />,
    );
    expect(getByTestId(NOTICE).textContent).toBe(text);
    expect(container.querySelector('line')).toBeNull();
    expect(container.querySelector('.recharts-wrapper')).toBeNull();
  });

  it.each([
    ['NaN', { min: 0, max: Number.NaN }],
    ['Infinity', { min: 0, max: Infinity }],
  ])('%s 이면 숫자 글자 없이 계산 불가 안내를 보인다', (_name, range) => {
    mockParentSize(400, 240);
    const { getByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: 50 }]} config={{ ...CONFIG, ...range }} height={240} />,
    );
    const text = getByTestId(NOTICE).textContent ?? '';
    expect(text).toBe('게이지 범위를 계산할 수 없습니다 — 최솟값·최댓값이 올바른 숫자가 아닙니다.');
    expect(text).not.toMatch(/NaN|Infinity|∞/);
  });

  it('한쪽만 설정한 min 이 자동 max 보다 크면 max 를 맞춰 정상 게이지로 그린다', () => {
    mockParentSize(400, 240);
    const { container, queryByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: 100 }]} config={{ xAxis: 'name', yAxis: ['v'], min: 200 }} height={240} />,
    );
    expect(queryByTestId(NOTICE)).toBeNull();
    expect(container.querySelector('line')).not.toBeNull();
    expect(container.textContent).toContain('200 ~ 240');
  });

  it('숫자 아닌 값 안내가 범위 안내보다 먼저다', () => {
    mockParentSize(400, 240);
    const { queryByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: '서울' }]} config={{ ...CONFIG, min: 100, max: 60 }} height={240} />,
    );
    expect(queryByTestId('chart-non-numeric-value')).not.toBeNull();
    expect(queryByTestId(NOTICE)).toBeNull();
  });

  it('height 가 없으면(fillParent) 안내가 부모 높이를 채운다', () => {
    const { getByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: 50 }]} config={{ ...CONFIG, min: 100, max: 60 }} />,
    );
    expect(getByTestId(NOTICE).className).toContain('h-full');
  });

  it('정상 범위면 안내 없이 바늘을 그린다', () => {
    mockParentSize(400, 240);
    const { container, queryByTestId } = render(
      <GaugeChartView data={[{ name: 'A', v: 70 }]} config={CONFIG} height={240} />,
    );
    expect(queryByTestId(NOTICE)).toBeNull();
    expect(container.querySelector('line')).not.toBeNull();
  });
});
