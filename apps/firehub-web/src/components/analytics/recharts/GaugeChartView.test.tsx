/**
 * GaugeChartView 단위 테스트 (WD-846)
 *
 * 높이가 숫자로 주어지면(AI 채팅 인라인 차트 등) 부모 크기를 재지 않고 그 높이 그대로 그려야 한다.
 * fillParent(높이 미지정) 경로는 실제 레이아웃이 필요해 E2E(dashboard-widget-fill-height)가 검증한다.
 */
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { GaugeChartView } from './GaugeChartView';

describe('GaugeChartView — 고정 높이 (WD-846)', () => {
  it('height={240} 이면 드로잉 영역 높이가 240px 이다', () => {
    const { container } = render(
      <GaugeChartView
        data={[{ name: 'A', v: 70 }]}
        config={{ xAxis: 'name', yAxis: ['v'], min: 0, max: 100 }}
        height={240}
      />,
    );
    const drawing = container.querySelector<HTMLElement>('[data-chart-drawing]');
    expect(drawing).not.toBeNull();
    expect(drawing!.style.height).toBe('240px');
  });
});
