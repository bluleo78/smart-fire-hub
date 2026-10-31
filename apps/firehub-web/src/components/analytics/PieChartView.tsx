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
// 범례가 차지할 수 있는 최대 비율(컨테이너 높이 대비) — 넘는 범례는 이 높이 안에서 스크롤된다(WD-307).
// 제한이 없으면 카테고리가 수백 개일 때 범례가 카드 위로 넘쳐 제목을 덮고 파이를 가린다.
const LEGEND_MAX_HEIGHT_RATIO = 0.4;
// 이 수를 넘는 카테고리는 파이로 읽기 어렵다고 안내한다(WD-307). 데이터를 상위 N개+기타로 묶지는 않는다 — 값의 의미가 바뀐다.
const MANY_CATEGORY_THRESHOLD = 50;
// 안내 문구 한 줄이 차지하는 높이(px) — 파이 위쪽 여백으로 비워 둔다.
const NOTICE_HEIGHT = 24;
// 조각 사이 여백(2°)을 둘 수 있는 최대 항목 수 — 넘으면 여백 합이 90° 를 넘어 파이가 크게 비거나 음수 각도가 된다(WD-307).
const PADDING_MAX_CATEGORIES = 45;

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
  // 범례 높이를 상한으로 자른다 — 나머지는 범례 안에서 스크롤해 보므로, 파이 자리는 늘 남는다(WD-307).
  const legendMaxHeight = Math.floor(numericHeight * LEGEND_MAX_HEIGHT_RATIO);
  const legendReservedHeight = showLegend
    ? Math.min(8 + legendRows * LEGEND_ROW_HEIGHT, legendMaxHeight)
    : 0;
  const showManyNotice = categoryCount > MANY_CATEGORY_THRESHOLD;
  const noticeHeight = showManyNotice ? NOTICE_HEIGHT : 0;

  // 라벨을 그릴 때는 조각 바깥으로 텍스트가 삐져나오므로 여백을 더 크게 잡는다.
  const labelMargin = showSliceLabels ? 40 : 16;

  // 최종 outerRadius: 세로는 범례 예약 공간 + 라벨 여백을, 가로는 좌우 여백을 뺀 값 중 작은 쪽을 쓴다.
  // 최소 24px은 보장해 극단적으로 작은 위젯에서도 파이 자체가 사라지지 않게 한다.
  const outerRadius = Math.max(
    24,
    Math.min(
      (numericHeight - legendReservedHeight - noticeHeight) / 2 - labelMargin,
      numericWidth / 2 - 24,
      120,
    ),
  );

  return (
    // 안내 문구를 차트 위에 겹쳐 두기 위한 기준 상자 — 높이는 ResponsiveContainer 와 같게 둔다.
    <div className="relative w-full" style={{ height: height ?? '100%' }}>
      {showManyNotice && (
        // 한 줄로 자르고 포인터를 통과시킨다 — 좁은 위젯에서 여러 줄로 감겨 파이를 덮거나 조각 툴팁을 막지 않게.
        <p
          className="pointer-events-none absolute inset-x-0 top-0 z-10 truncate text-xs text-muted-foreground"
          data-testid="pie-many-categories-notice"
        >
          항목이 {categoryCount.toLocaleString()}개라 파이 차트로 읽기 어렵습니다 — 막대 차트를 권장합니다.
        </p>
      )}
      <ResponsiveContainer
        width="100%"
        height="100%"
        initialDimension={{ width: numericWidth, height: numericHeight }}
        onResize={(width, resizedHeight) => setMeasured({ width, height: resizedHeight })}
      >
        <PieChart margin={{ top: 8 + noticeHeight, right: 16, left: 0, bottom: 8 }}>
          <Pie
            data={data}
            dataKey={valueKey}
            nameKey={nameKey}
            cx="50%"
            cy="50%"
            innerRadius={isDonut ? 60 : 0}
            outerRadius={outerRadius}
            // 조각 사이 여백은 (항목 수-1)×각도만큼 360° 에서 빠진다 — 항목이 많으면 합이 360° 를 넘어 조각이 음수 각도로 겹쳐
            // 그려지므로 많은 항목에서는 여백을 없앤다(WD-307).
            paddingAngle={categoryCount > PADDING_MAX_CATEGORIES ? 0 : 2}
            // 수백 개 조각의 진입 애니메이션은 대시보드를 무겁게 만든다 — 많은 항목이면 끈다(WD-307).
            isAnimationActive={!showManyNotice}
            // 조각이 1° 도 안 되면 기본 흰 테두리(1px)가 조각을 덮어 파이가 속 빈 고리처럼 보인다 — 많은 항목이면 테두리를 없앤다(WD-307).
            stroke={showManyNotice ? 'none' : undefined}
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
          {showLegend && (
            <Legend
              // 범례는 상한 높이 안에서 스크롤된다 — 카드 밖·제목 위로 넘치지 않게(WD-307).
              wrapperStyle={{ fontSize: 12, maxHeight: legendMaxHeight, overflowY: 'auto' }}
              formatter={CHART_LEGEND_FORMATTER}
            />
          )}
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
