import type { ChartConfig, ChartType } from '../../../types/analytics';
import { InlineChartWidget } from '../InlineChartWidget';
import type { WidgetProps } from './types';

interface ShowChartInput {
  sql: string;
  // AI가 전달하는 분석 제목 — 헤더에 표시 (없으면 차트 유형명으로 폴백)
  title?: string;
  chartType: ChartType;
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}

export default function ChartWidgetAdapter({ input }: WidgetProps<ShowChartInput>) {
  return (
    <InlineChartWidget
      sql={String(input.sql || '')}
      title={input.title}
      chartType={input.chartType}
      config={input.config}
      columns={input.columns || []}
      rows={input.rows || []}
    />
  );
}
