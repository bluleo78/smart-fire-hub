// apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx
// 연속 수치 데이터를 N개 구간(bin)으로 집계하여 빈도 막대 차트로 표시.
// config.xAxis = 수치 컬럼명, config.bins = 구간 수 (기본 20)
import {
Bar,   BarChart, CartesianGrid, ResponsiveContainer,
Tooltip, XAxis, YAxis, } from 'recharts';

import { BAR_CURSOR_STYLE,TOOLTIP_CONTENT_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

/** 수치 배열을 N개 구간으로 집계 */
function binValues(values: number[], bins: number) {
  if (values.length === 0) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) return [{ label: String(min), count: values.length }];
  const size = (max - min) / bins;
  const result = Array.from({ length: bins }, (_, i) => ({
    label: (min + i * size).toFixed(1),
    count: 0,
  }));
  values.forEach(v => {
    const idx = Math.min(Math.floor((v - min) / size), bins - 1);
    result[idx].count++;
  });
  return result;
}

export function HistogramChartView({ data, config, height = 300 }: ChartViewProps) {
  const bins = config.bins ?? 20;
  const values = data
    .map(d => Number(d[config.xAxis]))
    .filter(v => !isNaN(v));
  const binnedData = binValues(values, bins);

  // 수치 컬럼이 아닌 경우 안내 메시지 표시
  if (values.length === 0 && data.length > 0) {
    return (
      <div style={{ height }} className="flex items-center justify-center text-sm text-muted-foreground">
        X축은 숫자 컬럼이어야 합니다
      </div>
    );
  }

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <BarChart data={binnedData} barCategoryGap={1}>
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis
          dataKey="label"
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
          label={config.xAxisLabel ? { value: config.xAxisLabel, position: 'insideBottom', offset: -4, fontSize: 11 } : undefined}
        />
        <YAxis
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
          label={config.yAxisLabel ? { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fontSize: 11 } : undefined}
        />
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          cursor={BAR_CURSOR_STYLE}
          formatter={(v: number | undefined) => [v ?? 0, '빈도']}
        />
        <Bar dataKey="count" fill="#8884d8" radius={[2, 2, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
