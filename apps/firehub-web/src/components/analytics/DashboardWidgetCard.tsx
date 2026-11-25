import { AlertTriangle, EyeOff, GripVertical, Loader2, Lock, X } from 'lucide-react';
import { type ComponentProps, type KeyboardEvent, type ReactNode, useCallback, useId, useMemo, useRef, useState } from 'react';

import { useChart, useChartData } from '../../hooks/queries/useAnalytics';
import { useWidgetVisibility } from '../../hooks/useWidgetVisibility';
import { extractApiError, handleApiError } from '../../lib/api-error';
import { type KeyboardLayoutAction, keyToLayoutAction } from '../../lib/dashboard-layout-keyboard';
import { cn } from '../../lib/utils';
import { useWidgetRenderTurn } from '../../lib/widget-render-queue';
import { type ChartDataResponse, type DashboardWidget, MAX_AUTO_REFRESH_SECONDS } from '../../types/analytics';
import { PendingButton } from '../PendingButton';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Separator } from '../ui/separator';
import { chartSupportsValueTable } from './chart-a11y';
import { chartNeedsRenderTurn } from './chart-render-turn';
import { ChartRenderer } from './ChartRenderer';
import { ChartTableToggle } from './ChartTableToggle';
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
  /** 이 위젯 단건 조회의 마지막 오류(없으면 null) — 다시 조회하는 동안에도 남아, 데이터가 없으면 사유와 「다시 시도」를 보인다(WD-686) */
  dataError: unknown;
  /** 이 위젯만 다시 조회한다 — 실패해도 토스트를 띄우지 않는다(위젯 안 안내가 이미 사유를 보인다, WD-686) */
  onRetry: () => void;
  /** 「표로 보기」가 켜졌는가(WD-832) — 켜면 그림 자리에 값 표를 보인다 */
  tableView: boolean;
}

/**
 * 위젯 안 오류 상자(WD-686) — 저장 쿼리 오류(「쿼리 오류」)와 조회 실패(서버 문구 + 「다시 시도」)가 같은 모양을 쓴다.
 * 쿼리 편집기 결과 영역(ResultTable)의 「쿼리 오류」 박스와 같은 색·테두리 형식이다.
 * 메시지 영역만 스크롤하고 「다시 시도」는 아래에 고정 — 좁고 낮은 위젯에서 문구가 감겨 내용이 보이는 높이보다 높아지면
 * 버튼이 잘렸다(WD-959). `min-h-0` 은 flex 자식이 콘텐츠 높이 아래로 줄어 스크롤이 생기게 하고,
 * `max-h-full` 은 바깥 상자 안쪽 높이(2×2 기준 91px)만큼 안내 상자를 제한한다.
 *
 * 여백(WD-959 리뷰 후속 ③): 여백 축소(`p-3` → `p-1.5`)는 「다시 시도」 버튼까지 짧은 위젯 안에 넣기 위한 것이라
 * 버튼이 있는 상자(조회 실패)에만 적용한다. 버튼이 없는 「쿼리 오류」 상자도 같은 `p-1.5` 로 줄면 넓은 위젯의
 * 안내 상자까지 함께 좁아져 안내 여백이 불필요하게 조여지므로, 그 상자는 원래 여백(`p-3`)을 그대로 쓴다.
 * 두 상자의 여백이 달라 보여도 잘림이 생기는 조건(버튼이 있는 상자)에만 좁히는 편이 안내를 덜 깨뜨린다.
 *
 * 안내 문구 영역은 `tabIndex` 를 받아 키보드로 포커스할 수 있다(WD-959 리뷰 후속 ④, WCAG 2.1.1) — 스크롤되는
 * 영역에 키보드로 닿지 못하면 긴 문구를 화살표 키로 읽을 수 없다. 포커스 시 `role=group` 이름으로 무엇인지 읽힌다.
 */
function WidgetErrorBox({ title, children, action }: { title: ReactNode; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="h-full overflow-auto">
      <div
        role="alert"
        className={cn(
          'flex max-h-full min-h-0 flex-col gap-1 rounded-md border border-destructive/50 bg-destructive/10',
          // 버튼이 있을 때만 좁은 여백 — 위 주석 ③
          action ? 'p-1.5' : 'p-3',
        )}
      >
        <div className="min-h-0 overflow-auto" tabIndex={0} role="group" aria-label="오류 안내">
          <p className="flex items-center gap-1.5 text-sm font-medium text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {title}
          </p>
          {children}
        </div>
        {action ? <div className="shrink-0">{action}</div> : null}
      </div>
    </div>
  );
}

/** 위젯 본문 로딩 스피너 — 첫 조회 중이거나 그릴 차례를 기다리는 동안(WD-1072) 같은 모양을 보인다 */
function WidgetBodySpinner() {
  return (
    <div className="flex items-center justify-center h-full">
      <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
    </div>
  );
}

/**
 * ChartRenderer 를 실제로 그리는 자리(WD-1072) — 위젯 여러 개가 한 커밋에 recharts 를 그리면 메인 스레드가 길게 막히므로
 * ready(그릴 SVG 가 있음: 표 보기 아님·행 있음·차트 종류·안내도 본다 — chartNeedsRenderTurn, WD-1113)일 때만 줄을 서 차례를 받은 뒤 그린다. 기다리는 동안은 스피너.
 * 오류·권한 없음·0행·표 보기는 그릴 무거운 차트가 없으니 줄을 서지 않는다(옆 카드의 차례를 막지 않게).
 * 한 번 차례를 받으면 true 를 유지해 새로고침 중 차트가 스피너로 돌아가지 않는다(이미 받은 뒤의 동작은 그대로).
 * 판정은 여기서 메모한다 — WidgetContent 는 오류·로딩 갈래에서 일찍 돌아가므로 그 위에 훅을 둘 수 없고,
 * 행 전체를 훑는 판정을 매 렌더(새로고침 스피너 토글 등)마다 다시 돌리지 않게 한다(WD-1113 code-review).
 */
function QueuedChartArea({
  tableView,
  ...rendererProps
}: { tableView: boolean } & Omit<ComponentProps<typeof ChartRenderer>, 'fillParent' | 'view'>) {
  const { chartType, config, columns, data } = rendererProps;
  const needsTurn = useMemo(
    () => chartNeedsRenderTurn(chartType, config, columns, data),
    [chartType, config, columns, data],
  );
  // 표 보기는 SVG 를 그리지 않으므로 메모 밖에서 바로 거른다
  const ready = !tableView && needsTurn;
  const hasTurn = useWidgetRenderTurn(ready);
  if (ready && !hasTurn) return <WidgetBodySpinner />;
  return <ChartRenderer {...rendererProps} fillParent view={tableView ? 'table' : 'chart'} />;
}

/**
 * 위젯 본문 — 카드가 조회한 이 위젯의 단건 차트 데이터(`/charts/{id}/data`)를 렌더링한다.
 * 데이터 조회·새로고침 상태는 카드가 소유하고(신선도 바와 같은 쿼리를 보도록), 본문은 표시만 한다.
 */
function WidgetContent({ widget, chartData, dataLoading, dataFetching, dataError, onRetry, tableView }: WidgetContentProps) {
  // 차트 메타는 첫 로딩 표시에만 쓴다 — 그리는 정의는 데이터 응답의 chart 다
  const { isLoading: chartLoading } = useChart(widget.chartId);

  const isInitialLoading = chartLoading || (dataLoading && !chartData);
  const isBackgroundFetching = !isInitialLoading && dataFetching;

  // 조회 실패·표시할 데이터 없음(WD-686) — 500·504·네트워크·429(동시 실행 상한) 어느 것이든 서버 문구(없으면 일반 문구)와 이 위젯만 다시
  // 조회하는 「다시 시도」를 위젯 안에 보인다. 예전에는 「데이터를 불러올 수 없습니다.」와 신선도 바의 「새로고침 실패」만 보여, 429 처럼 잠시 뒤
  // 다시 하면 되는 실패도 알 수 없었다. 이전 결과가 있으면 그것을 그대로 보이고 신선도 바 배지가 실패를 알린다.
  // 로딩 표시보다 먼저 본다 — 데이터 없는 쿼리를 다시 조회하면 TanStack 이 pending 으로 되돌려 error 를 비우므로 카드가 기억한 마지막 오류로
  // 안내를 유지하고, 「다시 시도」만 진행 중 잠근다(스피너로 바뀌면 버튼이 사라져 잠금을 보일 수 없다).
  // 성공(200)인데 차트 데이터가 없는 응답(WD-693)은 조회 함수(analyticsApi.getChartData)가 오류로 바꾸므로 이 갈래로 온다 — 예전에는
  // 아래 로딩 갈래로 떨어져 스피너에 영영 멈췄다. 그 오류는 서버 응답이 없어 일반 문구가 된다.
  if (!chartData && dataError) {
    return (
      <WidgetErrorBox
        title={extractApiError(dataError, '데이터를 불러올 수 없습니다.')}
        action={
          // 다시 조회하는 동안 잠근다 — 거듭 눌러 같은 차트 데이터 요청이 겹쳐 나가지 않게(aria-disabled 로 포커스 유지)
          // 높이를 덮어쓰지 않는다 — size="sm"(h-8)이 최소 클릭 영역 32px 를 지킨다(h-7 이 28px 로 줄이던 결함, WD-926)
          // `mt-2` 는 뺀다 — 안내 상자의 `gap-1` 이 메시지와 버튼 사이 간격을 만든다(WD-959, 상자 여백 재배치로 잘림 해결)
          <PendingButton pending={dataFetching} variant="outline" size="sm" className="text-xs" onClick={onRetry}>
            다시 시도
          </PendingButton>
        }
      />
    );
  }

  // 아직 받은 데이터가 없다(첫 조회 중이거나, 화면 밖이라 조회를 미룬 위젯) — 로딩 표시
  if (isInitialLoading || !chartData) {
    return <WidgetBodySpinner />;
  }

  // 원본 데이터를 볼 수 없음(스펙 §5-4, 목업 s4 ①) — 오류가 아니다. 빨간 오류·재시도 대신 차분한 잠금 상태,
  // 원본 데이터셋 이름은 노출하지 않는다(존재 은닉과 같은 선). 서버는 200 + denied:true + 빈 결과를 주므로
  // 이 검사가 없으면 빈 차트("데이터가 없습니다.")로 보여 정상 0행과 구분되지 않는다.
  // WD-143: denied 는 "권한 부족"과 "원본 데이터셋 삭제"를 구분하지 않는다 — 서버 SQL 관문이 볼 수 없는 표·없는 표를 일부러 같은 결과로
  // 주고(스펙 §2.5), 삭제되면 saved_query.dataset_id 도 SET NULL 이라 삭제 사실이 남지 않는다. 조회자 기준으로 둘을 가르면 사용자가 직접
  // 쓴 SQL 로 숨김 데이터셋 존재를 떠보는 경로가 생기므로, 모든 조회자에게 두 원인을 함께 알리는 같은 문구를 보인다(스펙 §5-4 문구에서
  // 의도적으로 벗어남 — 관리자에게 삭제된 원본을 "권한 없음"으로 단정하던 오안내를 없앤다).
  if (chartData.denied) {
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

  const queryResult = chartData.queryResult;

  // 저장 쿼리 실패(#764): API는 HTTP 200 + queryResult.error 로 사유(division by zero,
  // 결과 32MB 초과 안내 등)를 준다. 빈 rows 를 그대로 ChartRenderer 에 넘기면
  // "데이터가 없습니다." 로 보여 정상 0행과 구분되지 않으므로,
  // queryResult 에서 error 를 먼저 검사해 오류 상태와 사유 원문을 표시한다.
  // 표시 형식은 쿼리 편집기 결과 영역(ResultTable)의 "쿼리 오류" 박스와 맞춘다.
  if (queryResult.error) {
    return (
      <WidgetErrorBox title="쿼리 오류">
        <p className="text-xs text-destructive/80 mt-1 font-mono whitespace-pre-wrap break-words">{queryResult.error}</p>
      </WidgetErrorBox>
    );
  }

  // 데이터 응답에 실린 차트 정의가 최신이라 그것을 쓴다(데이터 응답의 chart 는 언제나 있다)
  const effectiveChart = chartData.chart;
  // 메타 조회는 설정이 가려지면 config 가 null 이다(WD-31②). queryResult 가 있으면 데이터 응답(config 항상 객체)이
  // 쓰이므로 실제로는 닿지 않지만, 렌더러가 null 에서 깨지지 않게 빈 설정으로 좁힌다.
  const effectiveConfig = effectiveChart.config ?? { xAxis: '', yAxis: [] };

  return (
    <div className="flex h-full flex-col">
      <div className="relative min-h-0 flex-1" data-testid="dashboard-widget-chart-area">
        <QueuedChartArea
          chartType={effectiveChart.chartType}
          config={effectiveConfig}
          data={queryResult.rows}
          columns={queryResult.columns}
          tableView={tableView}
          // WD-826: 카드 제목과 같은 출처(widget.chartName)를 접근 이름에 쓴다
          title={widget.chartName ?? undefined}
        />
        {isBackgroundFetching && (
          <div className="absolute top-1 right-1 pointer-events-none">
            <Loader2 className="h-3 w-3 animate-spin motion-reduce:animate-none text-muted-foreground" />
          </div>
        )}
      </div>
      {/* 차트 데이터는 행 상한(1000행)까지만 실행된다 — 상한에 닿았으면(서버 truncated) 그린 것이 전부가 아닐 수 있음을 알린다
          (WD-320, 쿼리 편집기 「상위 N행」과 같은 뜻). 서버는 상한을 넘는 결과가 실제로 있을 때만 truncated 로 준다(WD-454 — 상한+1 행으로 판정). */}
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
    isError: dataIsError,
    error: queryError,
    status: dataStatus,
    dataUpdatedAt,
    refetch,
  } = useChartData(widget.chartId, {
    refetchInterval,
    enabled: isVisible && !inaccessible,
  });

  // 위젯 본문에 넘길 오류(WD-686) — 데이터 없는 쿼리를 다시 조회하면 TanStack 이 error 를 비우므로, 마지막 오류를 다시 조회하는 동안 기억해
  // 실패 안내와 잠긴 「다시 시도」가 스피너로 바뀌지 않게 한다. 조회가 성공하면(status success) 지운다. 렌더 중 상태 맞추기(React 권장 패턴)라
  // 두 갈래가 겹치면 끝없이 다시 그린다 — 기억은 오류가 있을 때만, 지우기는 오류가 없을 때만 해 서로 배타적이게 둔다(데이터가 있는 채로
  // 새로고침이 실패해도 「기억 → 지움」이 번갈아 돌지 않는다).
  const [lastError, setLastError] = useState<unknown>(null);
  if (queryError != null) {
    if (queryError !== lastError) setLastError(queryError);
  } else if (dataStatus === 'success' && lastError !== null) {
    setLastError(null);
  }
  const dataError = queryError ?? lastError;

  // 수동 새로고침 — 이 위젯의 데이터를 실제로 다시 조회하고, 실패하면 1회 토스트로 알린다(#566).
  // 자동 새로고침 실패는 위젯마다 토스트가 쏟아지지 않도록 신선도 바 배지로만 알린다.
  const handleRefresh = useCallback(() => {
    void refetch().then((result) => {
      if (result.isError) {
        handleApiError(result.error, '위젯 데이터를 불러오지 못했습니다.');
      }
    });
  }, [refetch]);

  // 위젯 안 「다시 시도」(429 안내, WD-686) — 다시 조회만 한다. 실패하면 위젯 안 안내가 그대로 사유를 보이므로 수동 새로고침처럼
  // 토스트를 더 띄우지 않는다(같은 문구가 두 번 보이지 않게). 신선도 바의 새로고침 버튼은 handleRefresh(토스트 포함) 그대로다.
  const handleRetry = useCallback(() => {
    void refetch();
  }, [refetch]);

  // 「표로 보기」 선택(WD-832) — 카드마다 따로, 저장하지 않는다(새로고침하면 그림으로 돌아온다)
  const [tableView, setTableView] = useState(false);

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
      {/* WD-528: CardHeader 는 grid 라 열 폭이 글자 폭(max-content)까지 늘어나 긴 제목이 카드 밖으로 잘렸다(줄임표 없음).
          열을 minmax(0,1fr)(grid-cols-1)로, 안쪽 줄을 min-w-0 으로 묶어 제목이 카드 폭 안에서 줄임표로 줄어들게 한다. */}
      <CardHeader className={`grid-cols-1 px-3 pb-0 ${isEditing ? 'drag-handle cursor-grab active:cursor-grabbing' : ''}`}>
        <div className="flex min-w-0 items-center justify-between gap-1">
          {/* 키보드 이동 손잡이(WD-324) — 머리글 끌기·모서리 크기 조절은 마우스로만 되므로, Tab 으로 닿는 버튼에서
              화살표로 옮기고 Shift+화살표로 크기를 바꾼다(WCAG 2.1.1·2.5.7). 머리글 안이라 마우스로 끌어도 그대로 끌린다. */}
          {/* 편집용 손잡이·제거 버튼은 인쇄에서 숨긴다(WD-424) */}
          {isEditing && onKeyboardLayout && (
            <>
              <Button
                variant="ghost"
                size="icon-sm"
                className="shrink-0 text-muted-foreground rounded-sm print:hidden"
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
          {/* 잘린 제목의 전체 이름은 title(마우스 올림)로 본다(WD-528) */}
          <CardTitle role="heading" aria-level={2} className="text-xs font-medium truncate flex-1 min-w-0" title={title}>
            {title}
          </CardTitle>
          {/* 표로 보기 토글(WD-832) — 표 종류·지도·볼 수 없는 차트는 값 표가 없어 버튼도 없다. 편집 모드에서도 제거 버튼과 나란히 둔다. */}
          {!inaccessible && widget.chartType != null && chartSupportsValueTable(widget.chartType) && (
            <ChartTableToggle pressed={tableView} onPressedChange={setTableView} />
          )}
          {isEditing && onRemove && (
            <Button
              variant="ghost"
              size="icon-sm"
              className="shrink-0 text-muted-foreground hover:text-destructive hover:bg-destructive/10 rounded-sm print:hidden"
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
              dataError={dataError}
              onRetry={handleRetry}
              tableView={tableView}
            />
          </WidgetErrorBoundary>
        )}
      </CardContent>
      {/* 원본을 볼 수 없는(denied) 위젯·볼 수 없는 차트 위젯은 신선도·재시도가 의미 없으므로 신선도 바를 숨긴다(목업 s4 ①, WD-195) */}
      {!inaccessible && !chartData?.denied && (
        <WidgetFreshnessBar
          dataUpdatedAt={dataUpdatedAt}
          isFetching={dataFetching}
          isError={dataIsError}
          refreshSeconds={refreshSeconds}
          onRefresh={handleRefresh}
          widgetName={title}
        />
      )}
    </Card>
  );
}
