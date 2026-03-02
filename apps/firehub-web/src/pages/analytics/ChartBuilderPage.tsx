import { ArrowLeft, Loader2, Play,Save } from 'lucide-react';
import { useCallback,useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { AxisConfigPanel } from '../../components/analytics/AxisConfigPanel';
import { ChartRenderer } from '../../components/analytics/ChartRenderer';
import { ChartTypeSelector } from '../../components/analytics/ChartTypeSelector';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import {
  Dialog,
  DialogContent,
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
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="chart-name">이름 *</Label>
            <Input
              id="chart-name"
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="차트 이름을 입력하세요"
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
            {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
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

  // Existing chart (edit mode)
  const { data: existingChart, isLoading: chartLoading } = useChart(chartId);

  const executeQuery = useExecuteSavedQuery();
  const createChart = useCreateChart();
  const updateChart = useUpdateChart();

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
    }
  }, [existingChart]);

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

      // Auto-recommend chart type and config only when first loading data
      const recommended = recommendChartType(result.columns, result.rows);
      setChartType(recommended);
      setConfig(buildDefaultConfig(result.columns, result.rows, recommended));
    } catch (error) {
      handleApiError(error, '쿼리 실행에 실패했습니다.');
    }
  }, [selectedQueryId, executeQuery]);

  // When chartType changes, rebuild default config if we already have columns but config is empty
  // MAP ↔ 비MAP 전환 시 항상 config 리빌드 (렌더링 패러다임이 다름)
  const handleChartTypeChange = (type: ChartType) => {
    setChartType(type);
    const switchingToOrFromMap = type === 'MAP' || chartType === 'MAP';
    if (queryColumns.length > 0 && (!config.xAxis || switchingToOrFromMap)) {
      setConfig(buildDefaultConfig(queryColumns, queryRows, type));
    }
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

  const isSaving = createChart.isPending || updateChart.isPending;
  const isRunning = executeQuery.isPending;

  return (
    <div className="space-y-6">
      {/* Toolbar */}
      <div className="flex items-center gap-3 flex-wrap">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate('/analytics/charts')}
          className="gap-1.5"
        >
          <ArrowLeft className="h-4 w-4" />
          목록
        </Button>

        <div className="flex-1 min-w-0">
          {existingChart ? (
            <span className="font-semibold truncate">{existingChart.name}</span>
          ) : (
            <span className="font-semibold text-muted-foreground">새 차트</span>
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
                  onValueChange={(v) => {
                    setSelectedQueryId(v === NO_QUERY ? null : parseInt(v, 10));
                    setQueryColumns([]);
                    setQueryRows([]);
                  }}
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
                  onChange={setConfig}
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
    </div>
  );
}
