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
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from './chart-styles';
import { toPlotNumber } from './numeric-columns';

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

  type ScatterData = { x: number; y: number }[];

  // 두 축 모두 숫자 축이라 숫자로 읽히는 값만 점이 된다(WD-308). X 는 행마다 한 번만 읽어 계열마다 다시 읽지 않는다
  const xs = data.map((row) => toPlotNumber(row[xAxis]));
  const points = (rows: number[], yKey: string | undefined): ScatterData => {
    if (yKey === undefined) return [];
    const out: ScatterData = [];
    for (const i of rows) {
      const x = xs[i];
      const y = toPlotNumber(data[i][yKey]);
      if (x !== null && y !== null) out.push({ x, y });
    }
    return out;
  };
  const allRows = data.map((_, i) => i);

  // 그릴 수 없는 설정이면 빈 격자 대신 이유를 알린다 — 히스토그램과 같은 수준(WD-308).
  // X 가 숫자가 아니면(다른 차트 타입에서 바꿔 문자 컬럼이 남은 경우 등), Y 에 숫자 컬럼이 없으면 점이 하나도 없다.
  const usedY = groupBy ? yAxis.slice(0, 1) : yAxis;
  const emptyReason =
    data.length === 0
      ? null
      : !xs.some((x) => x !== null)
        ? '산점도의 X축은 숫자 컬럼이어야 합니다'
        : !usedY.some((yKey) => data.some((row) => toPlotNumber(row[yKey]) !== null))
          ? '산점도의 Y축에 숫자 컬럼을 하나 이상 고르세요'
          : null;
  if (emptyReason) {
    return (
      <div style={{ height }} className="flex h-full items-center justify-center text-sm text-muted-foreground">
        {emptyReason}
      </div>
    );
  }

  let scatterGroups: { name: string; data: ScatterData; color: string }[];

  if (groupBy) {
    // 그룹이 계열이 되므로 Y축은 첫 번째 컬럼만 쓴다
    const groups = new Map<string, number[]>();
    data.forEach((row, i) => {
      const groupVal = String(row[groupBy] ?? 'unknown');
      if (!groups.has(groupVal)) groups.set(groupVal, []);
      groups.get(groupVal)!.push(i);
    });
    scatterGroups = Array.from(groups.entries()).map(([name, rows], i) => ({
      name,
      data: points(rows, yAxis[0]),
      color: palette[i % palette.length],
    }));
  } else if (yAxis.length > 0) {
    // Y축을 여러 개 고르면 각각 계열로 그린다 — 전에는 첫 번째만 쓰고 나머지는 말없이 빠졌다(WD-308)
    scatterGroups = yAxis.map((yKey, i) => ({
      name: yKey,
      data: points(allRows, yKey),
      color: palette[i % palette.length],
    }));
  } else {
    scatterGroups = [{ name: 'value', data: [], color: palette[0] }];
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
          // 계열이 여럿이면(Y축 여러 개) 툴팁의 y 이름을 한 컬럼으로 단정하지 않는다 — 어느 계열인지는 범례 색으로 본다
          name={usedY.length === 1 ? usedY[0] : '값'}
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
        {showLegend && <Legend wrapperStyle={{ fontSize: 12 }} />}
        {scatterGroups.map((g) => (
          <Scatter key={g.name} name={g.name} data={g.data} fill={g.color} opacity={0.7} />
        ))}
      </ScatterChart>
    </ResponsiveContainer>
  );
}
