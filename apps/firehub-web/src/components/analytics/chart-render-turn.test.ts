import { describe, expect, it } from 'vitest';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { chartNeedsRenderTurn } from './chart-render-turn';

const config: ChartConfig = { xAxis: 'cat', yAxis: ['v'] };
const columns = ['cat', 'v'];
const rows = [{ cat: 'a', v: 1 }];

describe('chartNeedsRenderTurn (WD-1113)', () => {
  it.each(['BAR', 'LINE', 'HEATMAP', 'BOXPLOT'] as ChartType[])('%s 는 그릴 것이 있어 true', (type) => {
    expect(chartNeedsRenderTurn(type, config, columns, rows)).toBe(true);
  });

  it.each(['TABLE', 'MAP'] as ChartType[])('%s 는 false', (type) => {
    expect(chartNeedsRenderTurn(type, config, columns, rows)).toBe(false);
  });

  it('모르는 타입은 false', () => {
    expect(chartNeedsRenderTurn('NOPE' as ChartType, config, columns, rows)).toBe(false);
  });

  it('0행은 false', () => {
    expect(chartNeedsRenderTurn('BAR', config, columns, [])).toBe(false);
  });

  it('설정 컬럼이 결과에 없으면 false', () => {
    expect(chartNeedsRenderTurn('BAR', { xAxis: 'cat', yAxis: ['gone'] }, columns, rows)).toBe(false);
  });

  it('축을 추론으로도 못 채우면 false', () => {
    expect(chartNeedsRenderTurn('BAR', { xAxis: '', yAxis: [] }, ['cat'], [{ cat: 'a' }])).toBe(false);
  });

  it('빈 설정이라도 추론으로 채워지면 true', () => {
    expect(chartNeedsRenderTurn('BAR', { xAxis: '', yAxis: [] }, columns, rows)).toBe(true);
  });

  it('산점도 X 가 숫자가 아니어도 추론 판정이라 줄을 선다(기존 동작 — 대시보드는 늘 추론)', () => {
    expect(chartNeedsRenderTurn('SCATTER', { xAxis: 'cat', yAxis: ['v'] }, ['cat', 'v'], [{ cat: 'a', v: 1 }])).toBe(true);
  });
});
