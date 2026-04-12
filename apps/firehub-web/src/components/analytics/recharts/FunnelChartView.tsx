// apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx
// 단계별 감소 수치를 깔때기 형태로 표현. config.xAxis = 단계명, config.yAxis[0] = 수치.
import { Cell,Funnel, FunnelChart, LabelList, ResponsiveContainer, Tooltip } from 'recharts';

import { TOOLTIP_CONTENT_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';

const COLORS = [
  'hsl(220 70% 50%)', 'hsl(220 65% 58%)', 'hsl(220 60% 65%)',
  'hsl(220 55% 72%)', 'hsl(220 50% 79%)', 'hsl(220 45% 85%)',
];

export function FunnelChartView({ data, config, height = 300 }: ChartViewProps) {
  const funnelData = data.map((d, i) => ({
    name: String(d[config.xAxis] ?? ''),
    value: Number(d[config.yAxis[0]] ?? 0),
    fill: COLORS[i % COLORS.length],
  }));

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <FunnelChart>
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} />
        <Funnel dataKey="value" data={funnelData} isAnimationActive={false}>
          {funnelData.map((entry, i) => (
            <Cell key={i} fill={entry.fill} />
          ))}
          <LabelList
            position="center"
            content={(props) => {
              const nx = Number(props.x ?? 0), ny = Number(props.y ?? 0);
              const nw = Number(props.width ?? 0), nh = Number(props.height ?? 0);
              return (
              <text x={nx + nw / 2} y={ny + nh / 2} textAnchor="middle"
                dominantBaseline="middle" fontSize={12} fill="#fff" fontWeight={500}>
                {`${String(props.name ?? '')}: ${Number(props.value).toLocaleString()}`}
              </text>
              );
            }}
          />
        </Funnel>
      </FunnelChart>
    </ResponsiveContainer>
  );
}
