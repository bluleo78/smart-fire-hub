// apps/firehub-web/src/components/analytics/recharts/RadarChartView.tsx
// 카테고리별 여러 지표를 방사형으로 비교. config.xAxis = 카테고리(라벨), config.yAxis = 수치 컬럼들.
import {
  Legend, PolarAngleAxis, PolarGrid, PolarRadiusAxis,
Radar,   RadarChart, ResponsiveContainer,
Tooltip, } from 'recharts';

import { HIDDEN_CHART_ROOT_PROPS } from '../chart-figure-context';
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';

export function RadarChartView({ data, config, height = 300 }: ChartViewProps) {
  return (
    <ChartDrawingArea>
      {/* WD-826: 그림은 숨기고 대체 표가 값을 전한다 */}
      <ResponsiveContainer width="100%" height={height ?? '100%'}>
        <RadarChart {...HIDDEN_CHART_ROOT_PROPS} data={data}>
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
            <Legend wrapperStyle={{ fontSize: 11 }} />
          )}
        </RadarChart>
      </ResponsiveContainer>
    </ChartDrawingArea>
  );
}
