import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';

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
import { useCallback, useEffect, useRef,useState } from 'react';
import type { LayoutItem, ResponsiveLayouts } from 'react-grid-layout';
import { ResponsiveGridLayout, useContainerWidth } from 'react-grid-layout';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { DashboardWidgetCard } from '../../components/analytics/DashboardWidgetCard';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Skeleton } from '../../components/ui/skeleton';
import {
  useAddWidget,
  useCharts,
  useDashboard,
  useRemoveWidget,
  useUpdateWidget,
} from '../../hooks/queries/useAnalytics';
import { handleApiError } from '../../lib/api-error';
import type { ChartListItem, DashboardWidget } from '../../types/analytics';

// Sub-component that owns width measurement for ResponsiveGridLayout
interface GridAreaProps {
  widgets: DashboardWidget[];
  isEditing: boolean;
  layouts: ResponsiveLayouts;
  onLayoutChange: (layout: readonly LayoutItem[], allLayouts: ResponsiveLayouts) => void;
  onRemove: (widgetId: number) => void;
}

function GridArea({ widgets, isEditing, layouts, onLayoutChange, onRemove }: GridAreaProps) {
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
          <div key={String(widget.id)} className="relative overflow-hidden">
            {isEditing && (
              <div className="drag-handle absolute top-0 left-0 right-8 h-8 cursor-grab active:cursor-grabbing z-10 bg-transparent" />
            )}
            <DashboardWidgetCard
              widget={widget}
              isEditing={isEditing}
              onRemove={onRemove}
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
  onAdd: (chartId: number) => void;
  isPending: boolean;
}

function AddWidgetDialog({ open, onOpenChange, onAdd, isPending }: AddWidgetDialogProps) {
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<number | null>(null);
  const { data: chartsData, isLoading } = useCharts({ search: search || undefined, size: 20, page: 0 });
  const charts = chartsData?.content ?? [];

  const handleConfirm = () => {
    if (selected !== null) {
      onAdd(selected);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>차트 추가</DialogTitle>
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
            {isPending && <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />}
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

  const { data: dashboard, isLoading, refetch } = useDashboard(dashboardId);
  const addWidgetMutation = useAddWidget(dashboardId!);
  const removeWidgetMutation = useRemoveWidget(dashboardId!);
  const updateWidgetMutation = useUpdateWidget(dashboardId!);

  const [isEditing, setIsEditing] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [addWidgetOpen, setAddWidgetOpen] = useState(false);

  // Local layout state (breakpoint → LayoutItem[])
  const [localLayouts, setLocalLayouts] = useState<ResponsiveLayouts>({});
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Sync layout from server data when not editing
  useEffect(() => {
    if (dashboard && !isEditing) {
      const items = widgetsToLayoutItems(dashboard.widgets);
      setLocalLayouts({ lg: items, md: items, sm: items });
    }
  }, [dashboard, isEditing]);

  // Auto-refresh
  useEffect(() => {
    if (!dashboard?.autoRefreshSeconds) return;
    const interval = setInterval(() => {
      void refetch();
    }, dashboard.autoRefreshSeconds * 1000);
    return () => clearInterval(interval);
  }, [dashboard?.autoRefreshSeconds, refetch]);

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

  // onLayoutChange: (layout: Layout, layouts: ResponsiveLayouts) => void
  // Layout = readonly LayoutItem[]
  const handleLayoutChange = useCallback(
    (layout: readonly LayoutItem[], allLayouts: ResponsiveLayouts) => {
      if (!isEditing || !dashboard) return;
      setLocalLayouts(allLayouts);

      // Debounce save: 1 second after last change
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
      saveTimerRef.current = setTimeout(() => {
        layout.forEach((item) => {
          const widgetId = parseInt(item.i, 10);
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
      }, 1000);
    },
    [isEditing, dashboard, updateWidgetMutation]
  );

  const handleRemoveWidget = useCallback(
    async (widgetId: number) => {
      try {
        await removeWidgetMutation.mutateAsync(widgetId);
        toast.success('위젯이 제거되었습니다.');
      } catch (error) {
        handleApiError(error, '위젯 제거에 실패했습니다.');
      }
    },
    [removeWidgetMutation]
  );

  const handleAddWidget = useCallback(
    async (chartId: number) => {
      if (!dashboard) return;
      const maxY = dashboard.widgets.reduce(
        (acc, w) => Math.max(acc, w.positionY + w.height),
        0
      );
      try {
        await addWidgetMutation.mutateAsync({
          chartId,
          positionX: 0,
          positionY: maxY,
          width: 6,
          height: 4,
        });
        toast.success('차트가 대시보드에 추가되었습니다.');
        setAddWidgetOpen(false);
      } catch (error) {
        handleApiError(error, '위젯 추가에 실패했습니다.');
      }
    },
    [dashboard, addWidgetMutation]
  );

  const handleExitEdit = () => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    setIsEditing(false);
  };

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

  const lgItems = (localLayouts.lg as LayoutItem[] | undefined) ?? widgetsToLayoutItems(dashboard.widgets);
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
                <Plus className="h-3.5 w-3.5 mr-1.5" />
                차트 추가
              </Button>
              <Button size="sm" onClick={handleExitEdit} className="h-8">
                <Check className="h-3.5 w-3.5 mr-1.5" />
                완료
              </Button>
              <Button size="sm" variant="ghost" onClick={handleExitEdit} className="h-8">
                <X className="h-3.5 w-3.5" />
              </Button>
            </>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => setIsEditing(true)}
              className="h-8"
            >
              <Pencil className="h-3.5 w-3.5 mr-1.5" />
              편집
            </Button>
          )}
        </div>
      </div>

      {/* Grid */}
      <div className="flex-1 overflow-auto p-3">
        {dashboard.widgets.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
            <LayoutDashboard className="h-12 w-12 text-muted-foreground" />
            <div>
              <p className="text-muted-foreground font-medium">위젯이 없습니다.</p>
              <p className="text-sm text-muted-foreground mt-1">
                편집 모드에서 차트를 추가하세요.
              </p>
            </div>
            {!isEditing && (
              <Button variant="outline" onClick={() => setIsEditing(true)}>
                <Pencil className="h-4 w-4 mr-2" />
                편집 모드
              </Button>
            )}
          </div>
        ) : (
          <GridArea
            widgets={dashboard.widgets}
            isEditing={isEditing}
            layouts={currentLayouts}
            onLayoutChange={handleLayoutChange}
            onRemove={handleRemoveWidget}
          />
        )}
      </div>

      <AddWidgetDialog
        open={addWidgetOpen}
        onOpenChange={setAddWidgetOpen}
        onAdd={handleAddWidget}
        isPending={addWidgetMutation.isPending}
      />
    </div>
  );
}
