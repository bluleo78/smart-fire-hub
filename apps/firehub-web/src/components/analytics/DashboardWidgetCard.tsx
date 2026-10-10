import { AlertTriangle, Loader2, Lock, X } from 'lucide-react';
import { useCallback, useRef } from 'react';

import { useChart, useChartData } from '../../hooks/queries/useAnalytics';
import { useWidgetVisibility } from '../../hooks/useWidgetVisibility';
import { handleApiError } from '../../lib/api-error';
import type { ChartDataResponse, DashboardWidget } from '../../types/analytics';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Separator } from '../ui/separator';
import { ChartRenderer } from './ChartRenderer';
import { WidgetErrorBoundary } from './WidgetErrorBoundary';
import { WidgetFreshnessBar } from './WidgetFreshnessBar';

interface DashboardWidgetCardProps {
  widget: DashboardWidget;
  isEditing: boolean;
  onRemove?: (widgetId: number) => void;
  autoRefreshSeconds?: number | null;
}

interface WidgetContentProps {
  widget: DashboardWidget;
  chartData?: ChartDataResponse;
  dataLoading: boolean;
  dataFetching: boolean;
}

/**
 * 위젯 본문 — 카드가 조회한 이 위젯의 단건 차트 데이터(`/charts/{id}/data`)를 렌더링한다.
 * 데이터 조회·새로고침 상태는 카드가 소유하고(신선도 바와 같은 쿼리를 보도록), 본문은 표시만 한다.
 */
function WidgetContent({ widget, chartData, dataLoading, dataFetching }: WidgetContentProps) {
  const { data: chart, isLoading: chartLoading } = useChart(widget.chartId);

  const isInitialLoading = chartLoading || (dataLoading && !chartData);
  const isBackgroundFetching = !isInitialLoading && dataFetching;

  if (isInitialLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  // 열람 권한 없음(스펙 §5-4, 목업 s4 ①) — 권한 부족은 오류가 아니다. 빨간 오류·재시도 대신 차분한 잠금 상태,
  // 원본 데이터셋 이름은 노출하지 않는다(존재 은닉과 같은 선). 서버는 200 + denied:true + 빈 결과를 주므로
  // 이 검사가 없으면 빈 차트("데이터가 없습니다.")로 보여 정상 0행과 구분되지 않는다.
  if (chartData?.denied) {
    return (
      <div
        data-testid="widget-denied"
        className="flex h-full flex-col items-center justify-center gap-1 text-center text-muted-foreground"
      >
        <Lock className="h-5 w-5" aria-hidden="true" />
        <p className="text-sm font-medium">열람 권한 없음</p>
        <p className="text-xs">이 위젯의 원본 데이터를 볼 수 있는 권한이 없습니다.</p>
      </div>
    );
  }

  const queryResult = chartData?.queryResult ?? null;

  // 저장 쿼리 실패(#764): API는 HTTP 200 + queryResult.error 로 사유(division by zero,
  // 결과 32MB 초과 안내 등)를 준다. 빈 rows 를 그대로 ChartRenderer 에 넘기면
  // "데이터가 없습니다." 로 보여 정상 0행과 구분되지 않으므로,
  // queryResult 에서 error 를 먼저 검사해 오류 상태와 사유 원문을 표시한다.
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

  // 데이터 응답에 실린 차트 정의가 최신이므로 우선 사용하고, 없으면 메타 조회 결과로 폴백
  const effectiveChart = chartData?.chart ?? chart;
  // 메타 조회는 설정이 가려지면 config 가 null 이다(WD-31②). queryResult 가 있으면 데이터 응답(config 항상 객체)이
  // 쓰이므로 실제로는 닿지 않지만, 렌더러가 null 에서 깨지지 않게 빈 설정으로 좁힌다.
  const effectiveConfig = effectiveChart.config ?? { xAxis: '', yAxis: [] };

  return (
    <div className="relative h-full">
      <ChartRenderer
        chartType={effectiveChart.chartType}
        config={effectiveConfig}
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

/**
 * 대시보드 위젯 카드.
 *
 * 왜 카드가 데이터 쿼리를 소유하나(#778): 과거엔 신선도 바("방금"/"새로고침 실패")와 새로고침 버튼이
 * 페이지의 일괄 쿼리(`/dashboards/{id}/data`) 상태를 따랐는데, 그 응답은 계약 불일치로 버려지고 실제 표시
 * 데이터는 위젯별 단건 쿼리에서 왔다. 그래서 새로고침이 화면 데이터를 바꾸지 않으면서 "방금"으로 표시되고,
 * 일괄 요청 하나가 500 이면 정상 위젯까지 "새로고침 실패"가 붙었다. 이제 표시 데이터·신선도·새로고침이
 * 모두 이 위젯의 단건 쿼리 하나를 보므로, "방금"은 실제 재조회 성공 시각이고 실패도 이 위젯에만 표시된다.
 */
export function DashboardWidgetCard({
  widget,
  isEditing,
  onRemove,
  autoRefreshSeconds,
}: DashboardWidgetCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const isVisible = useWidgetVisibility(containerRef);

  // 대시보드 자동 새로고침 주기를 위젯 단건 쿼리의 refetchInterval 로 적용한다(기존 자동 새로고침 경로 유지)
  const refetchInterval =
    autoRefreshSeconds && autoRefreshSeconds > 0 ? autoRefreshSeconds * 1000 : undefined;

  const {
    data: chartData,
    isLoading: dataLoading,
    isFetching: dataFetching,
    isError: dataError,
    dataUpdatedAt,
    refetch,
  } = useChartData(widget.chartId, {
    refetchInterval,
    enabled: isVisible,
  });

  // 수동 새로고침 — 이 위젯의 데이터를 실제로 다시 조회하고, 실패하면 1회 토스트로 알린다(#566).
  // 자동 새로고침 실패는 위젯마다 토스트가 쏟아지지 않도록 신선도 바 배지로만 알린다.
  const handleRefresh = useCallback(() => {
    void refetch().then((result) => {
      if (result.isError) {
        handleApiError(result.error, '위젯 데이터를 불러오지 못했습니다.');
      }
    });
  }, [refetch]);

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
            chartData={chartData}
            dataLoading={dataLoading}
            dataFetching={dataFetching}
          />
        </WidgetErrorBoundary>
      </CardContent>
      {/* 열람 권한 없음 위젯은 신선도·재시도가 의미 없으므로 신선도 바를 숨긴다(목업 s4 ①) */}
      {!chartData?.denied && (
        <WidgetFreshnessBar
          dataUpdatedAt={dataUpdatedAt}
          isFetching={dataFetching}
          isError={dataError}
          refreshSeconds={autoRefreshSeconds ?? undefined}
          onRefresh={handleRefresh}
        />
      )}
    </Card>
  );
}
