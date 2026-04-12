// apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx
// 카테고리별 여러 지표를 방사형으로 비교. config.xAxis = 카테고리(라벨), config.yAxis = 수치 컬럼들.
import {
  Legend, PolarAngleAxis, PolarGrid, PolarRadiusAxis,
Radar,   RadarChart, ResponsiveContainer,
Tooltip, } from 'recharts';

import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

const COLORS = ['hsl(220 70% 50%)', 'hsl(160 60% 45%)', 'hsl(30 80% 55%)', 'hsl(280 65% 60%)'];

export function RadarChartView({ data, config, height = 300 }: ChartViewProps) {
  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <RadarChart data={data}>
        <PolarGrid stroke="hsl(var(--border))" />
        <PolarAngleAxis
          dataKey={config.xAxis}
          tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
        />
        <PolarRadiusAxis
          tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }}
          axisLine={false}
        />
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} />
        {config.yAxis.map((key, i) => (
          <Radar
            key={key}
            name={key}
            dataKey={key}
            stroke={COLORS[i % COLORS.length]}
            fill={COLORS[i % COLORS.length]}
            fillOpacity={0.2}
          />
        ))}
        {config.yAxis.length > 1 && config.showLegend !== false && (
          <Legend wrapperStyle={{ fontSize: 11 }} />
        )}
      </RadarChart>
    </ResponsiveContainer>
  );
}
