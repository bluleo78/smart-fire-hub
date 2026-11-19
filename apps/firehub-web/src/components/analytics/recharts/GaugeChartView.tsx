// apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx
// 단일 수치를 반원 게이지로 표현. config.yAxis[0] = 값 컬럼, config.min/max/target = 범위 설정.
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts';

import { computeGaugeReading } from '../chart-derived-data';
import { HIDDEN_CHART_ROOT_PROPS } from '../chart-figure-context';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';
import { isNonNumericValue } from '../numeric-columns';
import { NonNumericValueNotice } from './NonNumericValueNotice';

export function GaugeChartView(props: ChartViewProps) {
  const { data, config, height = 280 } = props;
  const valueKey = config.yAxis[0];
  // WD-505: 문자 컬럼을 값으로 고르면 Number('서울') = NaN 이 바늘·수치에 그대로 그려졌다 — 게이지 대신 안내한다.
  // 첫 행만 보면 첫 칸이 빈 문자 컬럼이 0 으로 그럴듯하게 그려지므로, 컬럼에 숫자가 아닌 값이 하나라도 있으면 안내한다.
  // 안내 높이는 기본값(280)이 아닌 원래 prop 을 넘긴다 — fillParent(undefined)면 부모 높이를 채워 가운데에 보이게.
  if (data.some((row) => isNonNumericValue(row[valueKey]))) return <NonNumericValueNotice height={props.height} />;
  // 값(빈 값은 0)과 범위(max 미설정 시 값의 120%) — WD-826: 값 대체 표(chart-a11y)와 같은 함수를 쓴다
  const { value, min, max } = computeGaugeReading(data, config);
  const pct = Math.min(Math.max((value - min) / (max - min), 0), 1);

  // 반원 배경 + 값 호
  const backgroundData = [{ value: 100 }];
  const gaugeData = [
    { value: pct * 100 },
    { value: (1 - pct) * 100 },
  ];

  // 바늘 각도: 180° (왼쪽) → 0° (오른쪽)
  const needleAngleDeg = 180 - pct * 180;
  const needleAngleRad = (needleAngleDeg * Math.PI) / 180;

  const containerH = height ?? 280;

  return (
    // WD-826: 게이지 그림(호 + 바늘·수치 오버레이)만 보조기술에서 숨긴다 — 수치는 figure 의 대체 표가 전달한다.
    // 숫자 아닌 값 안내(NonNumericValueNotice)는 위에서 그림 대신 반환하므로 숨기지 않는다.
    // PieChart 의 HIDDEN_CHART_ROOT_PROPS 는 svg 의 tabindex·role=application 을, Pie 의 rootTabIndex=-1 은
    // 조각 묶음 <g> 의 tabindex=0 을 없애 숨긴 영역 안 Tab 정지점이 생기지 않게 한다.
    <ChartDrawingArea style={{ position: 'relative', height: containerH }}>
      <ResponsiveContainer width="100%" height={containerH}>
        <PieChart {...HIDDEN_CHART_ROOT_PROPS}>
          {/* 배경 반원 */}
          <Pie
            rootTabIndex={-1}
            data={backgroundData}
            cx="50%"
            cy="75%"
            startAngle={180}
            endAngle={0}
            innerRadius="55%"
            outerRadius="75%"
            dataKey="value"
            stroke="none"
          >
            <Cell fill="var(--muted)" />
          </Pie>
          {/* 값 반원 */}
          <Pie
            rootTabIndex={-1}
            data={gaugeData}
            cx="50%"
            cy="75%"
            startAngle={180}
            endAngle={0}
            innerRadius="55%"
            outerRadius="75%"
            dataKey="value"
            stroke="none"
          >
            <Cell fill="var(--chart-1)" />
            <Cell fill="transparent" />
          </Pie>
        </PieChart>
      </ResponsiveContainer>
      {/* 바늘 + 수치 오버레이 */}
      <svg
        style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: containerH, pointerEvents: 'none' }}
        viewBox={`0 0 200 ${containerH}`}
        preserveAspectRatio="xMidYMid meet"
      >
        {/* 바늘 */}
        <line
          x1={100}
          y1={containerH * 0.75}
          x2={100 + 55 * Math.cos(needleAngleRad)}
          y2={containerH * 0.75 - 55 * Math.sin(needleAngleRad)}
          stroke="var(--foreground)"
          strokeWidth={2}
          strokeLinecap="round"
        />
        <circle cx={100} cy={containerH * 0.75} r={4} fill="var(--foreground)" />
        {/* 값 표시 */}
        <text
          x={100} y={containerH * 0.75 + 24}
          textAnchor="middle" fontSize={16} fontWeight={700}
          fill="var(--foreground)"
        >
          {value.toLocaleString()}
        </text>
        <text
          x={100} y={containerH * 0.75 + 38}
          textAnchor="middle" fontSize={10}
          fill="var(--muted-foreground)"
        >
          {min} ~ {max}
        </text>
      </svg>
    </ChartDrawingArea>
  );
}
