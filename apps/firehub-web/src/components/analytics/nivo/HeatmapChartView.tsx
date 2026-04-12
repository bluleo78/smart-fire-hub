// apps/firehub-web/src/components/analytics/nivo/HeatmapChartView.tsx
// 2차원 교차 데이터를 색상 농도로 표현. 시간대×요일 패턴 분석에 적합.
// @nivo/heatmap을 사용하며 CSS 변수로 다크모드를 동기화한다.
import { ResponsiveHeatMap } from '@nivo/heatmap';
import { useMemo } from 'react';

import type { ChartViewProps } from '../chart-view-props';

/** CSS 변수를 읽어 Nivo theme 객체를 생성 */
function useNivoTheme() {
  // CSS 변수 직접 읽기 — next-themes의 다크/라이트 전환에 따라 재계산
  const textColor = 'hsl(var(--foreground))';
  const gridColor = 'hsl(var(--border))';
  const tooltipBg = 'hsl(var(--popover))';
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

export function HeatmapChartView({ data, config, height = 300 }: ChartViewProps) {
  const theme = useNivoTheme();
  const valueKey = config.valueColumn ?? config.yAxis[0] ?? 'value';
  const nivoData = useMemo(
    () => toNivoFormat(data, config.xAxis, config.yAxis[0] ?? '', valueKey),
    [data, config.xAxis, config.yAxis, valueKey],
  );

  if (nivoData.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
        데이터가 없습니다.
      </div>
    );
  }

  return (
    <div style={{ height: height ?? '100%' }}>
      <ResponsiveHeatMap
        data={nivoData}
        theme={theme}
        margin={{ top: 40, right: 60, bottom: 40, left: 80 }}
        colors={{ type: 'sequential', scheme: 'blues' }}
        axisTop={null}
        axisLeft={{ tickSize: 5, tickPadding: 5 }}
        axisBottom={{ tickSize: 5, tickPadding: 5 }}
        labelTextColor={{ from: 'color', modifiers: [['darker', 2]] }}
        animate={false}
      />
    </div>
  );
}
