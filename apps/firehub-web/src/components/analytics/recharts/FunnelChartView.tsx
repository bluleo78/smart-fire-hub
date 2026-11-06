// apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx
// 단계별 감소 수치를 깔때기 형태로 표현. config.xAxis = 단계명, config.yAxis[0] = 수치.
import { Cell,Funnel, FunnelChart, LabelList, ResponsiveContainer, Tooltip } from 'recharts';

import { CHART_SEQ_LABEL_COLOR, seqColor, TOOLTIP_CONTENT_STYLE, TOOLTIP_ITEM_STYLE } from '../chart-styles';
import type { ChartViewProps } from '../chart-view-props';
import { isNonNumericValue, toPlotNumber } from '../numeric-columns';
import { NonNumericValueNotice } from './NonNumericValueNotice';

export function FunnelChartView(props: ChartViewProps) {
  const { data, config, height = 300 } = props;
  const valueKey = config.yAxis[0];
  // WD-505: 값 컬럼이 문자 컬럼이면 단계가 「이름: NaN」 으로 그려졌다 — 숫자로 읽히지 않는 값이 하나라도 있으면 안내한다.
  // (빈 값이 섞인 문자 컬럼도 걸러지도록 「유한수가 하나도 없을 때」가 아니라 「숫자가 아닌 값이 하나라도 있을 때」로 본다)
  if (data.some((d) => isNonNumericValue(d[valueKey]))) return <NonNumericValueNotice height={props.height} />;
  const funnelData = data.map((d, i) => ({
    name: String(d[config.xAxis] ?? ''),
    // 빈 값(null·'')은 기존처럼 0 으로 본다
    value: toPlotNumber(d[valueKey]) ?? 0,
    fill: seqColor(i),
  }));

  return (
    <ResponsiveContainer width="100%" height={height ?? '100%'}>
      <FunnelChart>
        <Tooltip contentStyle={TOOLTIP_CONTENT_STYLE} itemStyle={TOOLTIP_ITEM_STYLE} />
        <Funnel dataKey="value" data={funnelData} isAnimationActive={false}>
          {funnelData.map((entry, i) => (
            <Cell key={i} fill={entry.fill} />
          ))}
          <LabelList
            dataKey="value"
            position="center"
            content={(props) => {
              // dataKey="value"로 props.value를 직접 전달받고, index로 name 조회
              const entry = funnelData[props.index as number];
              if (!entry) return null;
              const nx = Number(props.x ?? 0), ny = Number(props.y ?? 0);
              const nw = Number(props.width ?? 0), nh = Number(props.height ?? 0);
              return (
              <text x={nx + nw / 2} y={ny + nh / 2} textAnchor="middle"
                dominantBaseline="middle" fontSize={12} fill={CHART_SEQ_LABEL_COLOR} fontWeight={500}>
                {`${entry.name}: ${entry.value.toLocaleString()}`}
              </text>
              );
            }}
          />
        </Funnel>
      </FunnelChart>
    </ResponsiveContainer>
  );
}
