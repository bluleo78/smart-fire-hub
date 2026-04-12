// apps/firehub-web/src/components/analytics/recharts/TreemapChartView.tsx
// 계층형 데이터를 크기 비율로 표현. config.xAxis = 라벨 컬럼, config.yAxis[0] = 크기 컬럼.
import { ResponsiveContainer, Tooltip,Treemap } from 'recharts';

import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

const COLORS = [
  'hsl(220 70% 50%)', 'hsl(160 60% 45%)', 'hsl(30 80% 55%)',
  'hsl(280 65% 60%)', 'hsl(340 75% 55%)', 'hsl(200 70% 50%)',
  'hsl(100 55% 45%)', 'hsl(50 85% 50%)',
];

// 커스텀 콘텐츠: 셀에 라벨 표시
interface ContentProps {
  x?: number; y?: number; width?: number; height?: number;
  name?: string; depth?: number; index?: number;
}

const CustomContent = (props: ContentProps) => {
  const { x = 0, y = 0, width = 0, height = 0, name, depth, index = 0 } = props;
  if (depth === 0 || width < 40 || height < 20) return null;
  return (
    <g>
      <rect x={x} y={y} width={width} height={height}
        fill={COLORS[index % COLORS.length]} fillOpacity={0.85}
        stroke="hsl(var(--background))" strokeWidth={2} rx={3} />
      {width > 60 && height > 30 && (
        <text x={x + width / 2} y={y + height / 2}
          textAnchor="middle" dominantBaseline="middle"
          fontSize={Math.min(14, width / 6)} fill="#fff" fontWeight={500}>
          {name}
        </text>
      )}
    </g>
  );
};

export function TreemapChartView({ data, config, height = 300 }: ChartViewProps) {
  const treeData = data.map(d => ({
    name: String(d[config.xAxis] ?? ''),
    size: Number(d[config.yAxis[0]] ?? 0),
  }));

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <Treemap
        data={treeData}
        dataKey="size"
        content={<CustomContent />}
        isAnimationActive={false}
      >
        <Tooltip
          contentStyle={TOOLTIP_CONTENT_STYLE}
          formatter={(v, _, props: { payload?: { name?: string } }) => [v != null ? Number(v).toLocaleString() : '', props.payload?.name ?? ''] as [string, string]}
        />
      </Treemap>
    </ResponsiveContainer>
  );
}
