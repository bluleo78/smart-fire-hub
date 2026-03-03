import { Loader2, X } from 'lucide-react';
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
  onRefresh,
}: DashboardWidgetCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const isVisible = useWidgetVisibility(containerRef);

  const effectiveDataUpdatedAt = dataUpdatedAt ?? 0;
  const effectiveIsFetching = isFetching ?? false;
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
        refreshSeconds={autoRefreshSeconds ?? undefined}
        onRefresh={effectiveOnRefresh}
      />
    </Card>
  );
}
