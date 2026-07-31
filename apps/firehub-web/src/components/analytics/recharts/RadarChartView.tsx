// apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx
// 카테고리별 여러 지표를 방사형으로 비교. config.xAxis = 카테고리(라벨), config.yAxis = 수치 컬럼들.
import {
  Legend, PolarAngleAxis, PolarGrid, PolarRadiusAxis,
Radar,   RadarChart, ResponsiveContainer,
Tooltip, } from 'recharts';

import { CHART_LEGEND_FORMATTER } from '../chart-legend';
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

export function RadarChartView({ data, config, height = 300 }: ChartViewProps) {
  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <RadarChart data={data}>
        <PolarGrid stroke="var(--border)" />
        <PolarAngleAxis
          dataKey={config.xAxis}
          tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
        />
        <PolarRadiusAxis
          tick={{ fontSize: 10, fill: 'var(--muted-foreground)' }}
          axisLine={false}
        />
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} itemStyle={TOOLTIP_ITEM_STYLE} />
        {config.yAxis.map((key, i) => (
          <Radar
            key={key}
            name={key}
            dataKey={key}
            stroke={CHART_SERIES_COLORS[i % CHART_SERIES_COLORS.length]}
            fill={CHART_SERIES_COLORS[i % CHART_SERIES_COLORS.length]}
            fillOpacity={0.2}
          />
        ))}
        {config.yAxis.length > 1 && config.showLegend !== false && (
          <Legend wrapperStyle={{ fontSize: 11 }} formatter={CHART_LEGEND_FORMATTER} />
        )}
      </RadarChart>
    </ResponsiveContainer>
  );
}
