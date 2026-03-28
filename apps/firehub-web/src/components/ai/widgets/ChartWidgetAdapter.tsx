import type { ChartConfig, ChartType } from '../../../types/analytics';
import { InlineChartWidget } from '../InlineChartWidget';
import type { WidgetProps } from './types';

interface ShowChartInput {
  sql: string;
  chartType: ChartType;
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}

export default function ChartWidgetAdapter({ input }: WidgetProps<ShowChartInput>) {
  return (
    <InlineChartWidget
      sql={String(input.sql || '')}
      chartType={input.chartType}
      config={input.config}
      columns={input.columns || []}
      rows={input.rows || []}
    />
  );
}
