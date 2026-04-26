import { ArrowLeft, BarChart2, Loader2, Play,Save } from 'lucide-react';
import { useCallback,useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { AxisConfigPanel } from '../../components/analytics/AxisConfigPanel';
import { ChartRenderer } from '../../components/analytics/ChartRenderer';
import { ChartTypeSelector } from '../../components/analytics/ChartTypeSelector';
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
  useChart,
  useCreateChart,
  useExecuteSavedQuery,
  useSavedQueries,
  useUpdateChart,
} from '../../hooks/queries/useAnalytics';
import { handleApiError } from '../../lib/api-error';
import type { ChartConfig,ChartType } from '../../types/analytics';

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

  const isNumeric = (col: string): boolean => {
    const sample = rows.slice(0, 20).map((r) => r[col]);
    const nums = sample.filter((v) => v != null && !isNaN(Number(v)));
    return nums.length / sample.length > 0.7;
  };

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
    const vals = rows.map((r) => Number(r[numericCols[0]])).filter((v) => !isNaN(v));
    const hasPositive = vals.some((v) => v > 0);
    const hasNegative = vals.some((v) => v < 0);
    if (hasPositive && hasNegative) return 'WATERFALL';
  }

  if (dateCols.length > 0 && numericCols.length > 0) return 'LINE';
  if (numericCols.length >= 2) return 'SCATTER';
  if (categoryCols.length > 0 && numericCols.length > 0) {
    const uniqueVals = new Set(rows.map((r) => r[categoryCols[0]])).size;
    return uniqueVals <= 5 ? 'PIE' : 'BAR';
  }
  return 'BAR';
}

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

  const isNumeric = (col: string): boolean => {
    const sample = rows.slice(0, 20).map((r) => r[col]);
    const nums = sample.filter((v) => v != null && !isNaN(Number(v)));
    return nums.length / sample.length > 0.7;
  };

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
}: SaveDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isEdit ? '차트 수정' : '차트 저장'}</DialogTitle>
          <DialogDescription className="sr-only">
            {isEdit ? '차트 설정을 수정하여 저장합니다.' : '차트 이름과 설정을 입력하여 저장합니다.'}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="chart-name">이름 *</Label>
            <Input
              id="chart-name"
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="차트 이름을 입력하세요"
              maxLength={200}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="chart-description">설명</Label>
            <Input
              id="chart-description"
              value={description}
              onChange={(e) => onDescriptionChange(e.target.value)}
              placeholder="선택사항"
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
          <Button onClick={onSave} disabled={!name.trim() || isSaving}>
            {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
            {isEdit ? '수정' : '저장'}
          </Button>
        </DialogFooter>
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

  const chartId = id ? parseInt(id, 10) : null;
  const isNew = !chartId;

  // Pre-selected query from URL (?queryId=123)
  const initialQueryId = searchParams.get('queryId')
    ? parseInt(searchParams.get('queryId')!, 10)
    : null;

  // Form state
  const [selectedQueryId, setSelectedQueryId] = useState<number | null>(initialQueryId);
  const [chartType, setChartType] = useState<ChartType>('BAR');
  const [config, setConfig] = useState<ChartConfig>({
    xAxis: '',
    yAxis: [],
    showLegend: true,
    showGrid: true,
    stacked: false,
  });
  const [queryColumns, setQueryColumns] = useState<string[]>([]);
  const [queryRows, setQueryRows] = useState<Record<string, unknown>[]>([]);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const [saveForm, setSaveForm] = useState({
    name: '',
    description: '',
    isShared: false,
  });

  // Queries list for dropdown
  const { data: queriesData } = useSavedQueries({ size: 100 });
  const queries = queriesData?.content ?? [];

  // Existing chart (edit mode) — isError: 존재하지 않는 차트 ID(404 등) 접근 시 에러 감지
  const { data: existingChart, isLoading: chartLoading, isError: chartError } = useChart(chartId);

  const executeQuery = useExecuteSavedQuery();
  const createChart = useCreateChart();
  const updateChart = useUpdateChart();

  // 사용자 상호작용 후 변경 여부 추적
  // - 초기 로드(existingChart로부터 setState)는 변경으로 간주하지 않기 위해 핸들러에서 명시적으로 markDirty()를 호출
  const [isDirty, setIsDirty] = useState(false);
  const markDirty = useCallback(() => setIsDirty(true), []);

  // 이탈 확인 다이얼로그 상태 — 뒤로가기 클릭 시 dirty면 오픈
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);

  // Load existing chart into state
  useEffect(() => {
    if (existingChart) {
      setSelectedQueryId(existingChart.savedQueryId);
      setChartType(existingChart.chartType);
      setConfig(existingChart.config);
      setSaveForm({
        name: existingChart.name,
        description: existingChart.description ?? '',
        isShared: existingChart.isShared,
      });
      // 초기 로드는 dirty가 아님
      setIsDirty(false);
    }
  }, [existingChart]);

  // 브라우저 탭 닫기·새로고침 시 이탈 경고 (PipelineEditorPage와 동일 패턴)
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (isDirty) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

  // 뒤로가기 버튼 클릭 핸들러 — dirty면 다이얼로그 표시, 아니면 즉시 이동
  const handleBackClick = () => {
    if (isDirty) {
      setLeaveDialogOpen(true);
    } else {
      navigate('/analytics/charts');
    }
  };

  // 다이얼로그에서 '이탈' 클릭 — 변경사항 버리고 목록으로 이동
  const handleLeaveConfirm = () => {
    setLeaveDialogOpen(false);
    setIsDirty(false);
    navigate('/analytics/charts');
  };

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
      toast.success(`${result.rows.length}행 로드됨 (${result.executionTimeMs}ms)`);

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
  };

  const handleSaveClick = () => {
    if (existingChart) {
      setSaveForm({
        name: existingChart.name,
        description: existingChart.description ?? '',
        isShared: existingChart.isShared,
      });
    }
    setSaveDialogOpen(true);
  };

  const handleSave = async () => {
    if (!saveForm.name.trim()) return;
    if (!selectedQueryId) {
      toast.error('쿼리를 선택하세요.');
      return;
    }
    if (chartType === 'MAP') {
      if (!config.spatialColumn) {
        toast.error('공간 컬럼을 선택하세요.');
        return;
      }
    } else if (['CANDLESTICK', 'BOXPLOT', 'HISTOGRAM'].includes(chartType)) {
      // 이 타입들은 yAxis 대신 전용 컬럼 설정 사용 — xAxis만 필수
      if (!config.xAxis) {
        toast.error('X축을 설정하세요.');
        return;
      }
    } else if (chartType === 'GAUGE') {
      // 게이지는 yAxis[0]만 필수
      if (config.yAxis.length === 0) {
        toast.error('Y축(값 컬럼)을 설정하세요.');
        return;
      }
    } else if (!config.xAxis || config.yAxis.length === 0) {
      toast.error('X축과 Y축을 설정하세요.');
      return;
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
        await updateChart.mutateAsync({
          id: chartId!,
          data: {
            name: saveForm.name,
            description: saveForm.description || undefined,
            chartType,
            config,
            isShared: saveForm.isShared,
          },
        });
        toast.success('차트가 수정되었습니다.');
        setSaveDialogOpen(false);
        // 수정 성공 → dirty 해제 (페이지 이동은 없음)
        setIsDirty(false);
      }
    } catch (error) {
      handleApiError(error, '차트 저장에 실패했습니다.');
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

  // 존재하지 않는 차트 ID(404 등) 접근 시: 에러 안내 + 목록 이동 버튼 표시
  if (!isNew && chartError) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20">
        <BarChart2 className="h-12 w-12 text-muted-foreground" />
        <p className="text-muted-foreground">차트를 찾을 수 없습니다.</p>
        <Button variant="outline" onClick={() => navigate('/analytics/charts')}>
          목록으로
        </Button>
      </div>
    );
  }

  const isSaving = createChart.isPending || updateChart.isPending;
  const isRunning = executeQuery.isPending;

  return (
    <div className="space-y-6">
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

        <div className="flex-1 min-w-0 flex flex-col">
          {existingChart ? (
            <span className="text-lg font-semibold truncate">{existingChart.name}</span>
          ) : (
            <span className="text-lg font-semibold text-muted-foreground">새 차트</span>
          )}
          {/* 미저장 변경사항 표시 — PipelineEditorPage와 동일한 시각 패턴 */}
          {isDirty && (
            <span className="text-xs text-muted-foreground flex items-center gap-1">
              <span className="text-muted-foreground">●</span>
              미저장 변경사항
            </span>
          )}
        </div>

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
      </div>

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
                <Label className="text-xs text-muted-foreground">저장된 쿼리</Label>
                <Select
                  value={selectedQueryId ? String(selectedQueryId) : NO_QUERY}
                  onValueChange={handleQueryChange}
                >
                  <SelectTrigger className="h-8 text-sm w-full truncate">
                    <SelectValue placeholder="쿼리 선택" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NO_QUERY}>쿼리 선택</SelectItem>
                    {queries.map((q) => (
                      <SelectItem key={q.id} value={String(q.id)}>
                        {q.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <Button
                size="sm"
                className="w-full gap-1.5"
                onClick={handleRunQuery}
                disabled={!selectedQueryId || isRunning}
              >
                {isRunning ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Play className="h-4 w-4" />
                )}
                쿼리 실행
              </Button>
              {queryColumns.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  {queryColumns.length}개 컬럼, {queryRows.length}개 행 로드됨
                </p>
              )}
            </CardContent>
          </Card>

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
                  config={config}
                  onChange={handleConfigChange}
                />
              )}
            </CardContent>
          </Card>
        </div>

        {/* Right: Preview — sticky so it stays visible while scrolling config */}
        <Card className="py-3 gap-2 overflow-hidden lg:sticky lg:top-4">
          <CardHeader className="px-4 pb-0">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm">미리보기</CardTitle>
              {queryRows.length > 0 && (
                <span className="text-xs text-muted-foreground">
                  {queryRows.length}행 기준
                </span>
              )}
            </div>
          </CardHeader>
          <Separator />
          <CardContent className="px-0 pt-0">
            {queryColumns.length === 0 ? (
              <div
                className="flex flex-col items-center justify-center text-muted-foreground gap-2 px-4"
                style={{ height: 400 }}
              >
                <Play className="h-8 w-8 opacity-30" />
                <p className="text-sm">쿼리를 실행하면 차트가 표시됩니다.</p>
              </div>
            ) : (
              <div className="px-4 pt-3" style={{ height: 460 }}>
                <ChartRenderer
                  chartType={chartType}
                  config={config}
                  data={queryRows}
                  columns={queryColumns}
                  fillParent
                />
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Save Dialog */}
      <SaveDialog
        open={saveDialogOpen}
        onOpenChange={setSaveDialogOpen}
        name={saveForm.name}
        onNameChange={(v) => setSaveForm((p) => ({ ...p, name: v }))}
        description={saveForm.description}
        onDescriptionChange={(v) => setSaveForm((p) => ({ ...p, description: v }))}
        isShared={saveForm.isShared}
        onIsSharedChange={(v) => setSaveForm((p) => ({ ...p, isShared: v }))}
        onSave={handleSave}
        isSaving={isSaving}
        isEdit={!isNew}
      />

      {/*
        이탈 확인 다이얼로그 — 뒤로가기 클릭 시 dirty면 표시
        취소 시 머무름, 확인 시 변경사항 버리고 이동
      */}
      <AlertDialog open={leaveDialogOpen} onOpenChange={setLeaveDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
            <AlertDialogDescription>
              저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleLeaveConfirm}>이탈</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
