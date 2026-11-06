/**
 * findMissingChartAxesMessage 단위 테스트 (WD-505 회귀 방지)
 *
 * 차트 빌더 미리보기(inferAxes=false)와 저장 검증이 같은 규칙으로 빠진 축을 안내해야 한다.
 * 타입마다 필요한 축이 달라(게이지는 X 불필요, 통계 타입은 Y 불필요) 타입별로 단언한다.
 */
import { describe, expect, it } from 'vitest';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { findMissingChartAxesMessage } from './chart-required-axes';

/** 기본 설정에 덮어쓸 값만 받는 헬퍼 */
function cfg(overrides: Partial<ChartConfig>): ChartConfig {
  return { xAxis: '', yAxis: [], ...overrides };
}

describe('findMissingChartAxesMessage (WD-505)', () => {
  it.each<ChartType>(['MAP', 'TABLE'])('%s: 축 규칙이 없어 항상 null', (type) => {
    expect(findMissingChartAxesMessage(type, cfg({}))).toBeNull();
  });

  it.each<ChartType>(['CANDLESTICK', 'BOXPLOT', 'HISTOGRAM'])('%s: xAxis 만 필수', (type) => {
    expect(findMissingChartAxesMessage(type, cfg({}))).toBe('X축을 설정하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ xAxis: 'a' }))).toBeNull();
  });

  it('HEATMAP: 행·열·값 하나라도 없으면 안내', () => {
    const msg = '행, 열, 값(색상 기준) 컬럼을 모두 선택하세요.';
    expect(findMissingChartAxesMessage('HEATMAP', cfg({ xAxis: 'a', yAxis: ['b'] }))).toBe(msg);
    expect(findMissingChartAxesMessage('HEATMAP', cfg({ xAxis: 'a', valueColumn: 'c' }))).toBe(msg);
    expect(findMissingChartAxesMessage('HEATMAP', cfg({ xAxis: 'a', yAxis: ['b'], valueColumn: 'c' }))).toBeNull();
  });

  it('GAUGE: 값 컬럼만 필수(xAxis 불필요)', () => {
    expect(findMissingChartAxesMessage('GAUGE', cfg({ xAxis: 'region' }))).toBe('값 컬럼(Y축)을 선택하세요.');
    expect(findMissingChartAxesMessage('GAUGE', cfg({ yAxis: ['cnt'] }))).toBeNull();
  });

  it.each<ChartType>(['PIE', 'DONUT', 'TREEMAP', 'FUNNEL', 'WATERFALL'])('%s: 이름 + 값 하나', (type) => {
    expect(findMissingChartAxesMessage(type, cfg({}))).toBe('X축과 Y축을 설정하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ xAxis: 'region' }))).toBe('값 컬럼(Y축)을 선택하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ yAxis: ['cnt'] }))).toBe('X축을 설정하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ xAxis: 'region', yAxis: ['cnt'] }))).toBeNull();
  });

  it.each<ChartType>(['BAR', 'LINE', 'AREA', 'RADAR', 'SCATTER'])('%s: X + Y 하나 이상', (type) => {
    expect(findMissingChartAxesMessage(type, cfg({}))).toBe('X축과 Y축을 설정하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ xAxis: 'region' }))).toBe('Y축 컬럼을 선택하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ yAxis: ['cnt'] }))).toBe('X축을 설정하세요.');
    expect(findMissingChartAxesMessage(type, cfg({ xAxis: 'region', yAxis: ['cnt', 'amt'] }))).toBeNull();
  });

  it('yAxis 가 빠지거나 빈 문자열만 있어도 미설정으로 본다', () => {
    const noYAxis = { xAxis: 'region' } as unknown as ChartConfig;
    expect(findMissingChartAxesMessage('BAR', noYAxis)).toBe('Y축 컬럼을 선택하세요.');
    expect(findMissingChartAxesMessage('GAUGE', cfg({ yAxis: [''] }))).toBe('값 컬럼(Y축)을 선택하세요.');
  });
});
