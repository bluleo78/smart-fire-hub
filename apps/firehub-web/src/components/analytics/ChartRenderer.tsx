import { AlertTriangle } from 'lucide-react';
import { useMemo } from 'react';

import type { ChartConfig, ChartType } from '../../types/analytics';
import { AreaChartView } from './AreaChartView';
import { BarChartView } from './BarChartView';
import { findMissingChartColumns, normalizeYAxis } from './chart-missing-columns';
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
  /**
   * 컬럼 누락 안내의 해결 문구(WD-88). 제목과 없는 컬럼 목록은 어디서나 같고, 해결 방법만 문맥에 따라 다르다.
   * 대시보드 위젯·차트 편집은 기본값(사람이 승인한 문구)을 쓰고, 저장된 쿼리·차트 편집이 없는 AI 챗은 자기 문구를 넘긴다.
   */
  missingColumnsHint?: string;
}

/** 위젯·차트 편집 미리보기의 기본 해결 문구(WD-88 A안, 사람 승인 문구 — 바꾸지 않는다) */
const DEFAULT_MISSING_COLUMNS_HINT = '원본 쿼리의 컬럼 이름이 바뀌었을 수 있습니다. 차트 편집에서 축을 다시 고르세요.';

/** 문장 하나짜리 안내(축 미설정·데이터 없음·미지원 타입)의 기존 muted 가운데 정렬 형식 */
const MESSAGE_CLASS = 'flex items-center justify-center text-muted-foreground text-sm';

/**
 * 차트 대신 보여 주는 안내의 자리 — 차트와 같은 자리·높이를 차지하게 한다.
 * fillParent 면 부모 높이를 채우고(h-full), 아니면 차트와 같은 고정 높이를 쓴다. 안내 분기 네 곳이 이 규칙 하나를 쓴다.
 * 기본 모양은 문장 하나짜리 안내(MESSAGE_CLASS)이고, 누락 컬럼 안내처럼 구조가 다른 안내는 className 으로 바꾼다.
 */
function ChartMessage({
  height,
  fillParent,
  className = MESSAGE_CLASS,
  children,
  'data-testid': testId,
}: {
  height: number;
  fillParent?: boolean;
  className?: string;
  children: React.ReactNode;
  'data-testid'?: string;
}) {
  return (
    <div
      data-testid={testId}
      className={`${className} ${fillParent ? 'h-full' : ''}`}
      style={fillParent ? undefined : { height }}
    >
      {children}
    </div>
  );
}

/**
 * 차트 컬럼 누락 안내(WD-88) — 설정 컬럼이 쿼리 결과에 없을 때 차트 자리 가운데에 표시한다.
 * 「데이터가 없습니다」 빈 상태와 같은 자리(ChartMessage)를 쓰고, 구조는 디자인 시스템 06 EmptyState(아이콘 + 제목 + 설명)를
 * 따른다. 공용 EmptyState 컴포넌트는 없고 ChartConfigWithheldNotice 는 자물쇠·고정 문구 전용이라 재사용하지 않았다.
 * 경고 아이콘은 warning 토큰을 쓴다. 계속 보이는 정적 안내라 role=alert 를 붙이지 않는다(ChartConfigWithheldNotice 와 같은 판단).
 * 작은 대시보드 위젯(최소 높이 2칸)에서 내용이 넘칠 수 있어 overflow-auto 로 잘림 대신 스크롤을 허용한다.
 */
function MissingColumnsNotice({
  missingColumns,
  hint,
  height,
  fillParent,
}: {
  missingColumns: string[];
  hint: string;
  height: number;
  fillParent?: boolean;
}) {
  return (
    <ChartMessage data-testid="chart-missing-columns" height={height} fillParent={fillParent} className="flex overflow-auto px-4">
      {/* justify-center 대신 m-auto 로 가운데 정렬한다 — 내용이 칸보다 높으면 justify-center 는 위쪽(아이콘·제목)을
          스크롤로 닿을 수 없는 영역으로 밀어내지만, auto 여백은 넘칠 때 0 이 되어 위에서부터 스크롤된다. */}
      <div className="m-auto flex flex-col items-center gap-2 py-2 text-center">
        <AlertTriangle className="h-5 w-5 shrink-0 text-warning" aria-hidden="true" />
        <p className="text-sm font-medium break-keep">차트에 쓰는 컬럼이 쿼리 결과에 없습니다</p>
        <p className="flex flex-wrap items-center justify-center gap-1 text-xs text-muted-foreground">
          <span>없는 컬럼:</span>
          {missingColumns.map((col) => (
            <code key={col} className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground break-all">
              {col}
            </code>
          ))}
        </p>
        <p className="text-xs text-muted-foreground break-keep">{hint}</p>
      </div>
    </ChartMessage>
  );
}

export function ChartRenderer({
  chartType,
  config: rawConfig,
  data,
  columns,
  height = 300,
  fillParent,
  missingColumnsHint = DEFAULT_MISSING_COLUMNS_HINT,
}: ChartRendererProps) {
  // WD-88: 원본 쿼리의 컬럼 이름이 바뀌면 저장된 축 설정이 없는 컬럼을 가리켜, 차트가 빈 격자·범례만 그린 채
  // 아무 안내도 하지 않았다. 설정 컬럼이 하나라도 결과에 없으면 틀린 그림 대신 원인과 해결 방법을 보여 준다.
  // 자동 추론한 값은 columns 에서 온 것이라 검사 대상이 아니므로 원본(rawConfig)으로 판단한다.
  // 뷰는 row 키로 값을 읽으므로 row 키도 "결과에 있는 컬럼"으로 인정한다(data 전달).
  // 훅은 조기 반환보다 앞에 있어야 하므로 여기서 계산하고, 입력이 같으면 다시 계산하지 않게 메모한다.
  const missingColumns = useMemo(
    () => findMissingChartColumns(chartType, rawConfig, columns, data),
    [chartType, rawConfig, columns, data],
  );

  // Auto-infer xAxis/yAxis from columns when not provided.
  // yAxis 가 아예 없는 설정(AI 챗에서 LLM 이 빠뜨린 경우 등)에서 .length 로 TypeError 가 나지 않게 빈 배열로 정규화한다(WD-88).
  const config = normalizeYAxis(rawConfig);
  if ((!config.xAxis || config.yAxis.length === 0) && columns.length >= 2) {
    if (!config.xAxis) {
      config.xAxis = columns[0];
    }
    if (config.yAxis.length === 0) {
      // For numeric columns, use remaining columns as yAxis
      const remaining = columns.filter(c => c !== config.xAxis);
      config.yAxis = remaining.length > 0 ? remaining : [columns[1]];
    }
  }

  // 0행 결과에서도 이름이 틀렸으면 컬럼 안내가 「데이터가 없습니다」보다 우선한다(축을 고쳐야 데이터가 보이므로).
  if (missingColumns.length > 0) {
    return (
      <MissingColumnsNotice missingColumns={missingColumns} hint={missingColumnsHint} height={height} fillParent={fillParent} />
    );
  }

  if (chartType !== 'MAP' && chartType !== 'TABLE' && (!config.xAxis || config.yAxis.length === 0)) {
    return (
      <ChartMessage height={height} fillParent={fillParent}>
        {chartType === 'PIE' || chartType === 'DONUT'
          ? '데이터 필드를 설정하세요.'
          : 'X축과 Y축을 설정하세요.'}
      </ChartMessage>
    );
  }

  if (data.length === 0) {
    return (
      <ChartMessage height={height} fillParent={fillParent}>
        데이터가 없습니다.
      </ChartMessage>
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
        <ChartMessage height={height} fillParent={fillParent}>
          지원하지 않는 차트 타입입니다.
        </ChartMessage>
      );
  }

  // When fillParent, wrap in a full-height container so Recharts ResponsiveContainer
  // and MapView/TableView can derive their dimensions from CSS.
  if (fillParent) {
    return <div className="h-full w-full">{chart}</div>;
  }

  return chart;
}
