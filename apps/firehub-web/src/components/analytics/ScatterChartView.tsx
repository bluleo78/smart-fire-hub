import {
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
  ZAxis,
} from 'recharts';

import type { ChartConfig } from '../../types/analytics';
import { CHART_LEGEND_FORMATTER } from './chart-legend';
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from './chart-styles';

interface ScatterChartViewProps {
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function ScatterChartView({ config, data, height }: ScatterChartViewProps) {
  const { xAxis, yAxis, showLegend = true, showGrid = true, colors } = config;
  const palette = colors?.length ? colors : CHART_SERIES_COLORS;

  // If groupBy is present, split data into groups; otherwise render all in one scatter
  const { groupBy } = config;

  type ScatterData = { x: unknown; y: unknown }[];

  let scatterGroups: { name: string; data: ScatterData; color: string }[];

  if (groupBy) {
    const groups = new Map<string, ScatterData>();
    for (const row of data) {
      const groupVal = String(row[groupBy] ?? 'unknown');
      if (!groups.has(groupVal)) groups.set(groupVal, []);
      groups.get(groupVal)!.push({ x: row[xAxis], y: row[yAxis[0]] });
    }
    scatterGroups = Array.from(groups.entries()).map(([name, d], i) => ({
      name,
      data: d,
      color: palette[i % palette.length],
    }));
  } else {
    scatterGroups = [
      {
        name: yAxis[0] ?? 'value',
        data: data.map((row) => ({ x: row[xAxis], y: row[yAxis[0]] })),
        color: palette[0],
      },
    ];
  }

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <ScatterChart margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
        {showGrid && <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />}
        <XAxis
          dataKey="x"
          name={xAxis}
          type="number"
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
          dataKey="y"
          name={yAxis[0]}
          type="number"
          tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
          tickLine={false}
          axisLine={false}
          label={
            config.yAxisLabel
              ? { value: config.yAxisLabel, angle: -90, position: 'insideLeft', fontSize: 12 }
              : undefined
          }
        />
        <ZAxis range={[40, 40]} />
        <Tooltip
          cursor={{ strokeDasharray: '3 3', stroke: 'var(--muted-foreground)' }}
          contentStyle={TOOLTIP_CONTENT_STYLE}
          itemStyle={TOOLTIP_ITEM_STYLE}
        />
        {showLegend && <Legend wrapperStyle={{ fontSize: 12 }} formatter={CHART_LEGEND_FORMATTER} />}
        {scatterGroups.map((g) => (
          <Scatter key={g.name} name={g.name} data={g.data} fill={g.color} opacity={0.7} />
        ))}
      </ScatterChart>
    </ResponsiveContainer>
  );
}
