// apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx
// 단일 수치를 반원 게이지로 표현. config.yAxis[0] = 값 컬럼, config.min/max/target = 범위 설정.
import { ResponsiveWrapper } from '@nivo/core';
import { Cell, Pie, PieChart } from 'recharts';

import { computeGaugeReading } from '../chart-derived-data';
import { HIDDEN_CHART_ROOT_PROPS } from '../chart-figure-context';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';
import { isNonNumericValue } from '../numeric-columns';
import { GaugeInvalidRangeNotice } from './GaugeInvalidRangeNotice';
import { NonNumericValueNotice } from './NonNumericValueNotice';

/** 잰 폭·높이 중 작은 쪽의 하한(px) — 이보다 작으면 게이지를 그리지 않는다(WD-846) */
const MIN_GAUGE_SIZE = 80;
/** 중심 아래 남은 높이가 이보다 작으면 범위 글자만 숨긴다(px, WD-846) */
const MIN_RANGE_ROOM = 40;
/** 굵은 숫자 글자 한 자의 대략 폭 ÷ 글자 크기 — 값 글자가 폭을 넘지 않게 크기를 줄이는 데 쓴다(WD-846) */
const DIGIT_WIDTH_RATIO = 0.62;
/** 글자가 차지해도 되는 폭의 비율 — 양옆에 조금 여유를 둔다(WD-846) */
const FIT_RATIO = 0.9;
/** 값 글자 크기 — 남은 높이에 맞춘 크기의 범위(px) */
const VALUE_FONT_MIN = 10;
const VALUE_FONT_MAX = 16;
/** 값 글자 크기 하한(px) — 폭에 맞추느라 이보다 작아지면 읽을 수 없다(WD-846) */
const MIN_VALUE_FONT = 8;
/** 범위 글자 크기(px) */
const RANGE_FONT = 10;
/** PieChart 기본 여백(5px)을 없애 호 중심·반지름이 오버레이 계산((W/2, 0.75h), min(W,h)/2 기준)과 같게 한다(WD-846) */
const NO_MARGIN = { top: 0, right: 0, bottom: 0, left: 0 };

/** 글자 크기 font 로 쓴 text 의 대략 폭(px) — 굵은 숫자 기준의 넉넉한 추정(쉼표·공백은 더 좁다) */
function estimateTextWidth(text: string, font: number) {
  return text.length * DIGIT_WIDTH_RATIO * font;
}

export function GaugeChartView(props: ChartViewProps) {
  // height 가 없으면 부모를 채운다 — ChartViewProps 참고(WD-846: 280 기본값 제거)
  const { data, config, height } = props;
  const valueKey = config.yAxis[0];
  // WD-505: 문자 컬럼을 값으로 고르면 Number('서울') = NaN 이 바늘·수치에 그대로 그려졌다 — 게이지 대신 안내한다.
  // 첫 행만 보면 첫 칸이 빈 문자 컬럼이 0 으로 그럴듯하게 그려지므로, 컬럼에 숫자가 아닌 값이 하나라도 있으면 안내한다.
  // 안내 높이는 원래 prop 을 넘긴다 — fillParent(undefined)면 부모 높이를 채워 가운데에 보이게.
  if (data.some((row) => isNonNumericValue(row[valueKey]))) return <NonNumericValueNotice height={props.height} />;
  // 값(빈 값은 0)과 범위(max 미설정 시 값의 120%) — WD-826: 값 대체 표(chart-a11y)와 같은 함수를 쓴다
  const { value, min, max, invalidRange } = computeGaugeReading(data, config);
  // 잘못된 범위는 그리지 않고 안내한다(이유는 computeGaugeReading, WD-848) — 숫자 아님 안내 뒤, pct 계산 전
  if (invalidRange) return <GaugeInvalidRangeNotice min={min} max={max} height={props.height} />;
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

  // 그리기 본체 — 고정 높이(숫자 height)와 측정(fillParent) 두 경로가 같은 함수를 쓴다(WD-846).
  // 오버레이는 잰 폭 W·높이 h 의 실제 픽셀 좌표로 그린다. 예전 viewBox 200×h(meet)는 폭이 200 미만이면 통째로
  // 줄어 바늘 중심이 호 중심(cx 50%, cy 75%)에서 벗어났다.
  const renderGauge = (W: number, containerH: number) => {
    // 호 중심 — 위 Pie 의 cx="50%" cy="75%" 와 같은 점
    const cx = W / 2;
    const cy = containerH * 0.75;
    // recharts 백분율 반지름은 min(W,h)/2 기준 — 바늘은 안쪽 반지름(55%)의 0.9배로 호 안쪽 끝에 닿지 않게 둔다
    const innerR = (Math.min(W, containerH) / 2) * 0.55;
    const needleLen = innerR * 0.9;
    // 중심 아래 남은 높이(0.25h)에 글자를 맞춘다 — 예전 +24/+38 고정 오프셋은 낮은 위젯에서 h 를 넘어 잘렸다
    const below = containerH - cy;
    // 값 글자는 남은 높이에 맞추되, 폭(90%)도 넘지 않게 줄인다 — 오버레이가 축소 없는 픽셀이라 80~120px 폭에서
    // 큰 값(예: 12,345,678)이 양쪽으로 잘렸다(예전 viewBox 는 통째로 줄여 맞췄다)
    const valueText = value.toLocaleString();
    const heightFont = Math.max(VALUE_FONT_MIN, Math.min(VALUE_FONT_MAX, below * 0.4));
    const fitWidthFont = (W * FIT_RATIO) / estimateTextWidth(valueText, 1);
    const valueFont = Math.max(MIN_VALUE_FONT, Math.min(heightFont, fitWidthFont));
    const valueY = cy + Math.min(below - 2, valueFont + 8);
    // 범위 글자(10px)는 값 글자 아래 자리가 있고 폭(90%)에 들어갈 때만 — 남은 높이가 40px 미만이거나 좁은 폭에서
    // 양쪽이 잘리면 숨긴다(값 글자처럼 천 단위 구분으로 맞춘다)
    const rangeText = `${min.toLocaleString()} ~ ${max.toLocaleString()}`;
    const showRange = below >= MIN_RANGE_ROOM && estimateTextWidth(rangeText, RANGE_FONT) <= W * FIT_RATIO;
    return (
      <>
        {/* PieChart 의 HIDDEN_CHART_ROOT_PROPS 는 svg 의 tabindex·role=application 을, Pie 의 rootTabIndex=-1 은
            조각 묶음 <g> 의 tabindex=0 을 없애 숨긴 영역 안 Tab 정지점이 생기지 않게 한다. */}
        {/* 크기는 바깥 ResponsiveWrapper 가 이미 쟀으므로 픽셀로 넘겨 두 번 재지 않는다(WD-846) */}
        <PieChart {...HIDDEN_CHART_ROOT_PROPS} width={W} height={containerH} margin={NO_MARGIN}>
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
        {/* 바늘 + 수치 오버레이 — 잰 크기 그대로의 픽셀 좌표(축소 없음) */}
        <svg
          style={{ position: 'absolute', top: 0, left: 0, width: W, height: containerH, pointerEvents: 'none' }}
        >
          {/* 바늘 */}
          <line
            x1={cx}
            y1={cy}
            x2={cx + needleLen * Math.cos(needleAngleRad)}
            y2={cy - needleLen * Math.sin(needleAngleRad)}
            stroke="var(--foreground)"
            strokeWidth={2}
            strokeLinecap="round"
          />
          <circle cx={cx} cy={cy} r={4} fill="var(--foreground)" />
          {/* 값 표시 */}
          <text
            x={cx} y={valueY}
            textAnchor="middle" fontSize={valueFont} fontWeight={700}
            fill="var(--foreground)"
          >
            {valueText}
          </text>
          {showRange && (
            <text
              x={cx} y={valueY + 14}
              textAnchor="middle" fontSize={RANGE_FONT}
              fill="var(--muted-foreground)"
            >
              {rangeText}
            </text>
          )}
        </svg>
      </>
    );
  };

  // WD-826: 게이지 그림(호 + 바늘·수치 오버레이)만 보조기술에서 숨긴다 — 수치는 figure 의 대체 표가 전달한다.
  // 숫자 아닌 값 안내(NonNumericValueNotice)는 위에서 그림 대신 반환하므로 숨기지 않는다.
  // 숫자 height(AI 채팅 인라인 차트 등)는 드로잉 영역 높이를 그 숫자로 둔 채, 폭을 알아야 하므로 폭만 잰다.
  // WD-846: fillParent 는 부모 크기를 재서 잰 폭·높이로 그린다(히트맵과 같은 ResponsiveWrapper). 280px 고정이면
  // 키 큰 위젯에서 아래가 비고 낮은 위젯에서는 잘렸다. 측정 전(0 크기)에는 ResponsiveWrapper 가 children 을 그리지 않아
  // 0 높이 svg·NaN 좌표가 없고, data-chart-measured 는 측정된 뒤에만 붙여 시험이 측정 뒤에 검사하게 한다.
  // 잰 폭·높이 중 작은 쪽이 MIN_GAUGE_SIZE 미만이면 호·바늘이 뭉개지므로 그리지 않는다(측정 표식은 붙인다).
  return (
    <ChartDrawingArea style={{ height: height ?? '100%' }}>
      <ResponsiveWrapper>
        {({ width, height: measuredH }) => {
          const h = height ?? measuredH;
          const tooSmall = Math.min(width, h) < MIN_GAUGE_SIZE;
          return (
            <div data-chart-measured={width > 0 ? 'true' : undefined} style={{ position: 'relative', width, height: h }}>
              {tooSmall ? null : renderGauge(width, h)}
            </div>
          );
        }}
      </ResponsiveWrapper>
    </ChartDrawingArea>
  );
}
