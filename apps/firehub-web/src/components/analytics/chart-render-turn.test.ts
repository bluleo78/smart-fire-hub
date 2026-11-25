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

  it('inferAxes=false 면 축을 추론하지 않아 축 미설정은 차례를 서지 않는다', () => {
    const empty: ChartConfig = { xAxis: '', yAxis: [] };
    expect(chartNeedsRenderTurn('BAR', empty, columns, rows, false)).toBe(false);
    expect(chartNeedsRenderTurn('BAR', empty, columns, rows, true)).toBe(true);
  });

  // WD-1146: inferAxes=false 는 렌더러의 타입별 필수 축 규칙을 따른다 — 공통 xAxis·yAxis 검사로 오탐하지 않는다
  it('inferAxes=false 에서 히스토그램은 yAxis 없이 xAxis 만 있어도 그리므로 true', () => {
    expect(chartNeedsRenderTurn('HISTOGRAM', { xAxis: 'v', yAxis: [] }, columns, rows, false)).toBe(true);
  });

  it('inferAxes=false 에서 게이지는 xAxis 없이 yAxis 만 있어도 그리므로 true', () => {
    expect(chartNeedsRenderTurn('GAUGE', { xAxis: '', yAxis: ['v'] }, columns, rows, false)).toBe(true);
  });

  it('inferAxes=false 에서 산점도 X 가 숫자로 읽히지 않으면 가벼운 안내만 그려 false', () => {
    expect(chartNeedsRenderTurn('SCATTER', { xAxis: 'cat', yAxis: ['v'] }, columns, rows, false)).toBe(false);
  });

  it('inferAxes=false 에서 산점도 X 가 숫자면 true', () => {
    expect(chartNeedsRenderTurn('SCATTER', { xAxis: 'v', yAxis: ['v'] }, columns, rows, false)).toBe(true);
  });
});
