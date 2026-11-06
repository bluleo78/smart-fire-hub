// apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx
// 단일 수치를 반원 게이지로 표현. config.yAxis[0] = 값 컬럼, config.min/max/target = 범위 설정.
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts';

import type { ChartViewProps } from '../chart-view-props';
import { isNonNumericValue, toPlotNumber } from '../numeric-columns';
import { NonNumericValueNotice } from './NonNumericValueNotice';

export function GaugeChartView(props: ChartViewProps) {
  const { data, config, height = 280 } = props;
  const valueKey = config.yAxis[0];
  // WD-505: 문자 컬럼을 값으로 고르면 Number('서울') = NaN 이 바늘·수치에 그대로 그려졌다 — 게이지 대신 안내한다.
  // 첫 행만 보면 첫 칸이 빈 문자 컬럼이 0 으로 그럴듯하게 그려지므로, 컬럼에 숫자가 아닌 값이 하나라도 있으면 안내한다.
  // 안내 높이는 기본값(280)이 아닌 원래 prop 을 넘긴다 — fillParent(undefined)면 부모 높이를 채워 가운데에 보이게.
  if (data.some((row) => isNonNumericValue(row[valueKey]))) return <NonNumericValueNotice height={props.height} />;
  // 빈 값(null·'')은 기존처럼 0 으로 본다
  const value = toPlotNumber(data[0]?.[valueKey]) ?? 0;
  const min = config.min ?? 0;
  // max 미설정 시 실제 데이터 값의 120%를 기본값으로 사용하여 바늘이 최대값에 고착되지 않도록 함
  const max = config.max ?? (value > 0 ? Math.ceil(value * 1.2) : 100);
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
    <div style={{ position: 'relative', height: containerH }}>
      <ResponsiveContainer width="100%" height={containerH}>
        <PieChart>
          {/* 배경 반원 */}
          <Pie
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
    </div>
  );
}
