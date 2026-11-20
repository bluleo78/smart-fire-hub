// apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx
// 2차원 교차 데이터를 색상 농도로 표현. 시간대×요일 패턴 분석에 적합.
// @nivo/heatmap을 사용하며 CSS 변수로 다크모드를 동기화한다.
import { ResponsiveWrapper } from '@nivo/core';
import { HeatMap } from '@nivo/heatmap';
import { useMemo } from 'react';

import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';

/** CSS 변수를 읽어 Nivo theme 객체를 생성 */
function useNivoTheme() {
  // CSS 변수 직접 읽기 — next-themes의 다크/라이트 전환에 따라 재계산
  const textColor = 'var(--foreground)';
  const gridColor = 'var(--border)';
  const tooltipBg = 'var(--popover)';
  return useMemo(() => ({
    text: { fontSize: 11, fill: textColor },
    axis: {
      ticks: { text: { fill: textColor, fontSize: 10 } },
      legend: { text: { fill: textColor } },
    },
    tooltip: {
      container: {
        background: tooltipBg,
        color: textColor,
        fontSize: 12,
        border: `1px solid ${gridColor}`,
        borderRadius: 6,
      },
    },
  }), []);
}

// 히트맵 여백 — HeatMap margin 과 낮은 위젯 가드가 같은 값을 쓴다(WD-845)
const MARGIN = { top: 40, right: 60, bottom: 40, left: 80 };

/** 평탄한 행 데이터 → Nivo HeatMap 형식 변환 */
function toNivoFormat(
  data: Record<string, unknown>[],
  rowKey: string,
  colKey: string,
  valueKey: string,
) {
  const rowMap = new Map<string, Map<string, number>>();
  data.forEach(d => {
    const row = String(d[rowKey] ?? '');
    const col = String(d[colKey] ?? '');
    const val = Number(d[valueKey] ?? 0);
    if (!rowMap.has(row)) rowMap.set(row, new Map());
    rowMap.get(row)!.set(col, val);
  });
  return Array.from(rowMap.entries()).map(([id, cols]) => ({
    id,
    data: Array.from(cols.entries()).map(([x, y]) => ({ x, y })),
  }));
}

// height 가 없으면 부모를 채운다 — ChartViewProps 참고(WD-845)
export function HeatmapChartView({ data, config, height }: ChartViewProps) {
  const theme = useNivoTheme();
  const rowKey = config.xAxis;
  const colKey = config.yAxis[0] ?? '';
  const valueKey = config.valueColumn;

  // #664: valueColumn이 비어 있거나 행/열 컬럼과 겹치면 열 카테고리와 값이 같은 컬럼을
  // 오용하게 되어 유효하지 않은 격자가 그려진다 — 조용히 렌더링하지 않고 안내를 표시한다.
  const isMappingInvalid =
    !rowKey || !colKey || !valueKey || rowKey === colKey || rowKey === valueKey || colKey === valueKey;

  const nivoData = useMemo(
    () => (isMappingInvalid ? [] : toNivoFormat(data, rowKey, colKey, valueKey as string)),
    [data, rowKey, colKey, valueKey, isMappingInvalid],
  );

  if (isMappingInvalid) {
    return (
      <div className="flex items-center justify-center text-muted-foreground text-sm" style={{ height: height ?? '100%' }}>
        행/열/값(색상 기준) 컬럼을 서로 다르게 선택하세요.
      </div>
    );
  }

  if (nivoData.length === 0) {
    return (
      <div className="flex items-center justify-center text-muted-foreground text-sm" style={{ height: height ?? '100%' }}>
        데이터가 없습니다.
      </div>
    );
  }

  return (
    // WD-826: 히트맵 그림만 보조기술에서 숨긴다 — 값은 figure 의 대체 표가 전달하고, 매핑·빈 데이터 안내는 위에서 따로 반환해 읽힌다.
    <ChartDrawingArea style={{ height: height ?? '100%' }}>
      {/* WD-845: 측정 크기가 여백 합 이하(아주 낮은 위젯)면 격자 높이가 0 이하라 음수 rect 가 되므로 그리지 않는다.
          data-chart-measured 는 측정된 뒤에만 붙인다 — 시험이 측정 전 빈 상태를 통과로 오인하지 않게. width>0 검사는 방어용이다
          (AutoSizer 는 0 크기에서는 children 을 렌더하지 않는다) */}
      <ResponsiveWrapper>
        {({ width, height: h }) => (
          <div data-chart-measured={width > 0 ? 'true' : undefined} style={{ width, height: h }}>
            {h > MARGIN.top + MARGIN.bottom && (
              <HeatMap
                width={width}
                height={h}
                data={nivoData}
                theme={theme}
                margin={MARGIN}
                colors={{ type: 'sequential', scheme: 'blues' }}
                axisTop={null}
                axisLeft={{ tickSize: 5, tickPadding: 5 }}
                axisBottom={{ tickSize: 5, tickPadding: 5 }}
                labelTextColor={{ from: 'color', modifiers: [['darker', 2]] }}
                animate={false}
              />
            )}
          </div>
        )}
      </ResponsiveWrapper>
    </ChartDrawingArea>
  );
}
