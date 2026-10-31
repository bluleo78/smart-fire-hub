import { AlertTriangle, EyeOff, GripVertical, Loader2, Lock, X } from 'lucide-react';
import { type KeyboardEvent, useCallback, useId, useRef } from 'react';

import { useChart, useChartData } from '../../hooks/queries/useAnalytics';
import { useWidgetVisibility } from '../../hooks/useWidgetVisibility';
import { handleApiError } from '../../lib/api-error';
import { type KeyboardLayoutAction, keyToLayoutAction } from '../../lib/dashboard-layout-keyboard';
import { type ChartDataResponse, type DashboardWidget, MAX_AUTO_REFRESH_SECONDS } from '../../types/analytics';
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
  /** 편집 모드에서 이동 손잡이에 화살표 키를 누르면 부른다(WD-324) — 끌기 없이 배치를 바꾸는 키보드 대안 */
  onKeyboardLayout?: (widgetId: number, action: KeyboardLayoutAction) => void;
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

  // 원본 데이터를 볼 수 없음(스펙 §5-4, 목업 s4 ①) — 오류가 아니다. 빨간 오류·재시도 대신 차분한 잠금 상태,
  // 원본 데이터셋 이름은 노출하지 않는다(존재 은닉과 같은 선). 서버는 200 + denied:true + 빈 결과를 주므로
  // 이 검사가 없으면 빈 차트("데이터가 없습니다.")로 보여 정상 0행과 구분되지 않는다.
  // WD-143: denied 는 "권한 부족"과 "원본 데이터셋 삭제"를 구분하지 않는다 — 서버 SQL 관문이 볼 수 없는 표·없는 표를 일부러 같은 결과로
  // 주고(스펙 §2.5), 삭제되면 saved_query.dataset_id 도 SET NULL 이라 삭제 사실이 남지 않는다. 조회자 기준으로 둘을 가르면 사용자가 직접
  // 쓴 SQL 로 숨김 데이터셋 존재를 떠보는 경로가 생기므로, 모든 조회자에게 두 원인을 함께 알리는 같은 문구를 보인다(스펙 §5-4 문구에서
  // 의도적으로 벗어남 — 관리자에게 삭제된 원본을 "권한 없음"으로 단정하던 오안내를 없앤다).
  if (chartData?.denied) {
    return (
      <div
        data-testid="widget-denied"
        className="flex h-full flex-col items-center justify-center gap-1 text-center text-muted-foreground"
      >
        <Lock className="h-5 w-5" aria-hidden="true" />
        <p className="text-sm font-medium">원본 데이터를 볼 수 없음</p>
        <p className="text-xs">열람 권한이 없거나 원본 데이터셋이 삭제되었을 수 있습니다.</p>
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
    <div className="flex h-full flex-col">
      <div className="relative min-h-0 flex-1">
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
      {/* 차트 데이터는 행 상한(1000행)까지만 실행된다 — 상한에 닿았으면(서버 truncated) 그린 것이 전부가 아닐 수 있음을 알린다
          (WD-320, 쿼리 편집기 「상위 N행」과 같은 뜻). 서버는 정확히 상한만큼인 결과도 truncated 로 주므로 「있을 수 있음」으로 쓴다. */}
      {queryResult.truncated && (
        <p className="shrink-0 pt-1 text-xs text-muted-foreground">
          상위 {queryResult.rows.length}행까지만 표시 — 결과가 더 있을 수 있습니다
        </p>
      )}
    </div>
  );
}

/** 볼 수 없는 차트 위젯의 제목 — 서버가 차트 이름을 싣지 않으므로(WD-195) 이름 대신 쓴다 */
const INACCESSIBLE_TITLE = '볼 수 없는 차트';

/**
 * 볼 수 없는 차트 위젯 본문(WD-195) — 조회자가 이 차트를 볼 수 없다(차트 주인이 공유하지 않았거나 비공개로 돌렸다).
 * 오류가 아니므로 빨간 오류·재시도·신선도 바 없이 차분한 안내만 보인다. 보안 등급 잠금(widget-denied, 차트는 보이고 원본 데이터만 못 봄)과
 * 원인이 달라 문구와 아이콘을 나눈다.
 */
function InaccessibleWidgetContent() {
  return (
    <div
      data-testid="widget-inaccessible"
      className="flex h-full flex-col items-center justify-center gap-1 text-center text-muted-foreground"
    >
      <EyeOff className="h-5 w-5" aria-hidden="true" />
      <p className="text-sm font-medium">볼 수 없는 차트입니다</p>
      <p className="text-xs">차트 주인이 공유하지 않았습니다.</p>
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
  onKeyboardLayout,
  autoRefreshSeconds,
}: DashboardWidgetCardProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const isVisible = useWidgetVisibility(containerRef);
  // 조회자가 볼 수 없는 차트의 위젯(WD-195) — 서버가 accessible=false 로 주고 이름을 싣지 않는다. 데이터·메타를 조회하면 404 라
  // 「새로고침 실패」로 깨져 보이므로 조회 자체를 하지 않는다. 필드가 없는 옛 응답은 볼 수 있는 것으로 다룬다.
  const inaccessible = widget.accessible === false;
  const title = inaccessible ? INACCESSIBLE_TITLE : (widget.chartName ?? '');

  // 대시보드 자동 새로고침 주기를 위젯 단건 쿼리의 refetchInterval 로 적용한다(기존 자동 새로고침 경로 유지)
  // 상한 전에 저장된 큰 값(예: 2500000초)도 24시간으로 줄여 쓴다(WD-327) — 그대로 넘기면 지터까지 곱한 지연이 브라우저 타이머 한도(2^31 ms)를
  // 넘어 0 으로 감기고, 위젯이 쉬지 않고 재조회한다. 신선도 바도 같은 값을 써야 실제 주기와 어긋나지 않는다.
  const refreshSeconds =
    autoRefreshSeconds && autoRefreshSeconds > 0
      ? Math.min(autoRefreshSeconds, MAX_AUTO_REFRESH_SECONDS)
      : undefined;
  const refetchInterval = refreshSeconds ? refreshSeconds * 1000 : undefined;

  const {
    data: chartData,
    isLoading: dataLoading,
    isFetching: dataFetching,
    isError: dataError,
    dataUpdatedAt,
    refetch,
  } = useChartData(widget.chartId, {
    refetchInterval,
    enabled: isVisible && !inaccessible,
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

  // 이동 손잡이 사용법 — 손잡이의 설명(aria-describedby)으로 읽히게 숨긴 문구의 id
  const moveHelpId = useId();

  // 이동 손잡이 키 처리(WD-324): 화살표 = 한 칸 이동, Shift+화살표 = 크기 조절. 화살표만 가로채고(페이지 스크롤 방지)
  // Tab 등은 그대로 흘려보내 포커스 이동을 막지 않는다.
  const handleMoveKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    const action = keyToLayoutAction(e.key, e.shiftKey);
    if (!action || !onKeyboardLayout) return;
    e.preventDefault();
    e.stopPropagation();
    onKeyboardLayout(widget.id, action);
  };

  return (
    // 인쇄(WD-424): 위젯 하나가 두 쪽으로 갈리지 않게 한다(쌓기 보기처럼 흐름 배치일 때 효과가 있다)
    <Card ref={containerRef} className="h-full py-2 gap-1 overflow-hidden flex flex-col print:break-inside-avoid">
      <CardHeader className={`px-3 pb-0 ${isEditing ? 'drag-handle cursor-grab active:cursor-grabbing' : ''}`}>
        <div className="flex items-center justify-between gap-1">
          {/* 키보드 이동 손잡이(WD-324) — 머리글 끌기·모서리 크기 조절은 마우스로만 되므로, Tab 으로 닿는 버튼에서
              화살표로 옮기고 Shift+화살표로 크기를 바꾼다(WCAG 2.1.1·2.5.7). 머리글 안이라 마우스로 끌어도 그대로 끌린다. */}
          {/* 편집용 손잡이·제거 버튼은 인쇄에서 숨긴다(WD-424) */}
          {isEditing && onKeyboardLayout && (
            <>
              <Button
                variant="ghost"
                size="icon"
                className="h-5 w-5 shrink-0 text-muted-foreground rounded-sm print:hidden"
                aria-label={`「${title}」 위젯 이동·크기 조절`}
                aria-describedby={moveHelpId}
                title="화살표: 이동 · Shift+화살표: 크기 조절"
                onKeyDown={handleMoveKeyDown}
              >
                <GripVertical className="h-3 w-3" aria-hidden="true" />
              </Button>
              <span id={moveHelpId} className="sr-only">
                화살표 키로 한 칸씩 옮기고, Shift 와 화살표 키로 크기를 바꿉니다.
              </span>
            </>
          )}
          {/* WD-423: 위젯 제목을 제목(heading)으로 둬 스크린리더의 제목 탐색으로 위젯 사이를 오갈 수 있게 한다(페이지 H1 = 대시보드 이름 아래 단계). */}
          <CardTitle role="heading" aria-level={2} className="text-xs font-medium truncate flex-1 min-w-0">
            {title}
          </CardTitle>
          {isEditing && onRemove && (
            <Button
              variant="ghost"
              size="icon"
              className="h-5 w-5 shrink-0 text-muted-foreground hover:text-destructive hover:bg-destructive/10 rounded-sm print:hidden"
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
        {inaccessible ? (
          <InaccessibleWidgetContent />
        ) : (
          <WidgetErrorBoundary widgetName={title}>
            <WidgetContent
              widget={widget}
              chartData={chartData}
              dataLoading={dataLoading}
              dataFetching={dataFetching}
            />
          </WidgetErrorBoundary>
        )}
      </CardContent>
      {/* 원본을 볼 수 없는(denied) 위젯·볼 수 없는 차트 위젯은 신선도·재시도가 의미 없으므로 신선도 바를 숨긴다(목업 s4 ①, WD-195) */}
      {!inaccessible && !chartData?.denied && (
        <WidgetFreshnessBar
          dataUpdatedAt={dataUpdatedAt}
          isFetching={dataFetching}
          isError={dataError}
          refreshSeconds={refreshSeconds}
          onRefresh={handleRefresh}
          widgetName={title}
        />
      )}
    </Card>
  );
}
