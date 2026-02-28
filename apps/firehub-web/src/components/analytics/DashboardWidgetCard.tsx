import { X, Loader2 } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { ChartRenderer } from './ChartRenderer';
import { WidgetErrorBoundary } from './WidgetErrorBoundary';
import { useChartData, useChart } from '../../hooks/queries/useAnalytics';
import type { DashboardWidget } from '../../types/analytics';

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
      height={undefined}
    />
  );
}

export function DashboardWidgetCard({ widget, isEditing, onRemove }: DashboardWidgetCardProps) {
  return (
    <Card className="h-full flex flex-col overflow-hidden">
      <CardHeader className="py-2 px-3 shrink-0 flex-row items-center justify-between space-y-0 border-b">
        <CardTitle className="text-sm font-medium truncate">{widget.chartName}</CardTitle>
        {isEditing && onRemove && (
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6 shrink-0 ml-2 text-muted-foreground hover:text-destructive"
            onClick={() => onRemove(widget.id)}
            title="위젯 제거"
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        )}
      </CardHeader>
      <CardContent className="flex-1 p-2 min-h-0">
        <WidgetErrorBoundary widgetName={widget.chartName}>
          <WidgetContent widget={widget} />
        </WidgetErrorBoundary>
      </CardContent>
    </Card>
  );
}
