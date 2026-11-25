import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { ArrowLeft, BarChart2, Download, FileImage, FileType,LayoutDashboard,Loader2, Lock, Play,Save } from 'lucide-react';
import { type FormEvent, useCallback,useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import { AxisConfigPanel } from '../../components/analytics/AxisConfigPanel';
import { BOXPLOT_REQUIRED_COLUMNS_LABEL, findMissingBoxplotStats } from '../../components/analytics/boxplot-stats';
import { chartSupportsValueTable } from '../../components/analytics/chart-a11y';
import { findMissingChartColumns } from '../../components/analytics/chart-missing-columns';
import { findMissingChartAxesMessage } from '../../components/analytics/chart-required-axes';
import { ChartConfigWithheldNotice } from '../../components/analytics/ChartConfigWithheldNotice';
import { MemoChartRenderer } from '../../components/analytics/ChartRenderer';
import { ChartTableToggle } from '../../components/analytics/ChartTableToggle';
import { ChartTypeSelector } from '../../components/analytics/ChartTypeSelector';
import { hasMixedSignValues, isNumericColumn, isWeightColumn } from '../../components/analytics/numeric-columns';
import { SharedReadOnlyNotice } from '../../components/analytics/SharedReadOnlyNotice';
import { EditConflictDialog } from '../../components/EditConflictDialog';
import { DetailPageTitle } from '../../components/PageTitleSync';
import { PendingButton } from '../../components/PendingButton';
import { RequiredMark } from '../../components/RequiredMark';
import { ResourceNotFound } from '../../components/ResourceNotFound';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu';
import { ExportBlockedTooltip } from '../../components/ui/ExportBlockedTooltip';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Switch } from '../../components/ui/switch';
import {
  CHART_LIST_KEY,
  DASHBOARD_LIST_KEY,
  dashboardDetailQueryOptions,
  seedDashboardInListCache,
  useAddWidget,
  useAllDashboards,
  useAllSavedQueries,
  useChart,
  useCreateChart,
  useCreateDashboard,
  useExecuteSavedQuery,
  useUpdateChart,
} from '../../hooks/queries/useAnalytics';
import { useInvalidateListsOnEntryNotFound } from '../../hooks/queries/useInvalidateListsOnEntryNotFound';
import { useAuth } from '../../hooks/useAuth';
import { useIsOwner } from '../../hooks/useIsOwner';
import { useSubmitGuard } from '../../hooks/useSubmitGuard';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { extractApiError, handleApiError, isClientError, isDuplicateName, isEditConflict, isUserSwitchedError } from '../../lib/api-error';
import {
  exportChartAsPng,
  exportChartAsSvg,
  findChartSvg,
  sanitizeFilename,
} from '../../lib/chart-export';
import { MAX_WIDGET_POSITION_Y } from '../../lib/dashboard-grid-limits';
import { parseRouteId } from '../../lib/route-id';
import { hasVisibleText } from '../../lib/text-rules';
import { MAX_DESCRIPTION_LENGTH } from '../../lib/validations/description';
import type { Chart, ChartConfig,ChartType, Dashboard } from '../../types/analytics';

// ============================================================
// Auto chart type recommendation
// ============================================================

function isGeoJsonColumn(col: string, rows: Record<string, unknown>[]): boolean {
  return rows.slice(0, 5).some((r) => {
    const v = r[col];
    // 이미 파싱된 객체 (analytics API 응답)
    if (v && typeof v === 'object' && 'type' in v && 'coordinates' in v) return true;
    // JSON 문자열 (dataset data API 응답)
    if (typeof v !== 'string') return false;
    try {
      const parsed = JSON.parse(v);
      return parsed && typeof parsed === 'object' && 'type' in parsed && 'coordinates' in parsed;
    } catch {
      return false;
    }
  });
}

function recommendChartType(
  columns: string[],
  rows: Record<string, unknown>[]
): ChartType {
  if (columns.length < 2 || rows.length === 0) return 'TABLE';

  // GeoJSON 감지 → MAP 추천
  const geoCols = columns.filter((col) => isGeoJsonColumn(col, rows));
  if (geoCols.length > 0) return 'MAP';

  const isNumeric = (col: string): boolean => isNumericColumn(col, rows);

  const isDate = (col: string): boolean => {
    const sample = rows.slice(0, 10).map((r) => r[col]);
    return sample.some((v) => {
      if (typeof v !== 'string') return false;
      return /\d{4}-\d{2}-\d{2}/.test(v);
    });
  };

  const numericCols = columns.filter(isNumeric);
  const dateCols = columns.filter(isDate);
  const categoryCols = columns.filter((c) => !isNumeric(c) && !isDate(c));

  // CANDLESTICK: open/high/low/close 컬럼 존재 여부로 감지
  const lowerCols = columns.map((c) => c.toLowerCase());
  const hasCandlestickCols = ['open', 'high', 'low', 'close'].some((k) => lowerCols.includes(k));
  if (hasCandlestickCols) return 'CANDLESTICK';

  // BOXPLOT: 통계 컬럼(q1/q3/median/min/max) 존재 여부로 감지
  const hasBoxplotCols = ['q1', 'q3', 'median', 'min', 'max'].some((k) => lowerCols.includes(k));
  if (hasBoxplotCols) return 'BOXPLOT';

  // HEATMAP: 카테고리 2개 + 수치 1개 정확히 일치
  if (categoryCols.length === 2 && numericCols.length === 1) return 'HEATMAP';

  // HISTOGRAM: 수치 컬럼 1개만 있는 경우 — 단일 값 분포
  if (numericCols.length === 1 && categoryCols.length === 0 && dateCols.length === 0) return 'HISTOGRAM';

  // WATERFALL: 카테고리 1개 + 수치 1개이며 수치에 양수/음수 모두 존재
  if (categoryCols.length === 1 && numericCols.length === 1) {
    // WD-843: 부호 판정도 차트와 같은 toPlotNumber 규칙으로 읽는다(불리언·공백은 숫자가 아니다)
    if (hasMixedSignValues(numericCols[0], rows)) return 'WATERFALL';
  }

  if (dateCols.length > 0 && numericCols.length > 0) return 'LINE';
  if (numericCols.length >= 2) return 'SCATTER';
  if (categoryCols.length > 0 && numericCols.length > 0) {
    const uniqueVals = new Set(rows.map((r) => r[categoryCols[0]])).size;
    return uniqueVals <= 5 ? 'PIE' : 'BAR';
  }
  return 'BAR';
}

/** X축도 숫자 축인 차트 타입 — 타입을 바꿀 때 문자 X축을 숫자 컬럼으로 바꾼다(WD-308) */
const NUMERIC_X_AXIS_TYPES: ReadonlySet<ChartType> = new Set<ChartType>(['SCATTER', 'HISTOGRAM']);

// 「대시보드로 가기」 토스트 고정 id — 다시 띄우면 대체되고 언마운트·차트 변경 때 이 id 로 닫는다(WD-1148)
const GUARD_CANCEL_TOAST_ID = 'chart-added-to-dashboard';

function buildDefaultConfig(
  columns: string[],
  rows: Record<string, unknown>[],
  chartType: ChartType
): ChartConfig {
  // MAP 차트: 공간 컬럼 자동 선택
  if (chartType === 'MAP') {
    const spatialCol = columns.find((col) => isGeoJsonColumn(col, rows)) ?? columns[0];
    return { xAxis: '', yAxis: [], spatialColumn: spatialCol };
  }

  const isNumeric = (col: string): boolean => isNumericColumn(col, rows);

  const numericCols = columns.filter(isNumeric);
  const nonNumericCols = columns.filter((c) => !isNumeric(c));
  const categoryCols = nonNumericCols; // 날짜 포함 비수치 컬럼

  // CANDLESTICK: 시가/고가/저가/종가 컬럼을 자동 매핑
  if (chartType === 'CANDLESTICK') {
    const lowerMap = Object.fromEntries(columns.map((c) => [c.toLowerCase(), c]));
    return {
      xAxis: nonNumericCols[0] ?? columns[0] ?? '',
      yAxis: [],
      open: lowerMap['open'],
      high: lowerMap['high'],
      low: lowerMap['low'],
      close: lowerMap['close'],
    };
  }

  // BOXPLOT: xAxis만 설정, 나머지 컬럼은 컴포넌트가 직접 읽음
  if (chartType === 'BOXPLOT') {
    return { xAxis: nonNumericCols[0] ?? columns[0] ?? '', yAxis: [] };
  }

  // HEATMAP: x=카테고리[0], y=카테고리[1], valueColumn=수치[0]
  if (chartType === 'HEATMAP') {
    return {
      xAxis: categoryCols[0] ?? columns[0] ?? '',
      yAxis: categoryCols[1] ? [categoryCols[1]] : [],
      valueColumn: numericCols[0],
    };
  }

  // SCATTER: 두 축 모두 숫자 축 — x=첫 수치 컬럼, y=나머지 수치 컬럼(최대 3) (WD-308).
  // 숫자 컬럼이 없으면 첫 컬럼을 두고 미리보기가 「숫자 컬럼이어야 합니다」로 안내한다.
  if (chartType === 'SCATTER') {
    return { xAxis: numericCols[0] ?? columns[0] ?? '', yAxis: numericCols.slice(1, 4) };
  }

  // HISTOGRAM: x=수치 컬럼, yAxis 없음
  if (chartType === 'HISTOGRAM') {
    return { xAxis: numericCols[0] ?? columns[0] ?? '', yAxis: [] };
  }

  // WATERFALL: x=카테고리, y=수치 1개
  if (chartType === 'WATERFALL') {
    return {
      xAxis: nonNumericCols[0] ?? columns[0] ?? '',
      yAxis: numericCols[0] ? [numericCols[0]] : [],
    };
  }

  const xAxis = nonNumericCols[0] ?? columns[0] ?? '';
  const yAxis =
    chartType === 'PIE' || chartType === 'DONUT'
      ? numericCols.slice(0, 1)
      : numericCols.slice(0, 3);

  return {
    xAxis,
    yAxis,
    showLegend: true,
    showGrid: true,
    stacked: false,
  };
}

// ============================================================
// Save Dialog
// ============================================================

interface SaveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  name: string;
  onNameChange: (v: string) => void;
  description: string;
  onDescriptionChange: (v: string) => void;
  isShared: boolean;
  onIsSharedChange: (v: boolean) => void;
  onSave: () => void;
  isSaving: boolean;
  isEdit: boolean;
  /** 설정이 가려진 차트(WD-31②) — 이름·설명·공유만 바뀐다는 것을 보이는 설명으로 알린다. */
  configWithheld?: boolean;
  /** 이름 중복 409(WD-824)의 서버 문구 — 이름 칸 아래에 보인다 */
  nameError?: string | null;
}

function SaveDialog({
  open,
  onOpenChange,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  isShared,
  onIsSharedChange,
  onSave,
  isSaving,
  isEdit,
  configWithheld = false,
  nameError,
}: SaveDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isEdit ? '차트 수정' : '차트 저장'}</DialogTitle>
          {configWithheld ? (
            // 잠금 상태에서는 설정이 저장되지 않는다는 점을 눈에 보이게 알린다(WD-31②)
            <DialogDescription>차트 설정은 잠겨 있어 이름·설명·공유만 바꿀 수 있습니다.</DialogDescription>
          ) : (
            <DialogDescription className="sr-only">
              {isEdit ? '차트 설정을 수정하여 저장합니다.' : '차트 이름과 설정을 입력하여 저장합니다.'}
            </DialogDescription>
          )}
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            {/* WD-964: 별표는 aria-hidden, 필수는 aria-required 로 알린다(native required 는 쓰지 않음) */}
            <Label htmlFor="chart-name">
              <span>
                이름 <RequiredMark />
              </span>
            </Label>
            <Input
              id="chart-name"
              aria-required="true"
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="차트 이름을 입력하세요"
              maxLength={200}
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameError ? 'chart-name-error' : undefined}
            />
            {nameError && (
              <p id="chart-name-error" role="alert" className="text-sm text-destructive">
                {nameError}
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="chart-description">설명</Label>
            <Input
              id="chart-description"
              value={description}
              onChange={(e) => onDescriptionChange(e.target.value)}
              placeholder="선택사항"
              maxLength={MAX_DESCRIPTION_LENGTH}
            />
          </div>
          <div className="flex items-center justify-between">
            <Label htmlFor="chart-shared" className="cursor-pointer">
              공유 차트
            </Label>
            <Switch
              id="chart-shared"
              checked={isShared}
              onCheckedChange={onIsSharedChange}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            취소
          </Button>
          <Button onClick={onSave} disabled={!hasVisibleText(name) || isSaving}>
            {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
            {isEdit ? '저장' : '만들기'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================================
// Add to Dashboard Dialog (#97)
// — 차트 → 대시보드 워크플로우 단축. 사용자가 차트 편집 페이지에서 즉시
//   대시보드를 선택해 위젯으로 추가할 수 있게 함. 백엔드는 기존
//   POST /api/v1/dashboards/{id}/widgets 엔드포인트 활용.
// ============================================================

interface AddToDashboardDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  chartId: number;
  chartType: ChartType;
  // 미저장 변경 가드를 지나게 하려고 부모의 requestNavigate 를 받는다 — 직접 navigate 하면 가드를 우회한다(WD-1106 ②)
  onNavigate: (to: string) => void;
}

// 이름 내보내기 — 이 컴포넌트를 렌더링하는 단위 시험이 쓴다(WD-1106 ⑤)
export function AddToDashboardDialog({
  open,
  onOpenChange,
  chartId,
  chartType,
  onNavigate,
}: AddToDashboardDialogProps) {
  const [selectedDashboardId, setSelectedDashboardId] = useState<number | null>(null);
  // 첫 페이지(100개)만 쓰면 101번째 이후 대시보드가 조용히 빠지므로 전 페이지를 모은다 (#737)
  const { data: allDashboards, isLoading, isError, refetch, isRefetching } = useAllDashboards();
  // 위젯은 대시보드 작성자만 넣을 수 있다 — 목록은 남이 공유한 대시보드도 주므로(보기용) 내 대시보드만 고르게 거른다(WD-196).
  // 거르지 않으면 남의 대시보드를 골랐을 때 서버가 404 로 거절해 「대시보드를 찾을 수 없습니다」가 뜬다.
  const isOwner = useIsOwner();
  const dashboards = (allDashboards ?? []).filter((d) => isOwner(d.createdBy));
  // 인증 로딩이 끝나기 전에는 useIsOwner 가 모두 false 라 내 대시보드가 있어도 빈 목록처럼 보인다 —
  // 그래서 빈 상태(만들기 입력) 판정에서는 로딩으로 취급한다(WD-1076). 로딩 기준은 user == null 이 아니라 인증
  // isLoading 이다 — 사용자 조회가 실패해 user 가 계속 null 이면 로딩은 끝나지만 빈 목록으로 보내지 않고
  // 아래 isUserMissing 안내를 보인다(WD-1106 ⑤ 를 WD-1148 에서 바꿈).
  const { isLoading: isAuthLoading, user } = useAuth();
  // 인증 로딩이 끝났는데 user 가 없으면(조회 실패) useIsOwner 가 전부 false 라 내 대시보드가 있어도
  // 빈 목록처럼 보인다 — 빈 상태(만들기)로 보내면 이미 있는 이름으로 만들다 막히므로 안내만 보인다(WD-1148)
  const isUserMissing = !isAuthLoading && user == null;

  // 빈 상태(WD-1076)에서 새 대시보드 이름과 중복 409 서버 문구 — 이름을 고치면 문구가 사라진다
  const [newName, setNewName] = useState('');
  const [nameError, setNameError] = useState<string | null>(null);

  // 방금 만든 대시보드 id — #737 오류 안내 예외를 이 대시보드로만 좁히는 데 쓴다(선택된 아무 항목이 아니라)
  const [createdDashboardId, setCreatedDashboardId] = useState<number | null>(null);

  // 다이얼로그를 열 때마다 선택 초기화 — state-based tracking으로 cascading render 방지
  const [openTracker, setOpenTracker] = useState(false);
  if (open !== openTracker) {
    setOpenTracker(open);
    if (open) {
      setSelectedDashboardId(null);
      setNewName('');
      setNameError(null);
      // 지난 번에 만든 대시보드 id 가 다시 열 때 남아 #737 오류 안내 예외가 엉뚱한 항목에 적용되지 않게 비운다
      setCreatedDashboardId(null);
    }
  }

  // 대상 대시보드 id 는 두 경로 모두 mutate 변수로 넘긴다 — 훅이 선택 상태를 따라가면 진행 중 선택 변경이 대상을 바꾼다
  const addWidget = useAddWidget();
  const createDashboard = useCreateDashboard();
  const queryClient = useQueryClient();
  // 같은 틱의 연속 클릭·Enter 연타로 대시보드가 둘 만들어지는 것을 동기 잠금으로 막는다
  const guardSubmit = useSubmitGuard();
  // (WD-1106 ①) 떠난 뒤 이동 금지 — 요청 도중 사용자가 다른 화면으로 떠나 이 컴포넌트가 언마운트되면,
  // 늦게 온 성공 응답이 이동을 다시 불러 사용자를 대시보드로 끌고 간다. 성공 직후 이 ref 로 떠남을 가른다.
  // setup 에서 true 로 되살리는 건 StrictMode(개발) 이 마운트→정리→재마운트를 한 번 더 도는 동작 때문 —
  // 정리만 남으면 재마운트 뒤에도 false 로 굳어 이동이 영영 안 일어난다.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);
  // (WD-1148) 같은 ChartBuilderPage 가 :id 만 바뀐 채 살아 있으면(라우트에 key 없음) 언마운트로는 떠남을 못 가린다 —
  // 늦게 온 성공 응답이 차트 B 를 보는 사용자를 대시보드로 끌고 가므로, 최신 chartId 를 ref 로 들고 시작 차트와 비교한다.
  // 렌더 중 ref 대입은 react-hooks/refs 규칙이 막고, 렌더가 버려져도 값이 앞서 바뀌지 않도록 커밋 직후(layout effect)에 갱신한다
  const chartIdRef = useRef(chartId);
  useLayoutEffect(() => {
    chartIdRef.current = chartId;
  }, [chartId]);

  // 만들고 추가 진행 중에는 목록 재조회로 새 대시보드가 보여도 빈 상태 화면을 유지한다 —
  // 목록 화면으로 바뀌면 사용자가 새 대시보드를 골라 차트가 두 번 들어간다. 조회 실패는 「없음」이 아니다(#737).
  const [isCreating, setIsCreating] = useState(false);
  const isListLoading = isLoading || isAuthLoading;
  // 조회 실패 안내를 보이는가 — 다만 방금 만든 대시보드가 목록에 있고 선택된 동안에는 오류 화면이 덮지 않는다
  // (WD-1106 ③, WD-1148 에서 방금 만든 것으로 좁힘). 안내 표시와 「추가」 비활성이 같은 조건을 쓰도록 한 곳에서 계산한다
  const isListErrorShown =
    isError &&
    !(
      createdDashboardId !== null &&
      selectedDashboardId === createdDashboardId &&
      dashboards.some((d) => d.id === createdDashboardId)
    );
  // 목록을 믿을 수 없는 상태(사용자 조회 실패·목록 오류) — 「추가」를 막는다
  const isListBlocked = isUserMissing || isListErrorShown;
  const isEmptyState =
    isCreating || (!isListLoading && !isError && !isUserMissing && dashboards.length === 0);
  // 목록 오류 안내를 그리는가 — 만드는 중(POST 진행)에는 백그라운드 재조회가 실패해도 만들기 폼을 안내로 바꾸지 않는다.
  // 「추가」 비활성(isListBlocked)과 같은 자리에서 계산해 두 조건이 어긋나지 않게 한다
  const showListError = isListErrorShown && !isCreating;

  // 위젯이 들어간 뒤의 처리(토스트·닫기·이동)를 한 곳에서 한다. 예외는 기록만 한다 —
  // 위젯은 이미 들어갔으므로 실패 토스트를 띄우거나 미처리 거부로 두면 사용자가 다시 눌러 위젯이 중복된다(WD-1148).
  // 이동은 떠나 있는 동안(언마운트)·다른 차트로 바뀐 뒤에는 하지 않고(WD-1106 ①), 미저장 변경이 있으면 가드 확인을 지난다(②)
  const finishAdded = (message: string, dashboardId: number, startChartId: number) => {
    try {
      toast.success(message);
      onOpenChange(false);
      if (mountedRef.current && chartIdRef.current === startChartId) {
        onNavigate(`/analytics/dashboards/${dashboardId}`);
      }
    } catch (postError) {
      console.error('대시보드 추가 후 처리 실패', postError);
    }
  };

  // 위젯 위치·크기: MAP 은 전체 폭·큰 높이 (DashboardEditorPage 와 동일 규칙)
  // positionY 는 호출부가 대상 대시보드의 기존 위젯 아래로 계산해 넘긴다 (서버 상한 240 이하여야 한다)
  const buildWidgetRequest = (positionY: number) => {
    const isMapChart = chartType === 'MAP';
    return {
      chartId,
      positionX: 0,
      positionY,
      width: isMapChart ? 12 : 6,
      height: isMapChart ? 6 : 4,
    };
  };

  // 내 대시보드가 없을 때(WD-1076) 이름만으로 대시보드를 만들고 차트를 한 번에 넣는다.
  const handleCreateAndAdd = guardSubmit(async () => {
    const name = newName.trim();
    if (!hasVisibleText(name)) return;
    // 누른 순간의 차트 — 응답 뒤 이동은 아직 이 차트를 보고 있을 때만 한다(WD-1148)
    const startChartId = chartId;
    setNameError(null);
    setIsCreating(true);
    try {
      let created: Dashboard;
      try {
        // 설명·자동 새로고침은 보내지 않는다 — 나중에 대시보드에서 고친다
        created = await createDashboard.mutateAsync({ name, isShared: false });
      } catch (error) {
        // 이름 중복(409)은 서버 문구를 입력 아래에 보이고 토스트는 생략한다
        if (isDuplicateName(error)) {
          setNameError(extractApiError(error, '이미 있는 대시보드 이름입니다. 다른 이름을 입력하세요.'));
          return;
        }
        handleApiError(error, '대시보드 생성에 실패했습니다.');
        return;
      }
      // 만든 결과를 목록 캐시에 씨앗으로 넣는다 — 재조회 성공에 기대지 않도록,
      // 재조회가 실패해도 목록에 항목과 선택이 남아 재시도할 수 있다(WD-1106 ③).
      // 만들기 try 밖에 둔다: 씨앗 실패가 "생성 실패"로 오인되면 이미 만든 대시보드를 두고 다시 만들게 된다.
      // 씨앗은 보조 수단이므로 실패는 무시한다(목록은 재조회로 채워진다).
      setCreatedDashboardId(created.id);
      try {
        seedDashboardInListCache(queryClient, created);
      } catch {
        // 무시 — 위 주석 참고
      }
      // try 는 위젯 추가 요청만 감싼다 — 성공 뒤 토스트·닫기·이동에서 난 예외가 「추가 실패」로 오인되면
      // 이미 들어간 위젯을 다시 넣게 된다(WD-1148)
      try {
        await addWidget.mutateAsync({ dashboardId: created.id, ...buildWidgetRequest(0) }); // 새 대시보드는 비어 있어 맨 위(0)
      } catch (error) {
        // 다른 사용자로 바뀐 오류는 화면이 다시 열리므로 토스트·선택 없이 끝낸다.
        if (isUserSwitchedError(error)) return;
        // 만들기는 성공했으므로 대화상자를 유지하고 새 대시보드를 미리 선택해 바로 「추가」를 다시 누를 수 있게 한다
        setSelectedDashboardId(created.id);
        // 방금 만든 뒤의 부분 실패라 서버 사유를 그대로 보인다(403 권한 없음 등 — 일반 문구로 덮지 않는다).
        // 「추가」를 다시 눌러 나을 수 있는 경우(응답 없음=네트워크, 5xx)에만 재시도 안내를 붙인다 —
        // 4xx 는 다시 눌러도 같은 결과라 안내가 거짓이 된다
        // (네트워크·5xx·408/429 는 재시도 가능, 그 밖의 4xx 는 isClientError)
        const noResponse = axios.isAxiosError(error) && !error.response;
        const retryable = axios.isAxiosError(error) && !isClientError(error);
        // 응답이 없으면(네트워크) 서버 사유가 없으므로 「네트워크 오류」로 알린다. 사유 뒤에는 마침표로 안내와 구분한다
        const rawReason = extractApiError(
          error,
          noResponse ? '네트워크 오류' : '알 수 없는 오류',
        );
        // 뒤에 마침표로 안내를 붙일 때만, 서버 사유가 이미 마침표로 끝나면 '..' 이 되므로 끝의 마침표 하나를 걷어 낸다
        // (안내를 붙이지 않으면 서버 문구를 그대로 둔다)
        const reason = retryable ? rawReason.replace(/[.。]$/, '') : rawReason;
        toast.error(
          `대시보드를 만들었지만 차트를 추가하지 못했습니다: ${reason}` +
            (retryable ? '. 「추가」를 다시 누르세요' : ''),
        );
        // 씨앗이 이미 목록에 새 대시보드를 두므로 빈 입력이 다시 뜨지는 않는다. 재조회(위젯 수 등 서버 값 반영)를
        // 끝낸 뒤에 만들기 상태를 풀어, 재시도 때 오래된 목록 값으로 판단하지 않게 한다
        await queryClient.invalidateQueries({ queryKey: DASHBOARD_LIST_KEY });
        return;
      }
      finishAdded(`'${name}' 대시보드를 만들고 차트를 추가했습니다.`, created.id, startChartId);
    } finally {
      setIsCreating(false);
    }
  });

  // 「추가」 진행 중(상세 조회 + 위젯 추가) 여부 — 이 동안 「추가」·목록 선택·닫기를 막는다(WD-1076 반려)
  const [isAdding, setIsAdding] = useState(false);
  // 만들고 추가·추가 어느 쪽이든 진행 중이면 닫기·취소를 막는다
  const isBusy = isCreating || isAdding;

  // 같은 잠금(guardSubmit)으로 감싸 같은 틱의 연속 클릭도 한 번만 추가한다
  const handleAdd = guardSubmit(async () => {
    // 누른 순간의 대상을 고정한다 — 상세 조회를 기다리는 동안 선택이 바뀌어도 위젯·토스트·이동이 같은 대시보드를 향하게
    const targetId = selectedDashboardId;
    if (!targetId) return;
    // 누른 순간의 차트 — 응답 뒤 이동은 아직 이 차트를 보고 있을 때만 한다(WD-1148)
    const startChartId = chartId;
    const targetName = dashboards.find((d) => d.id === targetId)?.name ?? '대시보드';
    setIsAdding(true);
    // 위젯이 실제로 들어갔는가 — 성공 뒤 토스트·닫기·이동은 try 밖에서 한다.
    // 안에서 하면 거기서 난 예외가 「추가 실패」로 알려져 사용자가 다시 눌러 위젯이 중복된다(WD-1148)
    let added = false;
    try {
      // 대상 대시보드의 기존 위젯 맨 아래(y+h 최댓값)에 넣는다. 상세 캐시를 공유하고, 오래된 값이면 다시 읽는다 —
      // 쿼리 옵션은 useDashboard 와 같은 한 곳에서 쓴다(키·조회 함수가 어긋나면 캐시를 못 나눠 읽는다)
      const detail = await queryClient.fetchQuery({
        ...dashboardDetailQueryOptions(targetId),
        staleTime: 0,
      });
      const bottom = detail.widgets.reduce((acc, w) => Math.max(acc, w.positionY + w.height), 0);
      // 아래 끝이 상한을 넘는 자리에 넣으면 기존 위젯과 겹친다 — 그래서 자리가 없으면 넣지 않는다(WD-1106 ④)
      if (bottom > MAX_WIDGET_POSITION_Y) {
        toast.error(`이 대시보드는 위젯을 둘 자리가 없습니다(마지막 행 ${bottom}, 상한 ${MAX_WIDGET_POSITION_Y}). 대시보드에서 위젯을 옮긴 뒤 다시 시도하세요.`);
        return;
      }
      await addWidget.mutateAsync({ dashboardId: targetId, ...buildWidgetRequest(bottom) });
      added = true;
    } catch (error) {
      // 상세 조회·위젯 추가 실패
      handleApiError(error, '대시보드에 차트 추가 실패');
    } finally {
      setIsAdding(false);
    }
    if (!added) return;
    // isAdding 이 풀린 뒤(finally 다음)에 처리한다 — 성공 뒤 예외가 「추가 실패」로 오인되지 않게 try 밖
    finishAdded(`'${targetName}'에 차트가 추가되었습니다.`, targetId, startChartId);
  });

  // 진행 중에는 닫지 못하게 한다 — 닫아도 요청은 계속돼 뒤늦게 이동·토스트가 나온다
  const handleOpenChange = (next: boolean) => {
    if (!next && isBusy) return;
    onOpenChange(next);
  };

  // form 으로 감싸 빈 상태의 이름 입력에서 Enter 로도 제출된다
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (isEmptyState) void handleCreateAndAdd();
  };

  // 목록 조회 오류 안내 — 「다시 시도」로 재조회한다. 방금 만든 대시보드를 고른 동안은 이 안내가 뜨지 않는다(isListErrorShown)
  const listError = (
    <div role="alert" className="flex items-center justify-between gap-2 text-sm text-destructive">
      <span>대시보드 목록을 불러오지 못했습니다.</span>
      {/* disabled 로 잠그면 누른 버튼의 포커스가 body 로 떨어지므로 aria-disabled 로 잠근다(WD-637·678) */}
      <PendingButton type="button" variant="outline" size="sm" pending={isRefetching} onClick={() => void refetch()}>
        다시 시도
      </PendingButton>
    </div>
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>대시보드에 추가</DialogTitle>
          <DialogDescription>
            {isEmptyState
              ? '새 대시보드를 만들어 이 차트를 넣으세요.'
              : '이 차트를 추가할 대시보드를 선택하세요.'}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit}>
          <div className="space-y-2 py-2 max-h-80 overflow-y-auto">
            {isListLoading && !isCreating ? (
              <p className="text-sm text-muted-foreground">대시보드 목록을 불러오는 중...</p>
            ) : isUserMissing ? (
              // 사용자 정보를 못 받아 내 대시보드를 가를 수 없다 — 목록·빈 상태·만들기를 모두 숨기고 안내만 보인다(WD-1148)
              <p role="alert" className="text-sm text-destructive">
                사용자 정보를 확인하지 못해 대시보드 목록을 보일 수 없습니다. 페이지를 새로 고친 뒤 다시 시도하세요.
              </p>
            ) : isEmptyState ? (
              <div className="space-y-1.5">
                <p className="text-sm text-muted-foreground">
                  아직 대시보드가 없습니다.
                </p>
                <Label htmlFor="add-dash-new-name">새 대시보드 이름</Label>
                <Input
                  id="add-dash-new-name"
                  value={newName}
                  onChange={(e) => {
                    setNewName(e.target.value);
                    setNameError(null);
                  }}
                  maxLength={200}
                  aria-invalid={nameError ? true : undefined}
                  aria-describedby={nameError ? 'add-dash-name-error' : undefined}
                />
                {nameError && (
                  <p id="add-dash-name-error" role="alert" className="text-sm text-destructive">
                    {nameError}
                  </p>
                )}
              </div>
            ) : (
              <>
                {/* 조회 실패를 "대시보드 없음"으로 위장하지 않는다(#737) — 캐시 목록이 없으면 안내만(목록이 비어 아래는 그려지지 않음),
                    있으면 목록은 그대로 두고 안내를 위에 덧붙인다(「추가」는 막힘) */}
                {showListError && listError}
                {dashboards.map((d) => (
                  <button
                    key={d.id}
                    type="button"
                    onClick={() => setSelectedDashboardId(d.id)}
                    disabled={isAdding}
                    aria-pressed={selectedDashboardId === d.id}
                    className={`w-full text-left px-3 py-2 rounded border transition-colors ${
                      selectedDashboardId === d.id
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:bg-muted'
                    }`}
                  >
                    <div className="font-medium text-sm">{d.name}</div>
                    {d.description && (
                      // 말줄임된 설명을 마우스 올리면 전체로 볼 수 있게 title 부여(WD-1003)
                      <div className="text-xs text-muted-foreground mt-0.5 truncate" title={d.description}>
                        {d.description}
                      </div>
                    )}
                  </button>
                ))}
              </>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={isBusy} onClick={() => onOpenChange(false)}>
              취소
            </Button>
            {isEmptyState ? (
              <Button type="submit" disabled={!hasVisibleText(newName) || isCreating}>
                {isCreating && <Loader2 className="h-4 w-4 animate-spin" />}
                만들고 추가
              </Button>
            ) : (
              <Button
                type="button"
                onClick={() => void handleAdd()}
                // 목록 조회 오류 안내가 떠 있는 동안은 확인되지 않은 선택에 추가하지 않게 막는다 —
                // 선택은 유지해 「다시 시도」가 성공하면 그대로 이어진다(방금 만든 대시보드를 고른 동안은 오류 안내도 안 뜬다)
                disabled={!selectedDashboardId || isAdding || isListBlocked}
              >
                {isAdding && <Loader2 className="h-4 w-4 animate-spin" />}
                추가
              </Button>
            )}
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ============================================================
// ChartBuilderPage
// ============================================================

const NO_QUERY = '__none__';

export default function ChartBuilderPage() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();

  // 주소 id 는 숫자만 받는다(WD-434) — parseInt 는 'abc' 를 NaN(새 차트 + 「NaN」 글자로), '15abc' 를 15(다른 차트)로 읽었다
  const chartId = parseRouteId(id);
  const isInvalidId = chartId === null;
  const isNew = id === undefined;

  // 접근성: 라벨↔컨트롤 연결용 id 접두사 (#432). 하드코딩 대신 useId 로 충돌을 원천 차단한다.
  const baseId = useId();

  // Pre-selected query from URL (?queryId=123)
  const initialQueryId = searchParams.get('queryId')
    ? parseInt(searchParams.get('queryId')!, 10)
    : null;

  // Form state
  const [selectedQueryId, setSelectedQueryId] = useState<number | null>(initialQueryId);
  const [chartType, setChartType] = useState<ChartType>('BAR');
  // WD-832: 미리보기 「표로 보기」 토글 — 차트 종류를 바꿔도 유지한다(표·지도 종류에서는 버튼이 없고 렌더러도 view 를 무시한다)
  const [tableView, setTableView] = useState(false);
  const [config, setConfig] = useState<ChartConfig>({
    xAxis: '',
    yAxis: [],
    showLegend: true,
    showGrid: true,
    stacked: false,
  });
  const [queryColumns, setQueryColumns] = useState<string[]>([]);
  const [queryRows, setQueryRows] = useState<Record<string, unknown>[]>([]);
  // 숫자 컬럼 — 히트맵 가중치 목록에 숫자 컬럼만 보이게 한다(WD-354). 문자 컬럼을 고르면 히트맵이 사라졌다.
  const numericQueryColumns = useMemo(
    () => queryColumns.filter((col) => isWeightColumn(col, queryRows)),
    [queryColumns, queryRows],
  );
  // 결과가 행 상한(1000행)에 닿았는지(서버 truncated) — 받은 행이 전부인 것처럼 보이지 않게 안내한다(WD-320).
  // 서버는 상한을 넘는 결과가 실제로 있을 때만 truncated 로 표시한다(WD-454 — 정확히 1000행이거나 사용자 LIMIT 1000 이면 false).
  const [queryTruncated, setQueryTruncated] = useState(false);
  // 마지막 실행 결과의 조회자 기준 내보내기 가능(S4) — 차트 이미지도 원본 데이터를 담으므로 같은 정책을 따른다.
  // 서버가 true 를 준 경우만 허용(fail-closed).
  const [exportAllowed, setExportAllowed] = useState(false);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  // 이름 중복 409(WD-824)의 서버 문구 — 이름 칸 아래에 보이고 이름을 고치거나 다시 저장하면 사라진다
  const [nameError, setNameError] = useState<string | null>(null);
  // 대시보드 추가 다이얼로그 상태 (#97)
  const [addToDashboardOpen, setAddToDashboardOpen] = useState(false);
  const [saveForm, setSaveForm] = useState({
    name: '',
    description: '',
    isShared: false,
  });

  // Queries list for dropdown
  // 첫 페이지(100개)만 쓰면 101번째 이후 저장 쿼리가 빠지고, 그 쿼리를 쓰는 기존 차트의 쿼리 칸이 비므로 전 페이지를 모은다 (#737)
  // isError: 조회 실패를 빈 목록으로 위장하지 않고 드롭다운 아래에 오류로 드러낸다 (#738)
  const {
    data: allQueries,
    isLoading: queriesLoading,
    isError: queriesError,
  } = useAllSavedQueries();
  const queries = allQueries ?? [];

  // Existing chart (edit mode) — isError: 존재하지 않는 차트 ID(404 등) 접근 시 에러 감지
  const {
    data: existingChart,
    isLoading: chartLoading,
    isError: chartError,
    isLoadingError: isChartLoadingError,
    error: chartFetchError,
  } = useChart(chartId);
  // 서버가 설정을 가린 차트(WD-31②) — 설정 패널·차트 타입·쿼리 실행을 잠그고, 저장 시 config·chartType 을 보내지 않는다(기존 값 유지).
  const configWithheld = existingChart?.configWithheld === true;
  // 저장(덮어쓰기)·이름 변경은 작성자만 — 서버가 남의 수정 요청을 404 로 거절한다(WD-196). 남이 공유한 차트는 저장 버튼 대신 안내를 둔다.
  // 설정을 바꿔 미리보기를 다시 그려 보는 것(읽기)은 그대로 허용하되, 저장할 수 없으니 「미저장 변경사항」·이탈 경고는 띄우지 않는다.
  const isOwner = useIsOwner();
  const canEdit = isNew || (existingChart != null && isOwner(existingChart.createdBy));

  const executeQuery = useExecuteSavedQuery();
  const createChart = useCreateChart();
  const updateChart = useUpdateChart();
  const queryClient = useQueryClient();
  useInvalidateListsOnEntryNotFound({ isLoadingError: isChartLoadingError, error: chartFetchError }, CHART_LIST_KEY);

  /**
   * 지금 화면 내용의 기준 버전 — 그 내용을 불러온(또는 저장한) 시점의 서버 updatedAt(WD-364 낙관적 잠금).
   * 저장할 때 expectedUpdatedAt 으로 보내 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다.
   * 화면 내용을 서버 값으로 채우는 곳(applyChart)에서만 함께 바꾼다 — 내용과 기준이 어긋나면 다른 탭의 저장을 조용히 덮어쓴다.
   * 서버 문자열 그대로 둔다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다).
   */
  const [baseUpdatedAt, setBaseUpdatedAt] = useState<string | null>(null);
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 띄운다
  const [editConflict, setEditConflict] = useState(false);
  // 충돌 창의 「다시 불러오기」 진행 중 — 저장 중과 함께 창의 버튼을 막는다
  const [isReloading, setIsReloading] = useState(false);

  // 사용자 상호작용 후 변경 여부 추적
  // - 초기 로드(existingChart로부터 setState)는 변경으로 간주하지 않기 위해 핸들러에서 명시적으로 markDirty()를 호출
  const [isDirty, setIsDirty] = useState(false);
  // 저장할 수 없는 사용자(남의 공유 차트)는 변경을 추적하지 않는다 — 저장 못 할 「미저장 변경사항」 경고가 뜨지 않게(WD-196)
  const markDirty = useCallback(() => {
    if (canEdit) setIsDirty(true);
  }, [canEdit]);

  // 미저장 변경 이탈 확인(WD-323) — 사이드바 등 앱 안 링크·브라우저 뒤로·새로고침·탭 닫기를 함께 막는다.
  // 전에는 화면 안 「뒤로」 버튼과 beforeunload 만 막아, 사이드바로 나가면 확인 없이 변경이 사라졌다.
  // 「대시보드에 추가」가 끝난 뒤 가려던 대시보드 경로 — 가드 확인 창에서 「취소」하면(onStay) 추가는 이미 끝났는데
  // 화면만 그대로라 사용자가 결과를 못 본다. 그래서 그 경우에만 「대시보드로 가기」 토스트로 길을 다시 열어 준다.
  // 대시보드 이동이 아닌 가드 취소(뒤로가기 등)에는 경로가 없어 토스트를 띄우지 않는다. 한 번 쓰면 비운다.
  const pendingDashboardPathRef = useRef<string | null>(null);
  // 최신 isDirty — 대화상자는 클릭 때 받은 onNavigate 를 응답(await) 뒤에 부르므로, 클로저의 낡은 isDirty 가 아니라
  // 그 시점의 값으로 가드가 가로챌지 판정해야 한다(가드 훅도 ref 로 최신 값을 본다). 커밋 직후 갱신해 버려진 렌더가 앞서 바꾸지 않게 한다
  const isDirtyRef = useRef(false);
  useLayoutEffect(() => {
    isDirtyRef.current = isDirty;
  }, [isDirty]);
  // 아래 requestNavigate 는 훅 반환값이라 onStay 보다 늦게 선언된다 — 토스트 클릭 시점에 최신 래퍼를 부르도록 ref 로 잇는다
  const requestDashboardNavigateRef = useRef<(to: string) => void>(() => {});
  // 「대시보드로 가기」 토스트는 고정 id 를 쓴다 — 다시 띄우면 이전 것을 대체해 하나만 남는다.
  // 토스트는 전역 Toaster 에 있어 페이지를 떠나도 남고, 떠난 뒤 눌리면 이 페이지의 낡은 이동 함수가 불려
  // 다음 화면의 미저장 가드를 건너뛰므로 언마운트 때 닫는다(닫힌 토스트는 클릭될 수 없다)
  // 같은 페이지 인스턴스에서 차트 id 만 바뀔 때도(라우트에 key 없음) 이전 차트의 토스트·기억한 경로를 닫는다
  useEffect(() => {
    return () => {
      toast.dismiss(GUARD_CANCEL_TOAST_ID);
      pendingDashboardPathRef.current = null;
    };
  }, [chartId]);
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(isDirty, {
    onStay: () => {
      const path = pendingDashboardPathRef.current;
      pendingDashboardPathRef.current = null;
      if (!path) return;
      toast('대시보드에 추가했습니다.', {
        id: GUARD_CANCEL_TOAST_ID,
        action: {
          label: '대시보드로 가기',
          onClick: () => {
            requestDashboardNavigateRef.current(path);
          },
        },
      });
    },
  });
  // 대화상자의 이동 요청 — 경로를 기억한 뒤 가드를 지난다(가드가 꺼져 있으면 경로를 기억하지 않고 바로 이동한다)
  const requestDashboardNavigate = useCallback(
    (to: string) => {
      // 이동을 요청하는 지금의 dirty 로 판단한다(isDirtyRef) — 가드가 꺼져 있으면(미저장 변경 없음) 확인 창 없이 바로
      // 이동하므로 경로를 기억하지 않는다. 남으면 나중의 무관한 취소에 토스트가 뜬다.
      // 한계: isDirty 인데 가드가 가로채지 않은 경우(이미 이탈 확정 등)는 훅이 가로챔 여부를 알려 주지 않아 경로가 남을 수 있다.
      // 훅은 바꾸지 않으므로, 그 경로는 다음 onStay(가드 취소)에서만 쓰이고 한 번 쓰면 비워진다
      pendingDashboardPathRef.current = isDirtyRef.current ? to : null;
      requestNavigate(to);
    },
    [requestNavigate],
  );
  useLayoutEffect(() => {
    requestDashboardNavigateRef.current = requestDashboardNavigate;
  }, [requestDashboardNavigate]);

  // 차트 PNG/SVG 다운로드용 — 미리보기 컨테이너 ref. recharts/nivo SVG 추출에 사용 (이슈 #74).
  const previewContainerRef = useRef<HTMLDivElement | null>(null);

  /** 서버 차트로 화면 내용과 기준 버전을 함께 채운다 — 초기 로드·재조회·충돌 후 「다시 불러오기」가 같은 길을 쓴다 */
  const applyChart = useCallback((chart: Chart) => {
    setSelectedQueryId(chart.savedQueryId);
    setChartType(chart.chartType);
    // 가려진 차트는 config 가 null 이다 — 기본 config 를 그대로 둬 렌더러가 null 에서 깨지지 않게 한다(WD-31②).
    if (chart.config) setConfig(chart.config);
    setSaveForm({
      name: chart.name,
      description: chart.description ?? '',
      isShared: chart.isShared,
    });
    setBaseUpdatedAt(chart.updatedAt);
    // 서버 내용으로 채운 직후는 dirty가 아님
    setIsDirty(false);
  }, []);

  // Load existing chart into state
  useEffect(() => {
    if (existingChart) applyChart(existingChart);
  }, [existingChart, applyChart]);

  // 뒤로가기 버튼 — dirty 면 가드 확인 창, 아니면 즉시 이동
  const handleBackClick = () => requestNavigate('/analytics/charts');

  // Pre-fill save form name for new charts
  useEffect(() => {
    if (isNew && !saveForm.name) {
      setSaveForm((prev) => ({ ...prev, name: '새 차트' }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isNew]);

  // Execute query to get columns + rows
  const handleRunQuery = useCallback(async () => {
    if (!selectedQueryId) {
      toast.error('쿼리를 선택하세요.');
      return;
    }
    try {
      const result = await executeQuery.mutateAsync(selectedQueryId);
      if (result.error) {
        toast.error(`쿼리 오류: ${result.error}`);
        return;
      }
      setQueryColumns(result.columns);
      setQueryRows(result.rows);
      setQueryTruncated(result.truncated);
      setExportAllowed(result.exportAllowed === true);
      toast.success(
        `${result.truncated ? `상위 ${result.rows.length}행까지` : `${result.rows.length}행`} 로드됨 (${result.executionTimeMs}ms)`,
      );

      // Auto-recommend chart type and config only for new charts
      if (isNew) {
        const recommended = recommendChartType(result.columns, result.rows);
        setChartType(recommended);
        setConfig(buildDefaultConfig(result.columns, result.rows, recommended));
      }
    } catch (error) {
      handleApiError(error, '쿼리 실행에 실패했습니다.');
    }
  }, [selectedQueryId, executeQuery, isNew]);

  // When chartType changes, rebuild default config if we already have columns but config is empty
  // MAP ↔ 비MAP 전환 시 항상 config 리빌드 (렌더링 패러다임이 다름)
  const handleChartTypeChange = (type: ChartType) => {
    if (type !== chartType) markDirty();
    setChartType(type);
    const switchingToOrFromMap = type === 'MAP' || chartType === 'MAP';
    if (queryColumns.length > 0 && (!config.xAxis || switchingToOrFromMap)) {
      setConfig(buildDefaultConfig(queryColumns, queryRows, type));
      return;
    }
    // 산점도·히스토그램은 X축도 숫자 축이다 — 다른 타입에서 고른 문자 X축(예: city)이 남으면 아무것도 그려지지 않으므로,
    // 숫자 컬럼이 있으면 첫 숫자 컬럼으로 바꾼다(WD-308). 숫자 판단은 빈 값을 세지 않는 isWeightColumn 으로 — 드문드문 빈 측정값 컬럼을
    // 문자로 오인해 바꾸지 않게. 숫자 컬럼이 없으면 그대로 두고 미리보기가 안내한다.
    if (NUMERIC_X_AXIS_TYPES.has(type) && queryColumns.length > 0 && !isWeightColumn(config.xAxis, queryRows)) {
      const numericCols = queryColumns.filter((col) => isWeightColumn(col, queryRows));
      const numericX = numericCols[0];
      if (numericX) {
        // 산점도는 Y 도 숫자여야 한다 — X 로 옮긴 컬럼은 Y 에서 빼고, 그래서 Y 가 비면 다음 숫자 컬럼을 Y 로 둔다
        const restY = config.yAxis.filter((col) => col !== numericX);
        const yAxis =
          type === 'SCATTER' && restY.length === 0 ? numericCols.slice(1, 2) : restY;
        setConfig({ ...config, xAxis: numericX, yAxis });
      }
    }
  };

  // 축 설정 변경 — AxisConfigPanel에 전달
  const handleConfigChange = (next: ChartConfig) => {
    markDirty();
    setConfig(next);
  };

  // 쿼리 선택 변경
  const handleQueryChange = (value: string) => {
    const nextId = value === NO_QUERY ? null : parseInt(value, 10);
    if (nextId !== selectedQueryId) markDirty();
    setSelectedQueryId(nextId);
    setQueryColumns([]);
    setQueryRows([]);
    setQueryTruncated(false);
    setExportAllowed(false);
  };

  const handleSaveClick = () => {
    if (!canEdit) return; // 남의 차트는 저장 창을 열지 않는다(WD-196)
    if (existingChart) {
      setSaveForm({
        name: existingChart.name,
        description: existingChart.description ?? '',
        isShared: existingChart.isShared,
      });
    }
    setNameError(null);
    setSaveDialogOpen(true);
  };

  /**
   * 차트 미리보기를 SVG/PNG로 내보낸다 (이슈 #74).
   * - SVG: recharts/nivo가 렌더한 <svg>를 그대로 직렬화하여 다운로드 (벡터, 무손실).
   * - PNG: SVG를 Canvas에 그려 래스터 변환 (보고서 첨부용, 2배 해상도).
   * 쿼리 미실행 등으로 SVG가 없으면 토스트로 안내하고 종료.
   */
  const handleDownloadChart = async (format: 'svg' | 'png') => {
    const svg = findChartSvg(previewContainerRef.current);
    if (!svg) {
      toast.error('다운로드할 차트가 없습니다. 먼저 쿼리를 실행해 주세요.');
      return;
    }
    const baseName = sanitizeFilename(existingChart?.name ?? '차트');
    try {
      if (format === 'svg') {
        exportChartAsSvg(svg, baseName);
        toast.success('SVG 파일을 다운로드했습니다.');
      } else {
        await exportChartAsPng(svg, baseName);
        toast.success('PNG 파일을 다운로드했습니다.');
      }
    } catch (e) {
      toast.error(
        `차트 다운로드 실패: ${e instanceof Error ? e.message : '알 수 없는 오류'}`
      );
    }
  };

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const handleSave = async (force = false) => {
    if (!hasVisibleText(saveForm.name)) return;
    setNameError(null);
    if (!selectedQueryId) {
      toast.error('쿼리를 선택하세요.');
      return;
    }
    // 가려진 차트는 설정을 바꿀 수 없고 보내지도 않으므로 축 검증을 건너뛴다(WD-31②).
    if (!configWithheld) {
      // WD-88: 원본 쿼리의 컬럼 이름이 바뀌어 설정이 결과에 없는 컬럼을 가리키면, 미리보기에 안내만 뜬 채로
      // 깨진 설정이 저장될 수 있다. 쿼리를 실행해 결과 컬럼을 알 때만(queryColumns 가 있을 때) 저장을 막는다.
      // 타입별 검증보다 먼저 둔다 — 이름이 틀린 경우 "공간 데이터 없음" 같은 덜 정확한 안내보다 원인을 바로 알려 준다.
      const missingColumns = findMissingChartColumns(chartType, config, queryColumns, queryRows);
      if (missingColumns.length > 0) {
        toast.error(`쿼리 결과에 없는 컬럼이 있어 저장할 수 없습니다: ${missingColumns.join(', ')}`);
        return;
      }
      if (chartType === 'MAP') {
        if (!config.spatialColumn) {
          toast.error('공간 컬럼을 선택하세요.');
          return;
        }
        // 선택된 공간 컬럼이 실제 GeoJSON 데이터를 포함하는지 검증
        // — 저장 시점에 row 데이터를 기반으로 타입을 검사해 비공간 컬럼 저장을 차단한다
        if (!isGeoJsonColumn(config.spatialColumn, queryRows)) {
          toast.error('선택한 컬럼에 공간 데이터(GeoJSON)가 없습니다. GEOMETRY 타입 컬럼을 선택하세요.');
          return;
        }
      } else if (chartType === 'CANDLESTICK') {
        // 캔들스틱은 yAxis 대신 시가/고가/저가/종가 4개 컬럼 매핑이 필수 (#663).
        // 하나라도 비어 있으면 CandlestickChartView가 0으로 폴백해 flat 캔들이 그려진다.
        if (!config.xAxis) {
          toast.error('X축을 설정하세요.');
          return;
        }
        if (!config.open || !config.high || !config.low || !config.close) {
          toast.error('시가/고가/저가/종가 컬럼을 모두 선택하세요.');
          return;
        }
      } else if (chartType === 'HEATMAP') {
        // 히트맵은 행(xAxis)/열(yAxis[0])/값(valueColumn) 3개 컬럼이 모두 필요하고
        // 서로 겹치면 격자가 깨진다 (#664) — 저장 시점에 명시적으로 차단한다.
        const col = config.yAxis[0];
        if (!config.xAxis || !col || !config.valueColumn) {
          toast.error('행, 열, 값(색상 기준) 컬럼을 모두 선택하세요.');
          return;
        }
        if (config.xAxis === col || config.xAxis === config.valueColumn || col === config.valueColumn) {
          toast.error('행, 열, 값 컬럼은 서로 달라야 합니다.');
          return;
        }
      } else if (['BOXPLOT', 'HISTOGRAM'].includes(chartType)) {
        // 이 타입들은 yAxis 대신 전용 컬럼 설정 사용 — xAxis만 필수
        if (!config.xAxis) {
          toast.error('X축을 설정하세요.');
          return;
        }
        // WD-305: 박스 플롯은 통계 컬럼을 그대로 읽는다 — 없으면 0 으로 납작하게 그려진 차트가 저장되므로 막는다.
        // 쿼리를 실행해 결과 컬럼을 알 때만 검사한다(위 없는 컬럼 검사와 같은 기준).
        if (chartType === 'BOXPLOT' && queryColumns.length > 0) {
          const missingStats = findMissingBoxplotStats(queryColumns);
          if (missingStats.length > 0) {
            toast.error(
              `박스 플롯은 쿼리 결과에 ${BOXPLOT_REQUIRED_COLUMNS_LABEL} 컬럼이 있어야 저장할 수 있습니다. 없는 컬럼: ${missingStats.join(', ')}`,
            );
            return;
          }
        }
      } else {
        // WD-505: 게이지·값 하나 타입·계열 타입의 필수 축은 미리보기 안내와 같은 규칙·문구로 검사한다
        const axesMessage = findMissingChartAxesMessage(chartType, config);
        if (axesMessage) {
          toast.error(axesMessage);
          return;
        }
      }
    }

    try {
      if (isNew) {
        const created = await createChart.mutateAsync({
          name: saveForm.name,
          description: saveForm.description || undefined,
          savedQueryId: selectedQueryId,
          chartType,
          config,
          isShared: saveForm.isShared,
        });
        toast.success(`차트 "${created.name}" 저장 완료`);
        setSaveDialogOpen(false);
        // 저장 성공 → dirty 해제 후 신규 차트 상세로 replace
        setIsDirty(false);
        navigate(`/analytics/charts/${created.id}`, { replace: true });
      } else {
        // 편집 기준 버전(WD-364) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다. 덮어쓰기는 빼고 보낸다.
        const version = force ? {} : { expectedUpdatedAt: baseUpdatedAt };
        const saved = await updateChart.mutateAsync({
          id: chartId!,
          // 가려진 차트는 이름·설명·공유만 보낸다 — config·chartType 을 빼야 서버의 기존 설정이 덮어써지지 않는다(WD-31②).
          data: configWithheld
            // 설명은 지웠어도 빈 문자열로 보낸다 — undefined 면 필드가 빠져 서버가 "변경 없음"으로 보고 옛 설명을 남긴다(WD-197).
            ? { name: saveForm.name, description: saveForm.description, isShared: saveForm.isShared, ...version }
            : {
                name: saveForm.name,
                description: saveForm.description,
                chartType,
                config,
                isShared: saveForm.isShared,
                ...version,
              },
        });
        // 새 버전을 바로 기준으로 삼는다 — 재조회를 기다리면 그 사이 다시 저장할 때 자기 저장과 충돌한다.
        // 응답에 버전이 없으면(옛 서버·모킹) null — 다음 저장은 비교 없이 나간다(자기 충돌보다 안전한 쪽)
        setBaseUpdatedAt(saved?.updatedAt ?? null);
        setEditConflict(false);
        toast.success('차트가 수정되었습니다.');
        setSaveDialogOpen(false);
        // 수정 성공 → dirty 해제 (페이지 이동은 없음)
        setIsDirty(false);
      }
    } catch (error) {
      // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-364)
      if (isEditConflict(error)) {
        setEditConflict(true);
        return;
      }
      // 이름 중복(409 DUPLICATE_NAME, WD-824)은 서버 문구를 이름 칸 아래에 보이고 토스트는 생략한다
      if (isDuplicateName(error)) {
        setNameError(extractApiError(error, '이미 있는 차트 이름입니다. 다른 이름을 입력하세요.'));
        return;
      }
      handleApiError(error, '차트 저장에 실패했습니다.');
    }
  };

  /**
   * 편집 충돌의 「다시 불러오기」(WD-364) — 최신 차트를 서버에서 새로 받아 화면을 그것으로 바꾸고 내 변경을 버린다.
   * 캐시의 차트가 재조회 결과와 같으면(구조 공유로 같은 참조) 로드 effect 가 다시 돌지 않으므로 applyChart 로 직접 채운다.
   * 저장 창은 닫는다 — 화면에서 최신 내용을 확인한 뒤 다시 저장하게 한다.
   */
  const handleReloadLatest = async () => {
    if (!chartId) return;
    setIsReloading(true);
    try {
      const latest = await queryClient.fetchQuery({
        queryKey: ['analytics', 'charts', chartId],
        queryFn: () => analyticsApi.getChart(chartId).then((r) => r.data),
        staleTime: 0,
      });
      applyChart(latest);
      setEditConflict(false);
      setSaveDialogOpen(false);
    } catch (error) {
      handleApiError(error, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
    }
  };

  if (!isNew && chartLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  // 존재하지 않는 차트 ID(404 등) 접근 시: 공용 「찾을 수 없음」 안내(제목 h1) + 목록 이동 버튼
  if (!isNew && (chartError || isInvalidId)) {
    return (
      <ResourceNotFound
        icon={BarChart2}
        message="차트를 찾을 수 없습니다."
        onBackToList={() => navigate('/analytics/charts')}
      />
    );
  }

  const isSaving = createChart.isPending || updateChart.isPending;
  const isRunning = executeQuery.isPending;
  // 쿼리 실행 완료 문구 — 결과가 없으면 null(상태 그릇에 텍스트 노드를 두지 않아 :empty 가 맞게, WD-916)
  const runStatusText =
    queryColumns.length === 0
      ? null
      : queryTruncated
        ? `${queryColumns.length}개 컬럼, 상위 ${queryRows.length}개 행 로드됨 (행 상한 — 결과가 더 있을 수 있음)`
        : `${queryColumns.length}개 컬럼, ${queryRows.length}개 행 로드됨`;

  return (
    <div className="space-y-6">
      {/* 탭 제목에 서버에서 불러온 저장된 이름을 붙인다 (WD-534) */}
      <DetailPageTitle name={existingChart?.name} />
      {/* Toolbar */}
      <div className="flex items-center gap-3 flex-wrap">
        <Button
          variant="ghost"
          size="icon"
          aria-label="목록으로 돌아가기"
          onClick={handleBackClick}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>

        {/* 좁은 화면(sm 미만)에서는 제목이 뒤로 가기 버튼 옆 한 줄을 모두 차지하고 버튼들은 다음 줄로 내려간다(WD-306) —
            버튼과 한 줄을 나누면 차트 이름이 「QA…」처럼 두세 글자만 남았다. 3rem = 뒤로 가기 버튼(2.25rem) + 간격(0.75rem). */}
        <div className="flex-1 basis-[calc(100%-3rem)] sm:basis-0 min-w-0 flex flex-col">
          {existingChart ? (
            /* 문서 개요에 페이지 heading 이 하나는 있어야 한다(02-typography §2.2, #433).
               툴바 내 제목이라 시각 크기(text-lg)는 그대로 두고 태그만 h1 으로 올린다.
               그래도 잘리는 긴 이름은 마우스를 올리거나 길게 눌러 전체를 볼 수 있게 title 을 단다(WD-306). */
            <h1 className="text-lg font-semibold truncate" title={existingChart.name}>
              {existingChart.name}
            </h1>
          ) : (
            <h1 className="text-lg font-semibold text-muted-foreground">새 차트</h1>
          )}
          {/* 미저장 변경사항 표시 — PipelineEditorPage와 동일한 시각 패턴 */}
          {isDirty && (
            <span className="text-xs text-muted-foreground flex items-center gap-1">
              <span className="text-muted-foreground">●</span>
              미저장 변경사항
            </span>
          )}
        </div>

        {/* 차트 이미지 다운로드 (PNG/SVG) — 보고서·문서 첨부용 (이슈 #74).
            실행 결과가 있는데 정책상 내보내기 불가면 비활성+사유 툴팁(주 버튼, 스펙 §5-4). 실행 전 비활성은 기존 그대로
            (사유가 정책이 아니므로 툴팁 없음). 트리거 안쪽을 감싸면 드롭다운 동작이 꼬이므로 분기로 나눈다.
            설정이 가려진 차트는 미리보기를 그릴 수 없으니 버튼을 아예 두지 않는다(WD-31②) — 내보내기 차단 툴팁보다 우선. */}
        {!configWithheld &&
          (queryColumns.length > 0 && !exportAllowed ? (
            <ExportBlockedTooltip blocked label="차트 다운로드">
              <Button variant="outline" size="sm" className="gap-1.5" aria-label="차트 다운로드">
                <Download className="h-4 w-4" />
                다운로드
              </Button>
            </ExportBlockedTooltip>
          ) : (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-1.5"
                  disabled={queryColumns.length === 0}
                  aria-label="차트 다운로드"
                >
                  <Download className="h-4 w-4" />
                  다운로드
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => void handleDownloadChart('png')}>
                  <FileImage className="h-4 w-4" />
                  PNG 이미지로 저장
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => void handleDownloadChart('svg')}>
                  <FileType className="h-4 w-4" />
                  SVG 벡터로 저장
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ))}

        {/* 대시보드 추가 (#97) — 차트가 저장된 상태에서만 활성화.
            저장 안 된 차트는 chartId가 없어 위젯으로 추가 불가. */}
        {chartId && (
          <Button
            variant="outline"
            size="sm"
            className="gap-1.5"
            onClick={() => setAddToDashboardOpen(true)}
            disabled={isDirty}
            title={isDirty ? '저장 후 대시보드에 추가할 수 있습니다' : '대시보드에 추가'}
          >
            <LayoutDashboard className="h-4 w-4" />
            대시보드에 추가
          </Button>
        )}

        {/* 저장은 작성자에게만(WD-196) */}
        {canEdit && (
          <Button
            variant="outline"
            size="sm"
            onClick={handleSaveClick}
            disabled={isSaving}
            className="gap-1.5"
          >
            {isSaving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            저장
          </Button>
        )}
      </div>

      {/* 남이 공유한 차트 — 저장 버튼이 없는 이유와 할 수 있는 일을 알린다(WD-196). 차트 복제 기능은 아직 없어 안내만 둔다. */}
      {!canEdit && existingChart && (
        <SharedReadOnlyNotice
          testId="chart-shared-readonly"
          message={`${existingChart.createdByName}님이 공유한 차트입니다 — 설정을 바꿔 볼 수는 있지만 저장·이름 변경은 작성자만 할 수 있습니다.`}
        />
      )}

      {/* Main layout: config panel (left) + preview (right) */}
      <div className="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4 items-start">
        {/* Left: Config panel */}
        <div className="space-y-3">
          {/* Query selection */}
          <Card className="py-3 gap-2">
            <CardHeader className="px-4 pb-0">
              <CardTitle className="text-sm">데이터 소스</CardTitle>
            </CardHeader>
            <CardContent className="px-4 space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor={`${baseId}-saved-query`} className="text-xs text-muted-foreground">
                  저장된 쿼리
                </Label>
                <Select
                  value={selectedQueryId ? String(selectedQueryId) : NO_QUERY}
                  onValueChange={handleQueryChange}
                  // 목록을 모르는 상태(로딩·실패)에서 다른 쿼리로 덮어쓰지 못하도록 선택을 막는다 (#738).
                  // 설정이 가려진 차트도 막는다 — 쿼리를 바꾸면 보이지 않는 기존 설정과 어긋난다(WD-31②).
                  disabled={queriesLoading || queriesError || configWithheld}
                >
                  <SelectTrigger
                    id={`${baseId}-saved-query`}
                    size="sm"
                    className="text-sm w-full truncate"
                  >
                    <SelectValue placeholder="쿼리 선택" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NO_QUERY}>쿼리 선택</SelectItem>
                    {queries.map((q) => (
                      <SelectItem key={q.id} value={String(q.id)}>
                        {q.name}
                      </SelectItem>
                    ))}
                    {/* 목록에 없는(조회 실패·로딩 중) 기존 차트의 쿼리도 칸이 비지 않도록 차트가 가진 이름으로 항목을 둔다 (#738) */}
                    {existingChart &&
                      selectedQueryId === existingChart.savedQueryId &&
                      !queries.some((q) => q.id === existingChart.savedQueryId) && (
                        <SelectItem value={String(existingChart.savedQueryId)}>
                          {existingChart.savedQueryName}
                        </SelectItem>
                      )}
                  </SelectContent>
                </Select>
                {queriesError && (
                  // 조회 실패를 "쿼리 없음"으로 위장하지 않는다 — 대시보드 추가 다이얼로그와 같은 방식 (#737, #738)
                  <p role="alert" className="text-xs text-destructive">
                    저장 쿼리 목록을 불러오지 못했습니다.
                  </p>
                )}
              </div>
              <Button
                size="sm"
                className="w-full gap-1.5"
                onClick={handleRunQuery}
                disabled={!selectedQueryId || isRunning || configWithheld}
              >
                {isRunning ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Play className="h-4 w-4" />
                )}
                쿼리 실행
              </Button>
              {configWithheld && (
                // 비활성 이유를 컨트롤 바로 아래에 알린다(디자이너 리뷰)
                <p className="text-xs text-muted-foreground">설정이 잠긴 차트는 쿼리를 바꾸거나 실행할 수 없습니다.</p>
              )}
              {/* 실행 완료 문구(WD-916, WCAG 4.1.3): 새로 끼워진 live region 은 읽히지 않으므로 그릇을 늘 두고 글자만 바꾼다
                  (같은 결과로 다시 실행하면 글자가 그대로라 토스트가 알린다). 비었을 때는 space-y-3 의 12px 간격을 empty:-mt-3 로 상쇄한다. */}
              <div
                role="status"
                aria-live="polite"
                aria-atomic="true"
                className="text-xs text-muted-foreground empty:-mt-3"
              >
                {runStatusText}
              </div>
            </CardContent>
          </Card>

          {/* 설정이 가려진 차트(WD-31②)는 차트 타입·축 설정 대신 잠금 안내 한 장을 둔다.
              타입만 바꿔 저장해도 보이지 않는 기존 config 와 어긋나므로 타입 선택기도 함께 잠근다. */}
          {configWithheld ? (
            <Card className="py-3 gap-2">
              <CardContent className="px-4 py-2">
                <ChartConfigWithheldNotice />
              </CardContent>
            </Card>
          ) : (
            <>
            {/* Chart type */}
            <Card className="py-3 gap-2">
              <CardHeader className="px-4 pb-0">
                <CardTitle className="text-sm">차트 타입</CardTitle>
              </CardHeader>
              <CardContent className="px-4">
                <ChartTypeSelector value={chartType} onChange={handleChartTypeChange} />
              </CardContent>
            </Card>

            {/* Axis config */}
            <Card className="py-3 gap-2">
              <CardHeader className="px-4 pb-0">
                <CardTitle className="text-sm">축 설정</CardTitle>
              </CardHeader>
              <CardContent className="px-4">
                {queryColumns.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    먼저 쿼리를 실행하여 컬럼을 불러오세요.
                  </p>
                ) : (
                  <AxisConfigPanel
                    chartType={chartType}
                    columns={queryColumns}
                    numericColumns={numericQueryColumns}
                    config={config}
                    onChange={handleConfigChange}
                  />
                )}
              </CardContent>
            </Card>
            </>
          )}
        </div>

        {/* Right: Preview — sticky so it stays visible while scrolling config */}
        <Card className="py-3 gap-2 overflow-hidden lg:sticky lg:top-4">
          <CardHeader className="px-4 pb-0">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm">미리보기</CardTitle>
              <div className="flex items-center gap-2">
                {queryRows.length > 0 && (
                  <span className="text-xs text-muted-foreground">
                    {queryTruncated ? `상위 ${queryRows.length}행 기준` : `${queryRows.length}행 기준`}
                  </span>
                )}
                {/* WD-832: 값 표를 낼 수 있는 종류이고 미리보기 차트가 그려질 때만 — 가려진 설정·미실행 상태에는 버튼이 없다 */}
                {!configWithheld && queryColumns.length > 0 && chartSupportsValueTable(chartType) && (
                  <ChartTableToggle pressed={tableView} onPressedChange={setTableView} />
                )}
              </div>
            </div>
          </CardHeader>
          <Separator />
          <CardContent className="px-0 pt-0">
            {configWithheld ? (
              // 설정이 가려진 차트는 미리보기를 그리지 않는다(WD-31②). 정식 안내는 좌측 카드 한 곳에만 두고 여기는
              // 보조 상태만 보인다 — 같은 문장이 두 번 보이거나 읽히지 않게(디자이너 리뷰). 모바일은 높이를 줄인다.
              <div
                data-testid="chart-preview-withheld"
                className="flex h-[200px] flex-col items-center justify-center gap-1 px-4 text-center text-muted-foreground lg:h-[400px]"
              >
                <Lock className="h-5 w-5" aria-hidden="true" />
                <p className="text-xs">미리보기를 표시할 수 없습니다.</p>
              </div>
            ) : queryColumns.length === 0 ? (
              <div
                className="flex flex-col items-center justify-center text-muted-foreground gap-2 px-4"
                style={{ height: 400 }}
              >
                <Play className="h-8 w-8 opacity-30" />
                <p className="text-sm">쿼리를 실행하면 차트가 표시됩니다.</p>
              </div>
            ) : (
              <div
                ref={previewContainerRef}
                data-testid="chart-builder-preview"
                className="px-4 pt-3"
                style={{ height: 460 }}
              >
                {/* WD-821: 저장 대화상자 등 부모 상태 변화로 미리보기 차트를 다시 계산하지 않는다 */}
                <MemoChartRenderer
                  chartType={chartType}
                  config={config}
                  data={queryRows}
                  columns={queryColumns}
                  fillParent
                  // WD-832: 문자열 원시값만 넘겨 WD-821 memo 를 유지한다
                  view={tableView ? 'table' : 'chart'}
                  // WD-505: 사용자가 비운 축을 결과 컬럼으로 추론하지 않는다 — 문자 컬럼이 값으로 읽혀 NaN 이 그려졌다
                  inferAxes={false}
                  // WD-826: 접근 이름에는 서버에 저장된 차트 이름만 쓴다(새 차트는 undefined → 축 정보로 자동 생성).
                  // 편집 중인 saveForm.name 을 넘기면 저장 대화상자에서 글자를 칠 때마다 prop 이 바뀌어 WD-821 memo 가 깨진다.
                  title={existingChart?.name}
                />
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 대시보드에 추가 다이얼로그 (#97) */}
      {chartId && (
        <AddToDashboardDialog
          open={addToDashboardOpen}
          onOpenChange={setAddToDashboardOpen}
          chartId={chartId}
          chartType={chartType}
          onNavigate={requestDashboardNavigate}
        />
      )}

      {/* Save Dialog */}
      <SaveDialog
        open={saveDialogOpen}
        onOpenChange={setSaveDialogOpen}
        name={saveForm.name}
        onNameChange={(v) => {
          setSaveForm((p) => ({ ...p, name: v }));
          // 이름을 고치면 중복 안내는 낡은 것이다(WD-824)
          setNameError(null);
        }}
        nameError={nameError}
        description={saveForm.description}
        onDescriptionChange={(v) => setSaveForm((p) => ({ ...p, description: v }))}
        isShared={saveForm.isShared}
        onIsSharedChange={(v) => setSaveForm((p) => ({ ...p, isShared: v }))}
        onSave={() => handleSave()}
        isSaving={isSaving}
        isEdit={!isNew}
        configWithheld={configWithheld}
      />

      {/* 편집 충돌 안내 창(WD-364) — 저장이 409 EDIT_CONFLICT 로 거부되면 저장 창 위에 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(open) => {
          if (!open) setEditConflict(false);
        }}
        resourceLabel="차트"
        onReload={handleReloadLatest}
        onOverwrite={() => handleSave(true)}
        pending={isSaving || isReloading}
      />

      {unsavedChangesDialog}
    </div>
  );
}
