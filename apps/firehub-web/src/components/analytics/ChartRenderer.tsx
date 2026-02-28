import type { ChartConfig,ChartType } from '../../types/analytics';
import { AreaChartView } from './AreaChartView';
import { BarChartView } from './BarChartView';
import { LineChartView } from './LineChartView';
import { PieChartView } from './PieChartView';
import { ScatterChartView } from './ScatterChartView';
import { TableView } from './TableView';

export interface ChartRendererProps {
  chartType: ChartType;
  config: ChartConfig;
  data: Record<string, unknown>[];
  columns: string[];
  width?: number;
  height?: number;
}

export function ChartRenderer({ chartType, config, data, columns, height = 300 }: ChartRendererProps) {
  if (!config.xAxis || config.yAxis.length === 0) {
    return (
      <div className="flex items-center justify-center text-muted-foreground text-sm" style={{ height }}>
        X축과 Y축을 설정하세요.
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div className="flex items-center justify-center text-muted-foreground text-sm" style={{ height }}>
        데이터가 없습니다.
      </div>
    );
  }

  switch (chartType) {
    case 'BAR':
      return <BarChartView config={config} data={data} height={height} />;
    case 'LINE':
      return <LineChartView config={config} data={data} height={height} />;
    case 'AREA':
      return <AreaChartView config={config} data={data} height={height} />;
    case 'PIE':
    case 'DONUT':
      return <PieChartView chartType={chartType} config={config} data={data} height={height} />;
    case 'SCATTER':
      return <ScatterChartView config={config} data={data} height={height} />;
    case 'TABLE':
      return <TableView columns={columns} data={data} height={height} />;
    default:
      return (
        <div className="flex items-center justify-center text-muted-foreground text-sm" style={{ height }}>
          지원하지 않는 차트 타입입니다.
        </div>
      );
  }
}
