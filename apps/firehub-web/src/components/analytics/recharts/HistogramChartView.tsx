// apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx
// 연속 수치 데이터를 N개 구간(bin)으로 집계하여 빈도 막대 차트로 표시.
// config.xAxis = 수치 컬럼명, config.bins = 구간 수 (기본 20)
import { useMemo } from 'react';
import {
Bar,   BarChart, CartesianGrid, ResponsiveContainer,
Tooltip, XAxis, YAxis, } from 'recharts';

import { binHistogramValues, getHistogramBinCount, readHistogramValues } from '../chart-derived-data';
import { HIDDEN_CHART_ROOT_PROPS } from '../chart-figure-context';
import { BAR_CURSOR_STYLE,TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';

export function HistogramChartView({ data, config, height = 300 }: ChartViewProps) {
  // WD-826: 구간 집계는 값 대체 표(chart-a11y)와 같은 함수를 쓴다 — 표가 차트와 같은 구간·빈도를 전하게
  // 렌더마다(figure 의 그림 등록 재렌더 포함) 다시 집계하지 않게 data·축·구간 수 기준으로 메모한다.
  const binCount = getHistogramBinCount(config);
  const values = useMemo(() => readHistogramValues(data, config.xAxis), [data, config.xAxis]);
  const binnedData = useMemo(() => binHistogramValues(values, binCount), [values, binCount]);

  // 수치 컬럼이 아닌 경우 안내 메시지 표시
  if (values.length === 0 && data.length > 0) {
    return (
      <div style={{ height }} className="flex items-center justify-center text-sm text-muted-foreground">
        X축은 숫자 컬럼이어야 합니다
      </div>
    );
  }

  return (
    <ChartDrawingArea>
      {/* WD-826: 그림은 숨기고 대체 표가 값을 전한다 */}
      <ResponsiveContainer width="100%" height={height ?? '100%'}>
        <BarChart {...HIDDEN_CHART_ROOT_PROPS} data={binnedData} barCategoryGap={1}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
            label={config.xAxisLabel ? { value: config.xAxisLabel, position: 'insideBottom', offset: -4, fontSize: 11 } : undefined}
          />
          <YAxis
            tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
            label={config.yAxisLabel ? { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fontSize: 11 } : undefined}
          />
          <Tooltip
            contentStyle={TOOLTIP_CONTENT_STYLE}
            itemStyle={TOOLTIP_ITEM_STYLE}
            cursor={BAR_CURSOR_STYLE}
            formatter={(v: number | undefined) => [v ?? 0, '빈도']}
          />
          <Bar dataKey="count" fill="var(--chart-1)" radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </ChartDrawingArea>
  );
}
