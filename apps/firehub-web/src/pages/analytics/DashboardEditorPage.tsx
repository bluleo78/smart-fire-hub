import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';

import { useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  Check,
  Download,
  Eye,
  LayoutDashboard,
  Loader2,
  Maximize2,
  Minimize2,
  Pencil,
  Plus,
  RefreshCw,
  Share2,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { LayoutItem, ResponsiveLayouts } from 'react-grid-layout';
import { ResponsiveGridLayout, useContainerWidth } from 'react-grid-layout';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { DashboardWidgetCard } from '../../components/analytics/DashboardWidgetCard';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Skeleton } from '../../components/ui/skeleton';
import {
  useAddWidget,
  useChartsInfinite,
  useDashboard,
  useDashboardExportAllowed,
  useRemoveWidget,
  useUpdateWidget,
} from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { extractApiError, handleApiError, isConflictError, isNotFoundError } from '../../lib/api-error';
import {
  applyKeyboardLayoutAction,
  DASHBOARD_GRID_COLS,
  describeLayoutItem,
  type KeyboardLayoutAction,
} from '../../lib/dashboard-layout-keyboard';
import type { ChartListItem, DashboardWidget } from '../../types/analytics';

/** 격자 한 행의 높이(px)와 위젯 간격(px) — 쌓기 보기도 같은 값으로 높이를 맞춘다. */
const ROW_HEIGHT = 80;
const GRID_GAP = 8;
/** 이 폭(px)보다 좁으면 보기 모드에서 위젯을 한 줄에 하나씩 쌓는다(WD-321, Tailwind sm 경계). */
const STACK_BELOW_WIDTH = 640;

// Sub-component that owns width measurement for ResponsiveGridLayout
interface GridAreaProps {
  widgets: DashboardWidget[];
  isEditing: boolean;
  layouts: ResponsiveLayouts;
  onLayoutChange: (layout: readonly LayoutItem[], allLayouts: ResponsiveLayouts) => void;
  onRemove: (widgetId: number) => void;
  onKeyboardLayout: (widgetId: number, action: KeyboardLayoutAction) => void;
  autoRefreshSeconds?: number | null;
}

function GridArea({
  widgets,
  isEditing,
  layouts,
  onLayoutChange,
  onRemove,
  onKeyboardLayout,
  autoRefreshSeconds,
}: GridAreaProps) {
  const { width, containerRef: gridRef } = useContainerWidth({ initialWidth: 1280 });

  // 좁은 화면(모바일)의 보기 모드는 12칸 좌표를 비례 축소하지 않고 위젯을 한 줄에 하나씩 쌓는다(WD-321).
  // 4칸 위젯이 95px 로 찌그러져 읽을 수 없었기 때문이다. 순서는 데스크톱 배치의 위→아래, 왼→오른쪽이고,
  // 높이는 저장된 행 수를 그대로 쓴다. 편집 모드는 저장 좌표를 직접 다루므로 12칸 격자를 유지한다(#529 — lg 고정).
  // 폭 측정 ref 는 늘 같은 바깥 요소에 둔다 — 쌓기·격자를 오갈 때 측정 대상이 바뀌면 폭이 옛 값에 멈춘다.
  const stacked = !isEditing && width < STACK_BELOW_WIDTH;
  const ordered = stacked
    ? [...widgets].sort((a, b) => a.positionY - b.positionY || a.positionX - b.positionX)
    : widgets;

  return (
    <div ref={gridRef}>
      {stacked ? (
        <div className="flex flex-col gap-2" data-testid="dashboard-stacked">
          {ordered.map((widget) => (
            <div
              key={String(widget.id)}
              style={{ height: widget.height * ROW_HEIGHT + (widget.height - 1) * GRID_GAP }}
            >
              <DashboardWidgetCard
                widget={widget}
                isEditing={false}
                onRemove={onRemove}
                autoRefreshSeconds={autoRefreshSeconds}
              />
            </div>
          ))}
        </div>
      ) : (
        <ResponsiveGridLayout
          width={width}
          // 대시보드 레이아웃은 항상 "lg" 브레이크포인트(12컬럼) 기준으로만 저장/복원한다
          // (handleSaveEdit이 localLayouts.lg만 서버에 반영).
          // 과거에는 md/sm 브레이크포인트도 함께 선언했는데, 편집 영역의 실제 컨테이너 폭이
          // sidebar/padding 때문에 lg 임계값(1200px)보다 좁은 경우가 흔해 react-grid-layout이
          // 내부적으로 "md" 브레이크포인트를 활성화했다. 이 상태에서 위젯을 리사이즈하면
          // onLayoutChange가 allLayouts.md만 갱신하고 allLayouts.lg는 기존 값 그대로 남아,
          // "완료" 클릭 시 리사이즈 이전 값이 그대로 저장되는 버그가 있었다(#529).
          // 임계값을 0으로 두어 컨테이너 폭과 무관하게 항상 "lg"만 활성화되도록 고정한다.
          breakpoints={{ lg: 0 }}
          cols={{ lg: DASHBOARD_GRID_COLS }}
          rowHeight={ROW_HEIGHT}
          dragConfig={{ enabled: isEditing, bounded: false, handle: '.drag-handle' }}
          resizeConfig={{ enabled: isEditing, handles: ['se'] }}
          layouts={layouts}
          onLayoutChange={onLayoutChange}
          margin={[GRID_GAP, GRID_GAP]}
        >
          {widgets.map((widget) => (
            <div key={String(widget.id)}>
              <DashboardWidgetCard
                widget={widget}
                isEditing={isEditing}
                onRemove={onRemove}
                onKeyboardLayout={onKeyboardLayout}
                autoRefreshSeconds={autoRefreshSeconds}
              />
            </div>
          ))}
        </ResponsiveGridLayout>
      )}
    </div>
  );
}

// Convert DashboardWidget list to react-grid-layout LayoutItem[]
function widgetsToLayoutItems(widgets: DashboardWidget[]): LayoutItem[] {
  return widgets.map((w) => ({
    i: String(w.id),
    x: w.positionX,
    y: w.positionY,
    w: w.width,
    h: w.height,
    minW: 2,
    minH: 2,
  }));
}

interface AddWidgetDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAdd: (chartId: number, chartType?: string) => void;
  isPending: boolean;
}

function AddWidgetDialog({ open, onOpenChange, onAdd, isPending }: AddWidgetDialogProps) {
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<number | null>(null);
  // #537: 단일 페이지(useCharts) 대신 무한스크롤 훅을 사용해 20건 초과 차트도
  // 검색 없이 "더 보기"로 접근 가능하게 한다.
  const {
    data: chartsData,
    isLoading,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useChartsInfinite({ search: search || undefined, size: 20 });
  const charts = useMemo(
    () => chartsData?.pages.flatMap((p) => p.content) ?? [],
    [chartsData],
  );
  const totalElements = chartsData?.pages[0]?.totalElements ?? 0;

  const handleConfirm = () => {
    if (selected !== null) {
      const selectedChart = charts.find((c) => c.id === selected);
      onAdd(selected, selectedChart?.chartType);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>차트 추가</DialogTitle>
          <DialogDescription className="sr-only">대시보드에 추가할 차트를 선택합니다.</DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <input
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            placeholder="차트 검색..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="max-h-72 overflow-y-auto space-y-1 border rounded-md p-1">
            {isLoading ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : charts.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">
                차트가 없습니다.
              </p>
            ) : (
              <>
                {charts.map((chart: ChartListItem) => (
                  <button
                    key={chart.id}
                    className={`w-full text-left px-3 py-2 rounded-sm text-sm transition-colors ${
                      selected === chart.id
                        ? 'bg-primary text-primary-foreground'
                        : 'hover:bg-accent hover:text-accent-foreground'
                    }`}
                    onClick={() => setSelected(chart.id)}
                  >
                    <div className="font-medium">{chart.name}</div>
                    {chart.description && (
                      <div className="text-xs opacity-70 truncate">{chart.description}</div>
                    )}
                    <div className="text-xs opacity-60">{chart.savedQueryName}</div>
                  </button>
                ))}
                {hasNextPage && (
                  <button
                    type="button"
                    onClick={() => fetchNextPage()}
                    disabled={isFetchingNextPage}
                    className="mx-auto block rounded-md px-3 py-1.5 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground"
                  >
                    {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
                  </button>
                )}
              </>
            )}
          </div>
          {!isLoading && totalElements > 0 && (
            <p className="text-xs text-muted-foreground">
              전체 {totalElements}건 중 {charts.length}건 표시
              {hasNextPage && ' — 검색으로 나머지를 바로 찾을 수 있습니다'}
            </p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            취소
          </Button>
          <Button onClick={handleConfirm} disabled={selected === null || isPending}>
            {isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            추가
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function DashboardEditorPage() {
  const { id } = useParams<{ id: string }>();
  const dashboardId = id ? parseInt(id, 10) : null;
  const navigate = useNavigate();

  const queryClient = useQueryClient();
  const {
    data: dashboard,
    isLoading,
    isError: isDashboardError,
    error: dashboardError,
    refetch,
  } = useDashboard(dashboardId);

  // 새로고침(또는 최초 로드) 실패를 1회성 토스트로 알린다.
  // placeholderData가 이전 데이터를 유지하기 때문에 화면상 아무 변화가 없어 실패가
  // 완전히 조용해지는 문제(#566)를 막기 위해, error 객체 참조가 바뀔 때만(=새 실패 발생 시) 알린다.
  useEffect(() => {
    if (isDashboardError && dashboardError) {
      handleApiError(dashboardError, '대시보드 정보를 불러오지 못했습니다.');
    }
  }, [isDashboardError, dashboardError]);

  // 위젯 데이터 중 내보내기 불가(정책 차단·열람 불가)가 하나라도 있으면 PDF 내보내기를 숨긴다(보조 다운로드 → 숨김, S4).
  // 볼 수 없는 차트의 위젯(accessible=false, WD-195)은 데이터를 받지 않고 안내 문구만 그리므로 내보낼 데이터가 없다 — 판정에서 뺀다.
  const pdfExportAllowed = useDashboardExportAllowed(
    (dashboard?.widgets ?? []).filter((w) => w.accessible !== false).map((w) => w.chartId),
  );

  // 작성자만 위젯 추가·삭제·배치를 고칠 수 있다(서버는 남의 요청을 404 로 거절) — 남이 공유한 대시보드는 읽기 전용으로 보인다(WD-196).
  // 새로고침·PDF·전체화면·링크 복사처럼 보기 성격의 기능은 그대로 둔다.
  const isOwner = useIsOwner();
  const canEdit = dashboard != null && isOwner(dashboard.createdBy);

  const addWidgetMutation = useAddWidget(dashboardId!);
  const removeWidgetMutation = useRemoveWidget(dashboardId!);
  const updateWidgetMutation = useUpdateWidget(dashboardId!);

  const [isEditing, setIsEditing] = useState(false);
  // 비동기 재동기화가 끝났을 때 아직 편집 중인지 보려는 최신값(WD-367) — 그사이 「완료」·「편집 취소」로 편집이 끝났으면 편집 상태를 되살리지 않는다
  const isEditingRef = useRef(false);
  useEffect(() => {
    isEditingRef.current = isEditing;
  }, [isEditing]);
  // 「완료」 배치 저장 요청이 도는 중 — 두 번 눌러 같은 PUT 을 거듭 보내지 않게 버튼을 잠근다(WD-330)
  const [savingLayout, setSavingLayout] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [addWidgetOpen, setAddWidgetOpen] = useState(false);

  // 위젯 삭제 확인 다이얼로그 상태 — null이면 닫힘, 숫자면 삭제 대기 중인 widgetId
  const [deleteConfirmWidgetId, setDeleteConfirmWidgetId] = useState<number | null>(null);

  // 편집 중 로컬 위젯 목록 스냅샷 — 취소 시 롤백에 사용
  // null이면 편집 중이 아님
  const [editingWidgets, setEditingWidgets] = useState<DashboardWidget[] | null>(null);

  // Local layout state (breakpoint → LayoutItem[])
  const [localLayouts, setLocalLayouts] = useState<ResponsiveLayouts>({});
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync layout from server data when not editing (state-based tracking)
  const [syncedDashboardId, setSyncedDashboardId] = useState<number | null>(null);
  if (dashboard && !isEditing && dashboard.id !== syncedDashboardId) {
    setSyncedDashboardId(dashboard.id);
    const items = widgetsToLayoutItems(dashboard.widgets);
    setLocalLayouts({ lg: items, md: items, sm: items });
  }

  // 자동 새로고침은 각 위젯의 단건 쿼리(useChartData)의 refetchInterval 이 담당한다(DashboardWidgetCard).
  // 과거 여기서 일괄 쿼리(`/dashboards/{id}/data`)를 주기적으로 무효화했지만, 그 응답은 계약 불일치로
  // 화면에 쓰이지 않으면서 서버에서 모든 저장 쿼리를 한 번 더 실행시켰다(#778) — 일괄 호출 자체를 제거했다.

  /**
   * 헤더 새로고침 — 대시보드 메타와 이 대시보드 모든 위젯의 표시 데이터(`/charts/{id}/data`)를 실제로 다시 조회한다.
   * 과거엔 메타(`/dashboards/{id}`)만 다시 불러 위젯 데이터는 그대로였다(#778).
   * 보이지 않아 비활성인 위젯 쿼리는 무효화만 되어, 화면에 들어올 때 새로 조회된다.
   * 실패한 위젯은 각 카드의 신선도 바에 "새로고침 실패"로 남고, 여기서는 1회 토스트로만 알린다(#566).
   */
  const handleRefreshAll = useCallback(async () => {
    // 볼 수 없는 차트의 위젯(WD-195)은 데이터 쿼리가 없으므로 다시 조회할 대상에서 뺀다 — 넣으면 404 로 "불러오지 못했습니다" 토스트가 뜬다.
    const chartIds = [
      ...new Set((dashboard?.widgets ?? []).filter((w) => w.accessible !== false).map((w) => w.chartId)),
    ];
    try {
      await Promise.all([
        // 메타 조회 실패는 위쪽 useEffect 가 이미 토스트하므로 여기서 다시 throw 하지 않는다(중복 토스트 방지)
        refetch(),
        ...chartIds.map((chartId) =>
          queryClient.invalidateQueries(
            { queryKey: ['analytics', 'charts', chartId, 'data'] },
            { throwOnError: true },
          ),
        ),
      ]);
    } catch (err) {
      handleApiError(err, '위젯 데이터를 불러오지 못했습니다.');
    }
  }, [dashboard?.widgets, queryClient, refetch]);

  // Fullscreen API
  const containerRef = useRef<HTMLDivElement>(null);

  const toggleFullscreen = useCallback(async () => {
    if (!document.fullscreenElement) {
      try {
        await containerRef.current?.requestFullscreen();
        setIsFullscreen(true);
      } catch {
        // Browser may deny fullscreen
      }
    } else {
      await document.exitFullscreen();
      setIsFullscreen(false);
    }
  }, []);

  useEffect(() => {
    const handler = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', handler);
    return () => document.removeEventListener('fullscreenchange', handler);
  }, []);

  // onLayoutChange: 편집 모드에서는 로컬 layouts만 갱신하고 서버에는 즉시 저장하지 않는다.
  // "완료" 클릭 시 handleSaveEdit에서 일괄 서버 반영.
  const handleLayoutChange = useCallback(
    (_layout: readonly LayoutItem[], allLayouts: ResponsiveLayouts) => {
      if (!isEditing || !dashboard) return;
      setLocalLayouts(allLayouts);
    },
    [isEditing, dashboard]
  );

  // 키보드로 바꾼 위치·크기 안내(WD-324) — 화면 읽기 프로그램이 읽도록 aria-live 영역에 둔다
  const [layoutAnnouncement, setLayoutAnnouncement] = useState('');

  // 이동 손잡이 화살표 키(WD-324): 끌기와 같은 규칙(밀어내기·세로 압축)으로 lg 레이아웃만 바꾸고, 저장은 끌기처럼
  // 「완료」 때 handleSaveEdit 이 한다. 바뀐 위치·크기를 알려 보지 않고도 결과를 알 수 있게 한다.
  const handleKeyboardLayout = useCallback(
    (widgetId: number, action: KeyboardLayoutAction) => {
      if (!isEditing || !dashboard) return;
      const id = String(widgetId);
      const current =
        (localLayouts.lg as LayoutItem[] | undefined) ??
        widgetsToLayoutItems(editingWidgets ?? dashboard.widgets);
      const next = applyKeyboardLayoutAction(current, id, action);
      setLocalLayouts((prev) => ({ ...prev, lg: next }));
      const item = next.find((l) => l.i === id);
      const widget = (editingWidgets ?? dashboard.widgets).find((w) => w.id === widgetId);
      if (item) {
        setLayoutAnnouncement(describeLayoutItem(widget?.chartName ?? '위젯', item));
      }
    },
    [isEditing, dashboard, localLayouts, editingWidgets]
  );

  // 위젯 삭제 버튼 클릭 시: 즉시 삭제하지 않고 확인 다이얼로그를 표시한다.
  const handleRemoveWidget = useCallback(
    (widgetId: number) => {
      setDeleteConfirmWidgetId(widgetId);
    },
    []
  );

  /**
   * 서버의 대시보드를 다시 읽어 편집 중 위젯 목록·배치를 맞춘다 — 위젯 추가 성공 뒤와, 추가·삭제가 서버 상태와 어긋나 실패했을 때 쓴다(WD-367).
   * 이미 있던 위젯은 편집 중 배치를 그대로 두고(서버 값으로 다시 만들면 「완료」 전에 바꿔 둔 배치가 사라졌다 — WD-331),
   * 서버에 새로 생긴 위젯은 더하고, 서버에서 없어진 위젯은 뺀다. 다시 읽기에 실패하면 null 을 돌려준다.
   */
  const syncEditingWithServer = useCallback(async (): Promise<DashboardWidget[] | null> => {
    // 다시 읽기가 실패해도 refetch 결과의 data 는 직전 값이라, 오류면 맞추지 않는다(대시보드가 없어진 경우 등).
    // 추가 성공 직후에는 useAddWidget 의 무효화가 이미 다시 읽는 중이라, 취소하고 새로 보내지 않고 그 결과를 기다린다.
    const { data: updated, isError } = await refetch({ cancelRefetch: false });
    if (isError || !updated) return null;
    if (!isEditingRef.current) return updated.widgets;
    setEditingWidgets(updated.widgets);
    const serverIds = new Set(updated.widgets.map((w) => String(w.id)));
    setLocalLayouts((prev) => {
      const kept = ((prev.lg as LayoutItem[] | undefined) ?? []).filter((i) => serverIds.has(i.i));
      const keptIds = new Set(kept.map((i) => i.i));
      const added = widgetsToLayoutItems(updated.widgets).filter((i) => !keptIds.has(i.i));
      const items = [...kept, ...added];
      return { lg: items, md: items, sm: items };
    });
    return updated.widgets;
  }, [refetch]);

  // 삭제 확인 다이얼로그에서 "삭제" 클릭 시: 즉시 서버에 DELETE 요청을 보낸다.
  // 위젯 추가(handleAddWidget)와 동일하게 즉시 서버 반영하여 동작 일관성을 유지한다.
  // 이전에는 로컬 상태에만 저장하고 "완료" 버튼 클릭 시 일괄 반영했으나,
  // 추가는 즉시 반영, 삭제는 지연 반영하여 동작이 불일치하는 버그(#133)를 수정한다.
  const handleConfirmDelete = useCallback(async () => {
    if (deleteConfirmWidgetId === null) return;
    const id = deleteConfirmWidgetId;
    setDeleteConfirmWidgetId(null);
    try {
      await removeWidgetMutation.mutateAsync(id);
      // 서버 DELETE 성공 후 로컬 editingWidgets에서도 제거하여 UI 동기화
      setEditingWidgets((prev) => (prev ? prev.filter((w) => w.id !== id) : prev));
      // 레이아웃은 편집 중 배치에서 지운 위젯만 뺀다 — 편집 시작 때 목록으로 다시 만들면
      // 그 사이 바꿔 둔 다른 위젯의 크기·위치가 안내 없이 되돌아갔다(WD-331).
      setLocalLayouts((prev) => {
        const items = ((prev.lg as LayoutItem[] | undefined) ?? []).filter((i) => i.i !== String(id));
        return { lg: items, md: items, sm: items };
      });
      toast.success('위젯이 제거되었습니다.');
    } catch (error) {
      // WD-367: 다른 탭·사용자가 먼저 지운 위젯이면(서버 404) 실패 토스트만 띄우고 끝내면 위젯이 화면에 남아 다시 눌러도 같은 오류가 되풀이됐다.
      // 404 면 서버 목록을 다시 읽어 맞추고, 정말 서버에 없으면 「이미 삭제됨」으로 안내한다. 대시보드 자체가 없어진 404 는 다시 읽기도 실패해 원래 오류를 보인다.
      // 404 가 아닌 실패(500·연결 끊김)는 서버 상태를 알 수 없으므로 원래 오류만 보인다.
      const serverWidgets = isNotFoundError(error) ? await syncEditingWithServer() : null;
      if (serverWidgets && !serverWidgets.some((w) => w.id === id)) {
        toast.info('다른 곳에서 이미 삭제된 위젯이라 화면에서도 뺐습니다.');
      } else {
        handleApiError(error, '위젯 제거에 실패했습니다.');
      }
    }
  }, [deleteConfirmWidgetId, removeWidgetMutation, syncEditingWithServer]);

  const handleAddWidget = useCallback(
    async (chartId: number, chartType?: string) => {
      if (!dashboard) return;
      // 현재 편집 중인 위젯 목록 기준으로 최대 Y 계산 (로컬 삭제 반영)
      const currentWidgets = editingWidgets ?? dashboard.widgets;

      // 이미 대시보드에 동일한 chartId의 위젯이 존재하면 중복 추가를 막고 경고 표시
      if (currentWidgets.some((w) => w.chartId === chartId)) {
        toast.error('이미 대시보드에 추가된 차트입니다.');
        return;
      }
      // 맨 아래 위치는 편집 중 배치(localLayouts) 기준 — 서버 좌표로 재면 크기를 늘려 둔 위젯과 겹친다(WD-331)
      const editingItems = (localLayouts.lg as LayoutItem[] | undefined) ?? widgetsToLayoutItems(currentWidgets);
      const maxY = editingItems.reduce((acc, i) => Math.max(acc, i.y + i.h), 0);
      // MAP 차트는 전체 폭, 큰 높이로 배치
      const isMapChart = chartType === 'MAP';
      try {
        await addWidgetMutation.mutateAsync({
          chartId,
          positionX: 0,
          positionY: maxY,
          width: isMapChart ? 12 : 6,
          height: isMapChart ? 6 : 4,
        });
        toast.success('차트가 대시보드에 추가되었습니다.');
        setAddWidgetOpen(false);
        await syncEditingWithServer();
      } catch (error) {
        handleApiError(error, '위젯 추가에 실패했습니다.');
        // WD-367: 다른 탭에서 먼저 추가했거나(409) 차트가 지워졌으면(404) 화면이 서버와 어긋난 것이다 —
        // 대시보드를 다시 읽어 그 위젯을 보인다(지워진 차트를 고르기 목록에서 빼는 것은 useAddWidget 의 onError 가 한다).
        if (isConflictError(error) || isNotFoundError(error)) {
          await syncEditingWithServer();
        }
      }
    },
    [dashboard, editingWidgets, localLayouts, addWidgetMutation, syncEditingWithServer]
  );

  /**
   * 편집 모드 진입: 현재 서버 위젯 목록을 스냅샷으로 저장하여 취소 시 롤백에 사용.
   */
  const handleStartEdit = useCallback(() => {
    // 남의 대시보드는 편집 모드에 들어가지 않는다(버튼도 숨기지만 진입점을 한 곳에서 막아 둔다, WD-196)
    if (!dashboard || !canEdit) return;
    // 전체화면은 대시보드 영역만 띄우므로, 문서 본문에 붙는 「차트 추가」·위젯 삭제 대화상자가 보이지 않고
    // 클릭만 삼킨다 — 편집은 전체화면을 풀고 시작한다(WD-329). 상태는 fullscreenchange 가 맞춘다.
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {});
    }
    setEditingWidgets([...dashboard.widgets]);
    const items = widgetsToLayoutItems(dashboard.widgets);
    setLocalLayouts({ lg: items, md: items, sm: items });
    setIsEditing(true);
  }, [dashboard, canEdit]);

  /**
   * 편집 취소(X 버튼): 로컬 변경사항(삭제·레이아웃)을 버리고 스냅샷으로 복원.
   * 서버에는 아무것도 반영하지 않는다.
   */
  const handleCancelEdit = useCallback(() => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    // editingWidgets를 버리고 서버 원본 상태로 복원
    if (dashboard) {
      const items = widgetsToLayoutItems(dashboard.widgets);
      setLocalLayouts({ lg: items, md: items, sm: items });
    }
    setEditingWidgets(null);
    setIsEditing(false);
  }, [dashboard]);

  /**
   * 편집 완료(완료 버튼): 레이아웃 변경사항을 서버에 일괄 반영한다.
   * 삭제는 확인 시 즉시 서버에 반영되므로 여기서는 레이아웃 PATCH만 처리한다.
   * 저장 요청을 모두 기다려, 하나라도 실패하면 사유를 알리고 편집 모드를 유지한다(WD-330) —
   * 예전에는 결과를 기다리지 않고 편집을 닫아, 403·400·500 이 나도 저장된 것처럼 보이다 새로고침하면 원래대로 돌아갔다.
   * 바뀐 위젯만 보낸다 — 그대로인 위젯까지 보내면 실패할 요청만 늘어난다.
   */
  const handleSaveEdit = useCallback(async () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    if (!dashboard || !editingWidgets) {
      setIsEditing(false);
      setEditingWidgets(null);
      return;
    }

    const remainingIds = new Set(editingWidgets.map((w) => w.id));
    const serverById = new Map(dashboard.widgets.map((w) => [w.id, w]));

    // 레이아웃 변경 서버에 반영 (lg 기준) — 현재 편집 목록에 있고 서버 값과 다른 위젯만
    const lgItems = (localLayouts.lg as LayoutItem[] | undefined) ?? [];
    const changed = lgItems.filter((item) => {
      const widgetId = parseInt(item.i, 10);
      const server = serverById.get(widgetId);
      if (!remainingIds.has(widgetId) || !server) return false;
      return (
        item.x !== server.positionX ||
        item.y !== server.positionY ||
        item.w !== server.width ||
        item.h !== server.height
      );
    });

    setSavingLayout(true);
    const results = await Promise.allSettled(
      changed.map((item) =>
        updateWidgetMutation.mutateAsync({
          widgetId: parseInt(item.i, 10),
          data: { positionX: item.x, positionY: item.y, width: item.w, height: item.h },
        })
      )
    );
    setSavingLayout(false);

    const failed = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected');
    if (failed.length > 0) {
      // 편집 모드와 화면의 배치는 그대로 둔다 — 다시 「완료」를 누르거나 「편집 취소」로 서버 값으로 되돌릴 수 있다.
      // 성공한 위젯은 서버에 반영돼 다음 시도에서 빠진다.
      const what =
        failed.length === changed.length
          ? '배치를 저장하지 못했습니다'
          : `위젯 ${changed.length}개 중 ${failed.length}개의 배치를 저장하지 못했습니다`;
      toast.error(`${what} — ${extractApiError(failed[0].reason, '잠시 후 다시 시도해 주세요.')}`);
      // WD-367: 다른 곳에서 지운 위젯(404)이 섞였으면 서버 목록으로 맞춰 그 위젯을 빼 둔다 — 그대로 두면 「완료」가 같은 이유로 끝없이 실패했다
      if (failed.some((r) => isNotFoundError(r.reason))) await syncEditingWithServer();
      return;
    }

    setEditingWidgets(null);
    setIsEditing(false);
  }, [dashboard, editingWidgets, localLayouts, updateWidgetMutation, syncEditingWithServer]);

  /**
   * 편집 모드에서 「완료」 전에 바꾼 배치가 있는지(WD-323). 배치·크기는 완료를 눌러야 서버에 가므로, 이 값이 참인 채로 다른 화면으로 가면
   * 변경이 사라진다 — 다른 편집 화면처럼 이탈 확인을 띄운다. 위젯 추가·삭제는 즉시 서버에 반영되므로 세지 않는다.
   * 서버 위젯 좌표와 로컬 lg 배치를 비교한다 — react-grid-layout 이 마운트 때 같은 값으로 onLayoutChange 를 부르므로 「호출 여부」로는 판단할 수 없다.
   */
  const layoutDirty = useMemo(() => {
    if (!isEditing || !dashboard) return false;
    const items = (localLayouts.lg as LayoutItem[] | undefined) ?? [];
    return dashboard.widgets.some((w) => {
      const item = items.find((i) => i.i === String(w.id));
      return (
        item !== undefined &&
        (item.x !== w.positionX || item.y !== w.positionY || item.w !== w.width || item.h !== w.height)
      );
    });
  }, [isEditing, dashboard, localLayouts]);
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(layoutDirty);

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  if (!dashboard) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20">
        <LayoutDashboard className="h-12 w-12 text-muted-foreground" />
        <p className="text-muted-foreground">대시보드를 찾을 수 없습니다.</p>
        <Button variant="outline" onClick={() => navigate('/analytics/dashboards')}>
          목록으로
        </Button>
      </div>
    );
  }

  // 편집 중에는 로컬 editingWidgets를 렌더링 소스로 사용하고,
  // 비편집 중에는 서버 원본 dashboard.widgets를 사용한다.
  const displayWidgets = editingWidgets ?? dashboard.widgets;

  const lgItems = (localLayouts.lg as LayoutItem[] | undefined) ?? widgetsToLayoutItems(displayWidgets);
  const currentLayouts: ResponsiveLayouts = { ...localLayouts, lg: lgItems };

  return (
    <div ref={containerRef} className="flex flex-col h-full min-h-0 bg-background">
      {/* Toolbar */}
      {/* 좁은 화면에서는 버튼 묶음이 다음 줄로 내려가 이름이 읽히고 「완료」·「편집 취소」가 화면 밖으로 밀리지 않는다(WD-321) */}
      <div className="flex flex-wrap items-center gap-2 px-4 py-2 border-b shrink-0 bg-background">
        <Button
          variant="ghost"
          size="icon"
          // 아이콘만 있는 버튼이라 이름을 붙인다 — 쿼리 편집기·차트 빌더와 같은 문구 (WD-401)
          aria-label="목록으로 돌아가기"
          title="목록으로 돌아가기"
          // 화면 안 「←」도 미저장 배치 확인을 거친다(WD-323) — navigate 직접 호출은 가드를 우회한다
          onClick={() => requestNavigate('/analytics/dashboards')}
          className="h-8 w-8"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        </Button>

        <div className="flex-1 min-w-40">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <h1 className="text-lg font-semibold truncate min-w-0" title={dashboard.name}>
              {dashboard.name}
            </h1>
            {dashboard.isShared && (
              <Badge variant="secondary" className="text-xs shrink-0">공유</Badge>
            )}
            {dashboard.autoRefreshSeconds && (
              <Badge variant="outline" className="text-xs gap-1 shrink-0">
                <RefreshCw className="h-2.5 w-2.5" />
                {dashboard.autoRefreshSeconds}초
              </Badge>
            )}
            {/* 남이 공유한 대시보드 — 편집 버튼 대신 읽기 전용임을 알린다(WD-196). 누가 공유했는지 함께 보여 줘 「왜 못 고치는지」를 바로 알게 한다. */}
            {!canEdit && (
              <Badge
                variant="outline"
                className="text-xs gap-1 shrink-0"
                title={`${dashboard.createdByName}님이 공유한 대시보드입니다. 작성자만 고칠 수 있습니다.`}
                data-testid="dashboard-readonly-badge"
              >
                <Eye className="h-2.5 w-2.5" />
                보기 전용
              </Badge>
            )}
          </div>
          {dashboard.description && (
            <p className="text-xs text-muted-foreground truncate">{dashboard.description}</p>
          )}
        </div>

        <div className="flex flex-wrap items-center justify-end gap-1.5 ml-auto">
          {/* 공유 (#99, #522) — 현재 URL을 클립보드에 복사하여 협업 시 빠른 링크 공유 지원.
              clipboard API 미지원 환경에서는 텍스트 영역 fallback.
              단, 백엔드(AnalyticsDashboardRepository.findById)는 소유자이거나
              isShared=true인 경우만 조회를 허용하므로, 비공개 대시보드에서는
              링크를 복사해도 받는 사람이 열 수 없다 — 이를 사용자에게 명확히 안내한다. */}
          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              if (!dashboard.isShared) {
                toast.error(
                  '이 대시보드는 비공개입니다. 다른 사용자와 공유하려면 목록에서 "공개 대시보드"를 먼저 켜세요.',
                );
                return;
              }
              const url = window.location.href;
              try {
                await navigator.clipboard.writeText(url);
                toast.success('대시보드 링크가 복사되었습니다.');
              } catch {
                // clipboard 미지원/거부 시 사용자에게 URL을 직접 보여줌
                toast.error(`복사 실패. URL: ${url}`);
              }
            }}
            title={dashboard.isShared ? '공유 링크 복사' : '비공개 대시보드 (공유 링크 복사 불가)'}
            className="h-8"
            aria-label="공유 링크 복사"
          >
            <Share2 className="h-3.5 w-3.5" />
          </Button>

          {/* PDF 내보내기 (#99) — window.print()로 브라우저 인쇄 다이얼로그를 띄워
              "PDF로 저장" 옵션을 사용자가 선택하도록 한다. 별도 라이브러리 없이
              가장 가벼운 1차 구현. 추후 puppeteer 기반 server-side 렌더링 추천.
              내보내기 불가 위젯이 있으면 숨긴다(S4 UI 수준 차단 — 브라우저 인쇄 자체는 막을 수 없다). */}
          {pdfExportAllowed && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => window.print()}
              title="PDF로 내보내기 (인쇄 다이얼로그)"
              className="h-8"
              aria-label="PDF로 내보내기"
            >
              <Download className="h-3.5 w-3.5" />
            </Button>
          )}

          <Button
            variant="outline"
            size="sm"
            onClick={() => void handleRefreshAll()}
            title="새로고침"
            className="h-8"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </Button>

          {/* 편집 중에는 전체화면을 막는다 — 전체화면 안에서는 편집 대화상자가 보이지 않는다(WD-329) */}
          {!isEditing && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => void toggleFullscreen()}
              title={isFullscreen ? '전체화면 종료' : '전체화면'}
              className="h-8"
            >
              {isFullscreen ? (
                <Minimize2 className="h-3.5 w-3.5" />
              ) : (
                <Maximize2 className="h-3.5 w-3.5" />
              )}
            </Button>
          )}

          {/* 편집 진입·차트 추가·완료·취소는 작성자에게만 보인다(WD-196) */}
          {!canEdit ? null : isEditing ? (
            <>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setAddWidgetOpen(true)}
                className="h-8"
              >
                <Plus className="h-3.5 w-3.5" />
                차트 추가
              </Button>
              {/* 완료: 로컬 변경사항(삭제·레이아웃)을 서버에 일괄 반영 */}
              <Button
                size="sm"
                onClick={() => void handleSaveEdit()}
                className="h-8"
                disabled={savingLayout}
              >
                <Check className="h-3.5 w-3.5" />
                완료
              </Button>
              {/* 취소: 로컬 변경사항을 버리고 원래 상태로 복원 */}
              <Button
                size="sm"
                variant="ghost"
                onClick={handleCancelEdit}
                className="h-8"
                title="편집 취소"
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            </>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={handleStartEdit}
              className="h-8"
            >
              <Pencil className="h-3.5 w-3.5" />
              편집
            </Button>
          )}
        </div>
      </div>

      {/* Grid — 편집 중에는 로컬 displayWidgets로 렌더링하여 즉시 UI 반영 */}
      <div className="flex-1 overflow-auto p-3">
        {displayWidgets.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
            <LayoutDashboard className="h-12 w-12 text-muted-foreground" />
            <div>
              <p className="text-muted-foreground font-medium">위젯이 없습니다.</p>
              {/* 남의 대시보드에서는 할 수 없는 일(편집 모드)을 권하지 않는다(WD-196) */}
              <p className="text-sm text-muted-foreground mt-1">
                {canEdit ? '편집 모드에서 차트를 추가하세요.' : '작성자가 아직 차트를 추가하지 않았습니다.'}
              </p>
            </div>
            {canEdit && !isEditing && (
              <Button variant="outline" onClick={handleStartEdit}>
                <Pencil className="h-4 w-4" />
                편집 모드
              </Button>
            )}
          </div>
        ) : (
          <GridArea
            widgets={displayWidgets}
            isEditing={isEditing}
            layouts={currentLayouts}
            onLayoutChange={handleLayoutChange}
            onRemove={handleRemoveWidget}
            onKeyboardLayout={handleKeyboardLayout}
            autoRefreshSeconds={dashboard.autoRefreshSeconds}
          />
        )}
        {/* 키보드 배치 조작 결과 안내(WD-324) — 보이지 않고 화면 읽기 프로그램만 읽는다 */}
        <div role="status" aria-live="polite" className="sr-only" data-testid="dashboard-layout-announcement">
          {isEditing ? layoutAnnouncement : ''}
        </div>
      </div>

      {unsavedChangesDialog}

      <AddWidgetDialog
        open={addWidgetOpen}
        onOpenChange={setAddWidgetOpen}
        onAdd={handleAddWidget}
        isPending={addWidgetMutation.isPending}
      />

      {/* 위젯 삭제 확인 다이얼로그 — 실수 삭제 방지 */}
      <AlertDialog
        open={deleteConfirmWidgetId !== null}
        onOpenChange={(open) => { if (!open) setDeleteConfirmWidgetId(null); }}
      >
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>위젯 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              이 위젯을 삭제하시겠습니까? 삭제 즉시 서버에 반영되며 되돌릴 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => void handleConfirmDelete()}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
