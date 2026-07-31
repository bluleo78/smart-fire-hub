import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { ChartConfig } from '../../types/analytics';
import { CHART_LEGEND_FORMATTER } from './chart-legend';
import {
  CHART_SERIES_COLORS,
  formatYAxisTick,
  LINE_CURSOR_STYLE,
  TOOLTIP_CONTENT_STYLE,
  TOOLTIP_ITEM_STYLE,
} from './chart-styles';

interface AreaChartViewProps {
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function AreaChartView({ config, data, height }: AreaChartViewProps) {
  const { xAxis, yAxis, showLegend = true, showGrid = true, stacked = false, colors } = config;
  const palette = colors?.length ? colors : CHART_SERIES_COLORS;

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'} minWidth={200}>
      <AreaChart data={data} margin={{ top: 8, right: 16, left: 8, bottom: 8 }}>
        {showGrid && <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />}
        <XAxis
          dataKey={xAxis}
          tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
          tickLine={false}
          axisLine={{ stroke: 'var(--border)' }}
          label={
            config.xAxisLabel
              ? { value: config.xAxisLabel, position: 'insideBottom', offset: -4, fontSize: 12 }
              : undefined
          }
        />
        <YAxis
          tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
          tickLine={false}
          axisLine={false}
          width={80}
          tickFormatter={formatYAxisTick}
          label={
            config.yAxisLabel
              ? { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fontSize: 12 }
              : undefined
          }
        />
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} itemStyle={TOOLTIP_ITEM_STYLE} cursor={LINE_CURSOR_STYLE} />
        {showLegend && <Legend wrapperStyle={{ fontSize: 12 }} formatter={CHART_LEGEND_FORMATTER} />}
        {yAxis.map((col, i) => {
          const color = palette[i % palette.length];
          return (
            <Area
              key={col}
              type="monotone"
              dataKey={col}
              stroke={color}
              fill={color}
              fillOpacity={0.2}
              strokeWidth={2}
              stackId={stacked ? 'stack' : undefined}
            />
          );
        })}
      </AreaChart>
    </ResponsiveContainer>
  );
}
