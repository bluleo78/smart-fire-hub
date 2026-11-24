import { describe, expect, it } from 'vitest';

import { CHART_ANIMATION_MAX_ROWS, chartAnimationActive } from './chart-styles';

// WD-1072: 행이 많은 차트는 시리즈 애니메이션을 끈다.
describe('chartAnimationActive', () => {
  it('기준은 100행이다', () => {
    expect(CHART_ANIMATION_MAX_ROWS).toBe(100);
  });
  it('0행·100행은 애니메이션을 켠다', () => {
    expect(chartAnimationActive(0)).toBe(true);
    expect(chartAnimationActive(100)).toBe(true);
  });
  it('101행부터는 끈다', () => {
    expect(chartAnimationActive(101)).toBe(false);
    expect(chartAnimationActive(500)).toBe(false);
  });
});
