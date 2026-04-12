// apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx
// 누적 증감을 폭포 형태로 표현. config.xAxis = 카테고리, config.yAxis[0] = 변화량(양수/음수 모두 허용).
// 투명한 base bar + 색상 bar 스택으로 floating bar를 구현한다.
import {
Bar, CartesianGrid, Cell,   ComposedChart, ReferenceLine,
  ResponsiveContainer, Tooltip,
XAxis, YAxis, } from 'recharts';

import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

interface WaterfallRow {
  name: string;
  base: number;
  positive: number;
  negative: number;
  isTotal: boolean;
  value: number;
  cumulative: number;
}

function transformData(data: Record<string, unknown>[], xKey: string, yKey: string): WaterfallRow[] {
  let cumulative = 0;
  return data.map(d => {
    const name = String(d[xKey] ?? '');
    const value = Number(d[yKey] ?? 0);
    const prev = cumulative;
    cumulative += value;
    if (value >= 0) {
      return { name, base: prev, positive: value, negative: 0, isTotal: false, value, cumulative };
    } else {
      return { name, base: cumulative, positive: 0, negative: Math.abs(value), isTotal: false, value, cumulative };
    }
  });
}

export function WaterfallChartView({ data, config, height = 300 }: ChartViewProps) {
  const rows = transformData(data, config.xAxis, config.yAxis[0] ?? '');

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <ComposedChart data={rows} barCategoryGap="30%">
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
        <XAxis dataKey="name" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
        <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
        <ReferenceLine y={0} stroke="hsl(var(--border))" />
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          formatter={(_: unknown, __: string | undefined, props: { payload?: WaterfallRow }) => [
            props.payload?.value?.toLocaleString() ?? '', config.yAxis[0] ?? '',
          ] as [string, string]}
        />
        {/* 투명 spacer */}
        <Bar dataKey="base" stackId="wf" fill="transparent" />
        {/* 양수 bar (녹색) */}
        <Bar dataKey="positive" stackId="wf" radius={[2, 2, 0, 0]}>
          {rows.map((_, i) => <Cell key={i} fill="hsl(142 72% 45%)" />)}
        </Bar>
        {/* 음수 bar (빨간색) */}
        <Bar dataKey="negative" stackId="wf" radius={[2, 2, 0, 0]}>
          {rows.map((_, i) => <Cell key={i} fill="hsl(0 72% 55%)" />)}
        </Bar>
      </ComposedChart>
    </ResponsiveContainer>
  );
}
