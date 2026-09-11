import { useState } from 'react';
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

// 슬라이스 직접 라벨을 생략하고 범례+툴팁만 사용하기 시작하는 카테고리 수 임계값 (#642).
// 이 이상이면 라벨 텍스트가 파이 둘레를 가득 채워 범례와 겹칠 수밖에 없다.
const LABEL_SUPPRESS_THRESHOLD = 8;
// 범례 한 항목이 차지한다고 가정하는 평균 폭(px) — 스와치+텍스트 여유 포함.
const LEGEND_ITEM_WIDTH = 100;
// 범례 한 줄의 높이(px).
const LEGEND_ROW_HEIGHT = 20;

export function PieChartView({ chartType, config, data, height }: PieChartViewProps) {
  const { xAxis, yAxis, showLegend = true, colors } = config;
  const palette = colors?.length ? colors : CHART_SERIES_COLORS;

  // Use first yAxis value as the value key; xAxis as name key
  const valueKey = yAxis[0] ?? '';
  const nameKey = xAxis;

  const isDonut = chartType === 'DONUT';
  const categoryCount = data.length;
  // 카테고리가 많으면 조각 위 텍스트 라벨을 생략하고 범례로만 안내한다 (겹침 방지).
  const showSliceLabels = categoryCount <= LABEL_SUPPRESS_THRESHOLD;

  // ResponsiveContainer가 실제로 측정한 컨테이너 크기 — outerRadius를 여기에 맞춰 동적으로 줄인다.
  // 초기값은 height prop(고정 높이 모드) 또는 300(fillParent 기본 가정)으로 두어
  // 최초 렌더 시 recharts가 경고하는 "width(-1)/height(-1)" 상태를 피한다.
  const [measured, setMeasured] = useState({ width: 400, height: typeof height === 'number' ? height : 300 });

  const numericWidth = measured.width;
  const numericHeight = measured.height;

  // 범례가 차지할 세로 공간을 카테고리 수 기준으로 추정한다.
  // (실제 렌더된 범례 DOM을 측정하지 않는 이유: recharts Legend는 Pie와 달리
  //  플롯 영역을 자동으로 줄여주지 않아 outerRadius 계산 전에 미리 예약해야 한다.)
  const itemsPerRow = Math.max(1, Math.floor(numericWidth / LEGEND_ITEM_WIDTH));
  const legendRows = showLegend ? Math.ceil(categoryCount / itemsPerRow) : 0;
  const legendReservedHeight = showLegend ? 8 + legendRows * LEGEND_ROW_HEIGHT : 0;

  // 라벨을 그릴 때는 조각 바깥으로 텍스트가 삐져나오므로 여백을 더 크게 잡는다.
  const labelMargin = showSliceLabels ? 40 : 16;

  // 최종 outerRadius: 세로는 범례 예약 공간 + 라벨 여백을, 가로는 좌우 여백을 뺀 값 중 작은 쪽을 쓴다.
  // 최소 24px은 보장해 극단적으로 작은 위젯에서도 파이 자체가 사라지지 않게 한다.
  const outerRadius = Math.max(
    24,
    Math.min(
      (numericHeight - legendReservedHeight) / 2 - labelMargin,
      numericWidth / 2 - 24,
      120,
    ),
  );

  return (
    <ResponsiveContainer
      width="100%"
      height={height ?? '100%'}
      initialDimension={{ width: numericWidth, height: numericHeight }}
      onResize={(width, resizedHeight) => setMeasured({ width, height: resizedHeight })}
    >
      <PieChart margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
        <Pie
          data={data}
          dataKey={valueKey}
          nameKey={nameKey}
          cx="50%"
          cy="50%"
          innerRadius={isDonut ? 60 : 0}
          outerRadius={outerRadius}
          paddingAngle={2}
          label={
            showSliceLabels
              ? ({ name, percent }: { name?: string; percent?: number }) =>
                  (percent ?? 0) > 0.05 ? `${name ?? ''} (${((percent ?? 0) * 100).toFixed(0)}%)` : ''
              : false
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
