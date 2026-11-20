// apps/firehub-web/src/components/analytics/recharts/WaterfallChartView.tsx
// 누적 증감을 폭포 형태로 표현. config.xAxis = 카테고리, config.yAxis[0] = 변화량(양수/음수 모두 허용).
// 투명한 base bar + 색상 bar 스택으로 floating bar를 구현한다.
import { useMemo } from 'react';
import {
Bar, CartesianGrid, Cell,   ComposedChart, ReferenceLine,
  ResponsiveContainer, Tooltip,
XAxis, YAxis, } from 'recharts';

import { transformWaterfallData, type WaterfallRow } from '../chart-derived-data';
import { HIDDEN_CHART_ROOT_PROPS } from '../chart-figure-context';
import { TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';

export function WaterfallChartView({ data, config, height = 300 }: ChartViewProps) {
  // WD-826: 누적 계산은 값 대체 표(chart-a11y)와 같은 함수를 쓴다 — 표가 단계별 증감과 누적을 함께 전하게
  const valueKey = config.yAxis[0] ?? '';
  const { rows, overflow } = useMemo(
    () => transformWaterfallData(data, config.xAxis, valueKey),
    [data, config.xAxis, valueKey],
  );

  // WD-844: 누적이 유한하지 않으면 Infinity 막대·축 대신 안내만 보인다(파이 pie-empty-notice 와 같은 모양).
  // 그림이 없으므로 ChartDrawingArea 밖에 두어 스크린리더가 읽게 한다.
  if (overflow) {
    return (
      <p
        data-testid="waterfall-overflow-notice"
        className="flex w-full items-center justify-center text-sm text-muted-foreground"
        style={{ height: height ?? '100%' }}
      >
        값의 누적이 너무 커서 폭포 차트로 그릴 수 없습니다.
      </p>
    );
  }

  return (
    <ChartDrawingArea>
      {/* WD-826: 그림은 숨기고 대체 표가 값을 전한다 */}
      <ResponsiveContainer width="100%" height={height ?? '100%'}>
        <ComposedChart {...HIDDEN_CHART_ROOT_PROPS} data={rows} barCategoryGap="30%">
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="name" tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }} />
          <YAxis tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }} />
          <ReferenceLine y={0} stroke="var(--border)" />
          <Tooltip
            contentStyle={TOOLTIP_CONTENT_STYLE}
            itemStyle={TOOLTIP_ITEM_STYLE}
            formatter={(_: unknown, __: string | undefined, props: { payload?: WaterfallRow }) => [
              props.payload?.value?.toLocaleString() ?? '', config.yAxis[0] ?? '',
            ] as [string, string]}
          />
          {/* 투명 spacer */}
          <Bar dataKey="base" stackId="wf" fill="transparent" />
          {/* 양수 bar (녹색) */}
          <Bar dataKey="positive" stackId="wf" radius={[2, 2, 0, 0]}>
            {rows.map((_, i) => <Cell key={i} fill="var(--success)" />)}
          </Bar>
          {/* 음수 bar (빨간색) */}
          <Bar dataKey="negative" stackId="wf" radius={[2, 2, 0, 0]}>
            {rows.map((_, i) => <Cell key={i} fill="var(--destructive)" />)}
          </Bar>
        </ComposedChart>
      </ResponsiveContainer>
    </ChartDrawingArea>
  );
}
