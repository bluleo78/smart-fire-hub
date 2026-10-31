import { describe, expect, it } from 'vitest';

import { MAX_HISTOGRAM_BINS, normalizeChartConfig } from './normalize-chart-config';

/** 잘못 저장된 차트 config 정규화 (WD-413) */
describe('normalizeChartConfig', () => {
  it('yAxis 가 없으면 빈 배열, 문자열이면 한 칸 배열, 배열이면 문자열만 남긴다', () => {
    expect(normalizeChartConfig({ xAxis: 'region' }).yAxis).toEqual([]);
    expect(normalizeChartConfig({ xAxis: 'region', yAxis: 'amount' }).yAxis).toEqual(['amount']);
    expect(normalizeChartConfig({ xAxis: 'region', yAxis: '' }).yAxis).toEqual([]);
    expect(normalizeChartConfig({ xAxis: 'r', yAxis: [1, { a: 2 }, 'total'] }).yAxis).toEqual(['total']);
  });

  it('xAxis 가 문자열이 아니면 선택 안 함(빈 문자열)으로 둔다', () => {
    expect(normalizeChartConfig({ xAxis: 123, yAxis: [] }).xAxis).toBe('');
    expect(normalizeChartConfig({ xAxis: ['region'], yAxis: [] }).xAxis).toBe('');
  });

  it('타입이 틀린 옵션은 버리고 맞는 값은 그대로 둔다', () => {
    const out = normalizeChartConfig({
      xAxis: 'r',
      yAxis: ['a'],
      stacked: 'yes',
      showLegend: false,
      groupBy: 3,
      valueColumn: 'v',
      bins: '10',
      colors: ['#fff', 1],
      mapDisplayMode: 'grid',
      custom: 'kept',
    });
    expect(out).toEqual({ xAxis: 'r', yAxis: ['a'], showLegend: false, valueColumn: 'v', colors: ['#fff'], custom: 'kept' });
  });

  it('게이지 범위가 거꾸로면(min ≥ max) 둘 다 버려 기본 범위로 그리게 한다', () => {
    const out = normalizeChartConfig({ xAxis: '', yAxis: ['v'], min: 100, max: -5, target: 'abc' });
    expect(out).toEqual({ xAxis: '', yAxis: ['v'] });
    expect(normalizeChartConfig({ xAxis: '', yAxis: ['v'], min: 0, max: 50 })).toMatchObject({ min: 0, max: 50 });
  });

  it('bins 가 1 이상 상한 이하의 정수가 아니면 버려 기본 구간 수로 그리게 한다', () => {
    for (const bins of [0, -3, 2.5, MAX_HISTOGRAM_BINS + 1]) {
      expect(normalizeChartConfig({ xAxis: 'v', yAxis: [], bins })).toEqual({ xAxis: 'v', yAxis: [] });
    }
    expect(normalizeChartConfig({ xAxis: 'v', yAxis: [], bins: 12 })).toMatchObject({ bins: 12 });
  });

  it('config 자체가 객체가 아니면(null·배열) 빈 축 설정을 돌려준다', () => {
    expect(normalizeChartConfig(null)).toEqual({ xAxis: '', yAxis: [] });
    expect(normalizeChartConfig([])).toEqual({ xAxis: '', yAxis: [] });
  });

  it('원본을 바꾸지 않는다', () => {
    const raw = { xAxis: 'r', yAxis: 'a' };
    normalizeChartConfig(raw);
    expect(raw).toEqual({ xAxis: 'r', yAxis: 'a' });
  });
});
