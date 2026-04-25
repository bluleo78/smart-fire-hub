import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';

import { useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  Check,
  LayoutDashboard,
  Loader2,
  Maximize2,
  Minimize2,
  Pencil,
  Plus,
  RefreshCw,
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
  useCharts,
  useDashboard,
  useDashboardData,
  useRemoveWidget,
  useUpdateWidget,
} from '../../hooks/queries/useAnalytics';
import { handleApiError } from '../../lib/api-error';
import type { ChartListItem, DashboardWidget, WidgetData } from '../../types/analytics';

// Sub-component that owns width measurement for ResponsiveGridLayout
interface GridAreaProps {
  widgets: DashboardWidget[];
  isEditing: boolean;
  layouts: ResponsiveLayouts;
  onLayoutChange: (layout: readonly LayoutItem[], allLayouts: ResponsiveLayouts) => void;
  onRemove: (widgetId: number) => void;
  batchDataMap: Map<number, WidgetData>;
  autoRefreshSeconds?: number | null;
  dataUpdatedAt?: number;
  isFetching?: boolean;
  onRefresh?: () => void;
}

function GridArea({
  widgets,
  isEditing,
  layouts,
  onLayoutChange,
  onRemove,
  batchDataMap,
  autoRefreshSeconds,
  dataUpdatedAt,
  isFetching,
  onRefresh,
}: GridAreaProps) {
  const { width, containerRef: gridRef } = useContainerWidth({ initialWidth: 1280 });

  return (
    <div ref={gridRef}>
      <ResponsiveGridLayout
        width={width}
        breakpoints={{ lg: 1200, md: 996, sm: 768 }}
        cols={{ lg: 12, md: 8, sm: 4 }}
        rowHeight={80}
        dragConfig={{ enabled: isEditing, bounded: false, handle: '.drag-handle' }}
        resizeConfig={{ enabled: isEditing, handles: ['se'] }}
        layouts={layouts}
        onLayoutChange={onLayoutChange}
        margin={[8, 8]}
      >
        {widgets.map((widget) => (
          <div key={String(widget.id)}>
            <DashboardWidgetCard
              widget={widget}
              batchData={batchDataMap.get(widget.id)}
              isEditing={isEditing}
              onRemove={onRemove}
              autoRefreshSeconds={autoRefreshSeconds}
              dataUpdatedAt={dataUpdatedAt}
              isFetching={isFetching}
              onRefresh={onRefresh}
            />
          </div>
        ))}
      </ResponsiveGridLayout>
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
  const { data: chartsData, isLoading } = useCharts({ search: search || undefined, size: 20, page: 0 });
  const charts = chartsData?.content ?? [];

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
              charts.map((chart: ChartListItem) => (
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
              ))
            )}
          </div>
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
  const { data: dashboard, isLoading, refetch } = useDashboard(dashboardId);
  const {
    data: dashboardData,
    dataUpdatedAt: dashboardDataUpdatedAt,
    isFetching: dashboardDataFetching,
    refetch: refetchDashboardData,
  } = useDashboardData(dashboardId ?? undefined);
  const addWidgetMutation = useAddWidget(dashboardId!);
  const removeWidgetMutation = useRemoveWidget(dashboardId!);
  const updateWidgetMutation = useUpdateWidget(dashboardId!);

  // Build a map from widgetId → WidgetData for O(1) lookup in GridArea
  const batchDataMap = useMemo(
    () => new Map<number, WidgetData>(
      (dashboardData?.widgets ?? []).map((w) => [w.widgetId, w])
    ),
    [dashboardData?.widgets]
  );

  const [isEditing, setIsEditing] = useState(false);
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

  // Auto-refresh: invalidate batch data query so all widget data refreshes together
  useEffect(() => {
    if (!dashboard?.autoRefreshSeconds) return;
    const interval = setInterval(() => {
      void queryClient.invalidateQueries({
        queryKey: ['analytics', 'dashboards', dashboardId, 'data'],
      });
    }, dashboard.autoRefreshSeconds * 1000);
    return () => clearInterval(interval);
  }, [dashboard?.autoRefreshSeconds, dashboardId, queryClient]);

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

  // 위젯 삭제 버튼 클릭 시: 즉시 삭제하지 않고 확인 다이얼로그를 표시한다.
  const handleRemoveWidget = useCallback(
    (widgetId: number) => {
      setDeleteConfirmWidgetId(widgetId);
    },
    []
  );

  // 삭제 확인 다이얼로그에서 "삭제" 클릭 시: 로컬 editingWidgets에서만 제거한다.
  // 서버에는 "완료" 클릭 시 handleSaveEdit에서 일괄 반영.
  const handleConfirmDelete = useCallback(() => {
    if (deleteConfirmWidgetId === null) return;
    const id = deleteConfirmWidgetId;
    setDeleteConfirmWidgetId(null);
    setEditingWidgets((prev) => {
      if (!prev) return prev;
      const next = prev.filter((w) => w.id !== id);
      // 위젯 제거 후 레이아웃도 동기화
      const items = widgetsToLayoutItems(next);
      setLocalLayouts({ lg: items, md: items, sm: items });
      return next;
    });
    toast.success('위젯이 제거되었습니다.');
  }, [deleteConfirmWidgetId]);

  const handleAddWidget = useCallback(
    async (chartId: number, chartType?: string) => {
      if (!dashboard) return;
      // 현재 편집 중인 위젯 목록 기준으로 최대 Y 계산 (로컬 삭제 반영)
      const currentWidgets = editingWidgets ?? dashboard.widgets;
      const maxY = currentWidgets.reduce(
        (acc, w) => Math.max(acc, w.positionY + w.height),
        0
      );
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
        // 서버에서 최신 상태를 가져와 로컬 편집 목록 및 레이아웃 갱신
        const { data: updated } = await refetch();
        if (updated) {
          setEditingWidgets(updated.widgets);
          const items = widgetsToLayoutItems(updated.widgets);
          setLocalLayouts({ lg: items, md: items, sm: items });
        }
      } catch (error) {
        handleApiError(error, '위젯 추가에 실패했습니다.');
      }
    },
    [dashboard, editingWidgets, addWidgetMutation, refetch]
  );

  /**
   * 편집 모드 진입: 현재 서버 위젯 목록을 스냅샷으로 저장하여 취소 시 롤백에 사용.
   */
  const handleStartEdit = useCallback(() => {
    if (!dashboard) return;
    setEditingWidgets([...dashboard.widgets]);
    const items = widgetsToLayoutItems(dashboard.widgets);
    setLocalLayouts({ lg: items, md: items, sm: items });
    setIsEditing(true);
  }, [dashboard]);

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
   * 편집 완료(완료 버튼): 로컬 변경사항을 서버에 일괄 반영한다.
   * 1. 삭제된 위젯: DELETE 요청 전송
   * 2. 레이아웃 변경: PATCH 요청 전송
   */
  const handleSaveEdit = useCallback(async () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    if (!dashboard || !editingWidgets) {
      setIsEditing(false);
      setEditingWidgets(null);
      return;
    }

    const originalIds = new Set(dashboard.widgets.map((w) => w.id));
    const remainingIds = new Set(editingWidgets.map((w) => w.id));

    // 1. 삭제된 위젯 서버에 반영
    const deletedIds = [...originalIds].filter((id) => !remainingIds.has(id));
    for (const widgetId of deletedIds) {
      try {
        await removeWidgetMutation.mutateAsync(widgetId);
      } catch (error) {
        handleApiError(error, '위젯 제거에 실패했습니다.');
      }
    }

    // 2. 레이아웃 변경 서버에 반영 (lg 기준)
    const lgItems = (localLayouts.lg as LayoutItem[] | undefined) ?? [];
    lgItems.forEach((item) => {
      const widgetId = parseInt(item.i, 10);
      // 삭제된 위젯은 건너뜀
      if (!remainingIds.has(widgetId)) return;
      updateWidgetMutation.mutate({
        widgetId,
        data: {
          positionX: item.x,
          positionY: item.y,
          width: item.w,
          height: item.h,
        },
      });
    });

    setEditingWidgets(null);
    setIsEditing(false);
  }, [dashboard, editingWidgets, localLayouts, removeWidgetMutation, updateWidgetMutation]);

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
      <div className="flex items-center gap-2 px-4 py-2 border-b shrink-0 bg-background">
        <Button
          variant="ghost"
          size="icon"
          onClick={() => navigate('/analytics/dashboards')}
          className="h-8 w-8"
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h1 className="text-lg font-semibold truncate">{dashboard.name}</h1>
            {dashboard.isShared && (
              <Badge variant="secondary" className="text-xs shrink-0">공유</Badge>
            )}
            {dashboard.autoRefreshSeconds && (
              <Badge variant="outline" className="text-xs gap-1 shrink-0">
                <RefreshCw className="h-2.5 w-2.5" />
                {dashboard.autoRefreshSeconds}초
              </Badge>
            )}
          </div>
          {dashboard.description && (
            <p className="text-xs text-muted-foreground truncate">{dashboard.description}</p>
          )}
        </div>

        <div className="flex items-center gap-1.5 shrink-0">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void refetch()}
            title="새로고침"
            className="h-8"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </Button>

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

          {isEditing ? (
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
              <Button size="sm" onClick={() => void handleSaveEdit()} className="h-8">
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
              <p className="text-sm text-muted-foreground mt-1">
                편집 모드에서 차트를 추가하세요.
              </p>
            </div>
            {!isEditing && (
              <Button variant="outline" onClick={handleStartEdit}>
                <Pencil className="h-4 w-4 mr-2" />
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
            batchDataMap={batchDataMap}
            autoRefreshSeconds={dashboard.autoRefreshSeconds}
            dataUpdatedAt={dashboardDataUpdatedAt}
            isFetching={dashboardDataFetching}
            onRefresh={() => void refetchDashboardData()}
          />
        )}
      </div>

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
              이 위젯을 삭제하시겠습니까? "완료" 클릭 전까지는 취소가 가능합니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={handleConfirmDelete}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
