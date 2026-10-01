import { AlertTriangle, Loader2, X } from 'lucide-react';
import { useRef } from 'react';

import { useChart, useChartData } from '../../hooks/queries/useAnalytics';
import { useWidgetVisibility } from '../../hooks/useWidgetVisibility';
import type { DashboardWidget, WidgetData } from '../../types/analytics';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Separator } from '../ui/separator';
import { ChartRenderer } from './ChartRenderer';
import { WidgetErrorBoundary } from './WidgetErrorBoundary';
import { WidgetFreshnessBar } from './WidgetFreshnessBar';

interface DashboardWidgetCardProps {
  widget: DashboardWidget;
  batchData?: WidgetData;
  isEditing: boolean;
  onRemove?: (widgetId: number) => void;
  autoRefreshSeconds?: number | null;
  dataUpdatedAt?: number;
  isFetching?: boolean;
  isError?: boolean;
  onRefresh?: () => void;
}

interface WidgetContentProps {
  widget: DashboardWidget;
  batchData?: WidgetData;
  autoRefreshSeconds?: number | null;
  isVisible: boolean;
}

function WidgetContent({ widget, batchData, autoRefreshSeconds, isVisible }: WidgetContentProps) {
  const { data: chart, isLoading: chartLoading } = useChart(widget.chartId);

  // Only fetch individual chart data when no batch data is provided
  const refetchInterval =
    !batchData && autoRefreshSeconds && autoRefreshSeconds > 0
      ? autoRefreshSeconds * 1000
      : undefined;

  const {
    data: chartData,
    isLoading: dataLoading,
    isFetching: chartFetching,
  } = useChartData(batchData ? undefined : widget.chartId, {
    refetchInterval,
    enabled: isVisible,
  });

  const isInitialLoading = chartLoading || (!batchData && dataLoading && !chartData);
  const isBackgroundFetching = !isInitialLoading && !batchData && chartFetching;

  if (isInitialLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (batchData?.error) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
        {batchData.error}
      </div>
    );
  }

  // Use batch query result if available, else fall back to individual fetch
  const queryResult = batchData?.queryResult ?? chartData?.queryResult ?? null;

  // 저장 쿼리 실패(#764): API는 HTTP 200 + queryResult.error 로 사유(division by zero,
  // 결과 32MB 초과 안내 등)를 준다. 빈 rows 를 그대로 ChartRenderer 에 넘기면
  // "데이터가 없습니다." 로 보여 정상 0행과 구분되지 않으므로, 배치·단건 경로 공통으로
  // 해석된 queryResult 에서 error 를 먼저 검사해 오류 상태와 사유 원문을 표시한다.
  // 표시 형식은 쿼리 편집기 결과 영역(ResultTable)의 "쿼리 오류" 박스와 맞춘다.
  if (queryResult?.error) {
    return (
      <div className="h-full overflow-auto py-1">
        <div
          role="alert"
          className="rounded-md border border-destructive/50 bg-destructive/10 p-3"
        >
          <p className="flex items-center gap-1.5 text-sm font-medium text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            쿼리 오류
          </p>
          <p className="text-xs text-destructive/80 mt-1 font-mono whitespace-pre-wrap break-words">
            {queryResult.error}
          </p>
        </div>
      </div>
    );
  }

  if (!chart || !queryResult) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
        데이터를 불러올 수 없습니다.
      </div>
    );
  }

  const effectiveChart = batchData ? chart : (chartData?.chart ?? chart);

  return (
    <div className="relative h-full">
      <ChartRenderer
        chartType={effectiveChart.chartType}
        config={effectiveChart.config}
        data={queryResult.rows}
        columns={queryResult.columns}
        fillParent
      />
      {isBackgroundFetching && (
        <div className="absolute top-1 right-1 pointer-events-none">
          <Loader2 className="h-3 w-3 animate-spin motion-reduce:animate-none text-muted-foreground" />
        </div>
      )}
    </div>
  );
}

export function DashboardWidgetCard({
  widget,
  batchData,
  isEditing,
  onRemove,
  autoRefreshSeconds,
  dataUpdatedAt,
  isFetching,
  isError,
  onRefresh,
}: DashboardWidgetCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const isVisible = useWidgetVisibility(containerRef);

  const effectiveDataUpdatedAt = dataUpdatedAt ?? 0;
  const effectiveIsFetching = isFetching ?? false;
  const effectiveIsError = isError ?? false;
  const effectiveOnRefresh = onRefresh ?? (() => undefined);

  return (
    <Card ref={containerRef} className="h-full py-2 gap-1 overflow-hidden flex flex-col">
      <CardHeader className={`px-3 pb-0 ${isEditing ? 'drag-handle cursor-grab active:cursor-grabbing' : ''}`}>
        <div className="flex items-center justify-between">
          <CardTitle className="text-xs font-medium truncate">{widget.chartName}</CardTitle>
          {isEditing && onRemove && (
            <Button
              variant="ghost"
              size="icon"
              className="h-5 w-5 shrink-0 text-muted-foreground hover:text-destructive hover:bg-destructive/10 rounded-sm"
              onClick={(e) => {
                e.stopPropagation();
                onRemove(widget.id);
              }}
              title="위젯 제거"
            >
              <X className="h-3 w-3" />
            </Button>
          )}
        </div>
      </CardHeader>
      <Separator />
      <CardContent className="px-3 pt-1 flex-1 min-h-0">
        <WidgetErrorBoundary widgetName={widget.chartName}>
          <WidgetContent
            widget={widget}
            batchData={batchData}
            autoRefreshSeconds={autoRefreshSeconds}
            isVisible={isVisible}
          />
        </WidgetErrorBoundary>
      </CardContent>
      <WidgetFreshnessBar
        dataUpdatedAt={effectiveDataUpdatedAt}
        isFetching={effectiveIsFetching}
        isError={effectiveIsError}
        refreshSeconds={autoRefreshSeconds ?? undefined}
        onRefresh={effectiveOnRefresh}
      />
    </Card>
  );
}
