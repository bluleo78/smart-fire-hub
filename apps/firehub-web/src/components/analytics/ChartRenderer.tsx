import type { ChartConfig, ChartType } from '../../types/analytics';
import { AreaChartView } from './AreaChartView';
import { BarChartView } from './BarChartView';
import { LineChartView } from './LineChartView';
import { MapChartView } from './MapChartView';
import { HeatmapChartView } from './nivo/HeatmapChartView';
import { PieChartView } from './PieChartView';
import { BoxPlotChartView } from './recharts/BoxPlotChartView';
import { CandlestickChartView } from './recharts/CandlestickChartView';
import { FunnelChartView } from './recharts/FunnelChartView';
import { GaugeChartView } from './recharts/GaugeChartView';
import { HistogramChartView } from './recharts/HistogramChartView';
import { RadarChartView } from './recharts/RadarChartView';
import { TreemapChartView } from './recharts/TreemapChartView';
import { WaterfallChartView } from './recharts/WaterfallChartView';
import { ScatterChartView } from './ScatterChartView';
import { TableView } from './TableView';

export interface ChartRendererProps {
  chartType: ChartType;
  config: ChartConfig;
  data: Record<string, unknown>[];
  columns: string[];
  width?: number;
  height?: number;
  /** When true, stretches to fill parent container height instead of using fixed pixels */
  fillParent?: boolean;
}

export function ChartRenderer({ chartType, config: rawConfig, data, columns, height = 300, fillParent }: ChartRendererProps) {
  // Auto-infer xAxis/yAxis from columns when not provided
  const config = { ...rawConfig };
  if ((!config.xAxis || config.yAxis.length === 0) && columns.length >= 2) {
    if (!config.xAxis) {
      config.xAxis = columns[0];
    }
    if (!config.yAxis || config.yAxis.length === 0) {
      // For numeric columns, use remaining columns as yAxis
      const remaining = columns.filter(c => c !== config.xAxis);
      config.yAxis = remaining.length > 0 ? remaining : [columns[1]];
    }
  }

  if (chartType !== 'MAP' && chartType !== 'TABLE' && (!config.xAxis || config.yAxis.length === 0)) {
    return (
      <div
        className={`flex items-center justify-center text-muted-foreground text-sm ${fillParent ? 'h-full' : ''}`}
        style={fillParent ? undefined : { height }}
      >
        {chartType === 'PIE' || chartType === 'DONUT'
          ? '데이터 필드를 설정하세요.'
          : 'X축과 Y축을 설정하세요.'}
      </div>
    );
  }

  if (data.length === 0) {
    return (
      <div
        className={`flex items-center justify-center text-muted-foreground text-sm ${fillParent ? 'h-full' : ''}`}
        style={fillParent ? undefined : { height }}
      >
        데이터가 없습니다.
      </div>
    );
  }

  let chart: React.ReactNode;

  switch (chartType) {
    case 'BAR':
      chart = <BarChartView config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'LINE':
      chart = <LineChartView config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'AREA':
      chart = <AreaChartView config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'PIE':
    case 'DONUT':
      chart = <PieChartView chartType={chartType} config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'SCATTER':
      chart = <ScatterChartView config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'TABLE':
      chart = <TableView columns={columns} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'MAP':
      chart = <MapChartView config={config} data={data} height={fillParent ? undefined : height} />;
      break;
    case 'HISTOGRAM':
      chart = <HistogramChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'BOXPLOT':
      chart = <BoxPlotChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'HEATMAP':
      chart = <HeatmapChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'TREEMAP':
      chart = <TreemapChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'FUNNEL':
      chart = <FunnelChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'RADAR':
      chart = <RadarChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'WATERFALL':
      chart = <WaterfallChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'GAUGE':
      chart = <GaugeChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    case 'CANDLESTICK':
      chart = <CandlestickChartView data={data} config={config} height={fillParent ? undefined : height} />;
      break;
    default:
      return (
        <div
          className={`flex items-center justify-center text-muted-foreground text-sm ${fillParent ? 'h-full' : ''}`}
          style={fillParent ? undefined : { height }}
        >
          지원하지 않는 차트 타입입니다.
        </div>
      );
  }

  // When fillParent, wrap in a full-height container so Recharts ResponsiveContainer
  // and MapView/TableView can derive their dimensions from CSS.
  if (fillParent) {
    return <div className="h-full w-full">{chart}</div>;
  }

  return chart;
}
