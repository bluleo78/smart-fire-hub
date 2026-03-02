import { Loader2, X } from 'lucide-react';

import { useChart, useChartData } from '../../hooks/queries/useAnalytics';
import type { DashboardWidget } from '../../types/analytics';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Separator } from '../ui/separator';
import { ChartRenderer } from './ChartRenderer';
import { WidgetErrorBoundary } from './WidgetErrorBoundary';

interface DashboardWidgetCardProps {
  widget: DashboardWidget;
  isEditing: boolean;
  onRemove?: (widgetId: number) => void;
}

function WidgetContent({ widget }: { widget: DashboardWidget }) {
  const { data: chart, isLoading: chartLoading } = useChart(widget.chartId);
  const { data: chartData, isLoading: dataLoading } = useChartData(widget.chartId);

  const isLoading = chartLoading || dataLoading;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!chart || !chartData) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
        데이터를 불러올 수 없습니다.
      </div>
    );
  }

  return (
    <ChartRenderer
      chartType={chartData.chart.chartType}
      config={chartData.chart.config}
      data={chartData.queryResult.rows}
      columns={chartData.queryResult.columns}
      fillParent
    />
  );
}

export function DashboardWidgetCard({ widget, isEditing, onRemove }: DashboardWidgetCardProps) {
  return (
    <Card className="h-full py-2 gap-1 overflow-hidden">
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
          <WidgetContent widget={widget} />
        </WidgetErrorBoundary>
      </CardContent>
    </Card>
  );
}
