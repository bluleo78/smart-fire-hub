import { AlertTriangle } from 'lucide-react';
import { useMemo, useState } from 'react';
import {
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { splitPieData } from './chart-derived-data';
import { HIDDEN_CHART_ROOT_PROPS } from './chart-figure-context';
import { CHART_SERIES_COLORS, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from './chart-styles';
import { ChartDrawingArea } from './ChartDrawingArea';
import { NonNumericValueNotice } from './recharts/NonNumericValueNotice';

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

  // WD-303: 파이·도넛은 전체에서 차지하는 비율을 그리는 차트라 음수를 표현할 수 없다. 음수를 그대로 넘기면 recharts 가
  // 음수를 포함한 합계로 비율을 계산해 「인천 (135%)」처럼 100% 를 넘는 라벨을 내고 음수 조각을 다른 조각 위에 겹쳐 그린다.
  // 음수 행은 그리기·비율 계산에서 빼고, 몇 개를 뺐는지 차트 위에 안내한다(빼고 말하지 않으면 합계를 잘못 읽는다).
  // 거르기는 값 대체 표와 공유하는 splitPieData 로 한다(WD-831). 새 배열을 만들므로 입력이 같으면 다시 만들지 않게 메모한다
  // (렌더마다 새 배열이면 recharts 가 조각을 다시 그린다).
  // WD-835: 값이 비었거나 숫자가 아닌 행(missingCount)도 0 이 아니라 「없음」이라 같은 방식으로 빼고 안내한다.
  const { pieData, negativeCount, missingCount, nonNumericCount } = useMemo(
    () => splitPieData(data, valueKey),
    [data, valueKey],
  );
  // WD-838: 그릴 조각의 값 합 — 전부 0 이면 조각이 각도 0 이라 아무것도 그려지지 않는다(빈 안내 대상).
  const pieTotal = useMemo(() => pieData.reduce((sum, r) => sum + (r[valueKey] as number), 0), [pieData, valueKey]);

  const categoryCount = pieData.length;
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
  // 항목이 많은지 — 안내 줄뿐 아니라 애니메이션·조각 테두리도 이 값으로 끈다(WD-307)
  const showManyNotice = categoryCount > MANY_CATEGORY_THRESHOLD;
  const chartLabel = isDonut ? '도넛' : '파이';
  // 차트 위 안내 줄 목록 — 보일 줄만 담아, 그리는 줄과 아래 여백 계산이 늘 같은 목록을 쓰게 한다(WD-835).
  // warn 은 경고 아이콘을 붙일지(뺀 행이 있다는 안내), 순서는 화면에 보이는 순서다.
  // WD-838: 그릴 조각이 없다 — 행이 없거나 전부 0(합계 0)이면 빈 원 대신 이유를 말한다.
  // 많은 항목 안내(막대 권장)도 이때는 그릴 것이 없어 어긋나므로 줄에 넣지 않는다.
  const nothingToDraw = pieData.length === 0 || pieTotal <= 0;
  const notices: { testId: string; warn: boolean; text: string }[] = [];
  if (negativeCount > 0) {
    // WD-303: 음수 행을 뺐다는 안내 — 빼고 말하지 않으면 비율·합계를 잘못 읽는다.
    notices.push({
      testId: 'pie-negative-notice',
      warn: true,
      text: `음수 값 ${negativeCount}개는 ${chartLabel} 차트에 표시되지 않습니다. 음수까지 보려면 막대 차트를 쓰세요.`,
    });
  }
  if (missingCount > 0) {
    // WD-835: 결측 행을 뺐다는 안내 — 0 으로 싣지 않고 뺐으니 뺀 개수를 말해야 합계를 잘못 읽지 않는다.
    notices.push({
      testId: 'pie-missing-notice',
      warn: true,
      text: `값이 없거나 숫자가 아닌 항목 ${missingCount}개는 ${chartLabel} 차트에 표시되지 않습니다.`,
    });
  }
  if (showManyNotice && !nothingToDraw) {
    notices.push({
      testId: 'pie-many-categories-notice',
      warn: false,
      // WD-838: 도넛에서도 「파이 차트」라 말하던 문구를 차트 종류에 맞춘다.
      text: `항목이 ${categoryCount.toLocaleString()}개라 ${chartLabel} 차트로 읽기 어렵습니다 — 막대 차트를 권장합니다.`,
    });
  }
  // 안내 줄마다 한 줄 높이를 파이 위쪽 여백으로 비워 둔다.
  const noticeHeight = notices.length * NOTICE_HEIGHT;

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

  // WD-838: 그릴 행도 뺀 음수도 없이 숫자 아닌 값만 있으면 값 컬럼 전체가 문자다 — 컬럼을 다시 고르라고 안내한다.
  // 의도적으로 퍼널·게이지와 다르다: 파이는 섞인 숫자 아닌 행을 빼고 그리므로(WD-835) 컬럼 전체가 숫자 아닐 때만 컬럼 안내를 낸다.
  // null 만 있는 컬럼은 nonNumericCount 가 0 이라 빈 안내가 나온다. 훅을 모두 호출한 뒤에 반환해 훅 규칙을 지킨다.
  const allNonNumeric = pieData.length === 0 && negativeCount === 0 && nonNumericCount > 0;
  if (allNonNumeric) {
    return <NonNumericValueNotice height={height} />;
  }
  return (
    // 안내 문구를 차트 위에 겹쳐 두기 위한 기준 상자 — 높이는 ResponsiveContainer 와 같게 둔다.
    <div className="relative w-full" style={{ height: height ?? '100%' }}>
      {notices.length > 0 && (
        // 한 줄씩 자르고 포인터를 통과시킨다 — 좁은 위젯에서 여러 줄로 감겨 파이를 덮거나 조각 툴팁을 막지 않게.
        // 계속 보이는 정적 안내라 role=alert 를 붙이지 않는다(ChartRenderer 의 컬럼 안내와 같은 판단).
        <div className="pointer-events-none absolute inset-x-0 top-0 z-10 text-xs text-muted-foreground">
          {notices.map(({ testId, warn, text }) =>
            warn ? (
              <p key={testId} className="flex items-center gap-1 truncate" data-testid={testId}>
                <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-warning" aria-hidden="true" />
                {text}
              </p>
            ) : (
              <p key={testId} className="truncate" data-testid={testId}>
                {text}
              </p>
            ),
          )}
        </div>
      )}
      {nothingToDraw ? (
        // 위 안내 줄(절대 배치)에 가리지 않게 줄 높이만큼 위를 비운다. 안내 줄은 그대로 둔다.
        <p
          data-testid="pie-empty-notice"
          className="flex h-full items-center justify-center text-sm text-muted-foreground"
          style={{ paddingTop: noticeHeight }}
        >
          {chartLabel} 차트로 그릴 값이 없습니다.
        </p>
      ) : (
        <>
          {/* WD-826: 그림만 보조기술에서 숨긴다 — 위 안내문은 화면에 보이는 정보라 밖에 두어 스크린리더가 읽는다.
              PieChart 의 HIDDEN_CHART_ROOT_PROPS 는 svg 의 tabindex·role=application 을, Pie 의 rootTabIndex=-1 은
              조각 묶음 <g> 의 tabindex=0 을 없애 숨긴 영역 안 Tab 정지점이 생기지 않게 한다(마우스 툴팁은 그대로). */}
          <ChartDrawingArea>
            <ResponsiveContainer
              width="100%"
              height="100%"
              initialDimension={{ width: numericWidth, height: numericHeight }}
              onResize={(width, resizedHeight) => setMeasured({ width, height: resizedHeight })}
            >
              <PieChart {...HIDDEN_CHART_ROOT_PROPS} margin={{ top: 8 + noticeHeight, right: 16, left: 0, bottom: 8 }}>
                <Pie
                  // WD-826: recharts Pie 는 조각 묶음 <g> 에 rootTabIndex(기본 0)를 붙여 숨긴 그림 안 Tab 정지점이 된다 — -1 로 끈다
                  rootTabIndex={-1}
                  data={pieData}
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
                  {pieData.map((_entry, index) => (
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
                  />
                )}
              </PieChart>
            </ResponsiveContainer>
          </ChartDrawingArea>
        </>
      )}
    </div>
  );
}
