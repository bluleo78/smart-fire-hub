import {
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts';

import type { ChartConfig, ChartType } from '../../types/analytics';

const DEFAULT_COLORS = [
  '#8884d8',
  '#82ca9d',
  '#ffc658',
  '#ff7300',
  '#0088fe',
  '#00C49F',
  '#FFBB28',
  '#FF8042',
];

interface PieChartViewProps {
  chartType: ChartType;
  config: ChartConfig;
  data: Record<string, unknown>[];
  height?: number;
}

export function PieChartView({ chartType, config, data, height = 300 }: PieChartViewProps) {
  const { xAxis, yAxis, showLegend = true, colors } = config;
  const palette = colors?.length ? colors : DEFAULT_COLORS;

  // Use first yAxis value as the value key; xAxis as name key
  const valueKey = yAxis[0] ?? '';
  const nameKey = xAxis;

  const isDonut = chartType === 'DONUT';

  return (
    <ResponsiveContainer width="100%" height={height}>
      <PieChart margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
        <Pie
          data={data}
          dataKey={valueKey}
          nameKey={nameKey}
          cx="50%"
          cy="50%"
          innerRadius={isDonut ? 60 : 0}
          outerRadius={Math.min(height / 2 - 40, 120)}
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
          contentStyle={{
            background: 'hsl(var(--popover))',
            border: '1px solid hsl(var(--border))',
            borderRadius: 6,
            fontSize: 12,
          }}
          formatter={(value, name) => [value, name]}
        />
        {showLegend && <Legend wrapperStyle={{ fontSize: 12 }} />}
      </PieChart>
    </ResponsiveContainer>
  );
}
