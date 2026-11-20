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
