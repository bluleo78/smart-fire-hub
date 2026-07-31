import {
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { CHART_LEGEND_FORMATTER } from './chart-legend';
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from './chart-styles';

interface PieChartViewProps {
  chartType: ChartType;
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function PieChartView({ chartType, config, data, height }: PieChartViewProps) {
  const { xAxis, yAxis, showLegend = true, colors } = config;
  const palette = colors?.length ? colors : CHART_SERIES_COLORS;

  // Use first yAxis value as the value key; xAxis as name key
  const valueKey = yAxis[0] ?? '';
  const numericHeight = typeof height === 'number' ? height : 300;
  const nameKey = xAxis;

  const isDonut = chartType === 'DONUT';

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <PieChart margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
        <Pie
          data={data}
          dataKey={valueKey}
          nameKey={nameKey}
          cx="50%"
          cy="50%"
          innerRadius={isDonut ? 60 : 0}
          outerRadius={Math.min(numericHeight / 2 - 40, 120)}
          paddingAngle={2}
          label={({ name, percent }: { name?: string; percent?: number }) =>
            (percent ?? 0) > 0.05 ? `${name ?? ''} (${((percent ?? 0) * 100).toFixed(0)}%)` : ''
          }
          labelLine={false}
        >
          {data.map((_entry, index) => (
            <Cell key={`cell-${index}`} fill={palette[index % palette.length]} />
          ))}
        </Pie>
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          itemStyle={TOOLTIP_ITEM_STYLE}
          formatter={(value, name) => [value, name]}
        />
        {showLegend && <Legend wrapperStyle={{ fontSize: 12 }} formatter={CHART_LEGEND_FORMATTER} />}
      </PieChart>
    </ResponsiveContainer>
  );
}
