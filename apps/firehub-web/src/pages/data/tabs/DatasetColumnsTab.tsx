import React, { useCallback, lazy, Suspense } from 'react';
import {
  useDeleteColumn,
  useReorderColumns,
  useColumnStats,
  useUpdateColumn,
} from '../../../hooks/queries/useDatasets';
import type {
  DatasetDetailResponse,
  DatasetColumnResponse,
  ColumnStatsResponse,
} from '../../../types/dataset';
import { Button } from '../../../components/ui/button';
import { Badge } from '../../../components/ui/badge';
import { Input } from '../../../components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../../components/ui/alert-dialog';
import {
  ChevronUp,
  ChevronDown,
  ChevronRight,
  Pencil,
  Trash2,
  Loader2,
} from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';

const EditColumnDialog = lazy(() =>
  import('../components/EditColumnDialog').then((m) => ({ default: m.EditColumnDialog }))
);
const AddColumnDialog = lazy(() =>
  import('../components/AddColumnDialog').then((m) => ({ default: m.AddColumnDialog }))
);

interface DatasetColumnsTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

function getDataTypeBadge(dataType: string, maxLength?: number | null) {
  const colorMap: Record<string, 'default' | 'secondary' | 'outline'> = {
    TEXT: 'default',
    VARCHAR: 'default',
    INTEGER: 'secondary',
    DECIMAL: 'secondary',
    BOOLEAN: 'outline',
    DATE: 'outline',
    TIMESTAMP: 'outline',
  };
  const displayType = dataType === 'VARCHAR' && maxLength ? `VARCHAR(${maxLength})` : dataType;
  return <Badge variant={colorMap[dataType] || 'default'}>{displayType}</Badge>;
}

function NullProgressBar({ percent }: { percent: number }) {
  const color =
    percent === 0 ? 'bg-green-500' : percent <= 30 ? 'bg-yellow-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-[60px] h-1.5 bg-muted rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${color}`}
          style={{ width: `${Math.min(percent, 100)}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground">{percent.toFixed(1)}%</span>
    </div>
  );
}

function ColumnExpandedStats({
  stats,
  dataType,
}: {
  stats: ColumnStatsResponse;
  dataType: string;
}) {
  const isNumeric = dataType === 'INTEGER' || dataType === 'DECIMAL';
  const isText = dataType === 'TEXT' || dataType === 'VARCHAR';
  const isDate = dataType === 'DATE' || dataType === 'TIMESTAMP';
  const isBoolean = dataType === 'BOOLEAN';

  const maxTopCount = stats.topValues.length > 0 ? Math.max(...stats.topValues.map((v) => v.count)) : 1;

  return (
    <div className="p-4 bg-muted/30 space-y-4">
      {/* Common: null summary */}
      <div className="space-y-1">
        <div className="text-xs font-medium text-muted-foreground">
          NULL: {stats.nullCount.toLocaleString()} / {stats.totalCount.toLocaleString()}
        </div>
        <div className="w-full h-2 bg-muted rounded-full overflow-hidden">
          <div
            className={`h-full rounded-full ${
              stats.nullPercent === 0
                ? 'bg-green-500'
                : stats.nullPercent <= 30
                ? 'bg-yellow-500'
                : 'bg-red-500'
            }`}
            style={{ width: `${Math.min(stats.nullPercent, 100)}%` }}
          />
        </div>
      </div>

      {/* Numeric */}
      {isNumeric && (
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: '최솟값', value: stats.minValue ?? '-' },
            { label: '최댓값', value: stats.maxValue ?? '-' },
            {
              label: '평균값',
              value: stats.avgValue != null ? Number(stats.avgValue).toFixed(2) : '-',
            },
          ].map(({ label, value }) => (
            <div key={label} className="rounded-md border bg-background p-3 text-center">
              <div className="text-xs text-muted-foreground mb-1">{label}</div>
              <div className="text-sm font-semibold font-mono">{value}</div>
            </div>
          ))}
        </div>
      )}

      {/* Text: top 5 frequency values */}
      {isText && stats.topValues.length > 0 && (
        <div className="space-y-1.5">
          <div className="text-xs font-medium text-muted-foreground">상위 빈도 값</div>
          {stats.topValues.slice(0, 5).map((tv) => (
            <div key={tv.value} className="flex items-center gap-2">
              <span className="text-xs font-mono w-32 truncate text-right shrink-0">
                {tv.value}
              </span>
              <div className="flex-1 h-4 bg-muted rounded overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded"
                  style={{ width: `${(tv.count / maxTopCount) * 100}%` }}
                />
              </div>
              <span className="text-xs text-muted-foreground w-12 shrink-0">
                {tv.count.toLocaleString()}
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Date */}
      {isDate && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground text-xs">범위:</span>
          <span className="font-mono text-xs">{stats.minValue ?? '-'}</span>
          <span className="text-muted-foreground">~</span>
          <span className="font-mono text-xs">{stats.maxValue ?? '-'}</span>
        </div>
      )}

      {/* Boolean */}
      {isBoolean && stats.topValues.length > 0 && (
        <div className="space-y-1">
          <div className="text-xs font-medium text-muted-foreground">True / False 비율</div>
          {(() => {
            const trueEntry = stats.topValues.find(
              (v) => v.value.toLowerCase() === 'true'
            );
            const falseEntry = stats.topValues.find(
              (v) => v.value.toLowerCase() === 'false'
            );
            const trueCount = trueEntry?.count ?? 0;
            const falseCount = falseEntry?.count ?? 0;
            const total = trueCount + falseCount || 1;
            const truePct = (trueCount / total) * 100;
            const falsePct = (falseCount / total) * 100;
            return (
              <div className="flex items-center gap-2">
                <div className="flex-1 h-4 rounded overflow-hidden flex">
                  <div
                    className="h-full bg-green-500"
                    style={{ width: `${truePct}%` }}
                    title={`true: ${trueCount}`}
                  />
                  <div
                    className="h-full bg-red-500"
                    style={{ width: `${falsePct}%` }}
                    title={`false: ${falseCount}`}
                  />
                </div>
                <span className="text-xs text-muted-foreground shrink-0">
                  T:{trueCount} / F:{falseCount}
                </span>
              </div>
            );
          })()}
        </div>
      )}

      {/* Sampled notice */}
      {stats.sampled && (
        <p className="text-xs italic text-muted-foreground">
          * 10만행 초과 데이터셋으로 샘플링된 통계입니다
        </p>
      )}
    </div>
  );
}

interface DescriptionCellProps {
  col: DatasetColumnResponse;
  datasetId: number;
  isEditing: boolean;
  onStartEdit: () => void;
  onEndEdit: () => void;
}

function DescriptionCell({
  col,
  datasetId,
  isEditing,
  onStartEdit,
  onEndEdit,
}: DescriptionCellProps) {
  const [value, setValue] = React.useState(col.description ?? '');
  const [saving, setSaving] = React.useState(false);
  const updateColumn = useUpdateColumn(datasetId, col.id);

  // Sync when col.description changes (e.g. after invalidation)
  React.useEffect(() => {
    if (!isEditing) {
      setValue(col.description ?? '');
    }
  }, [col.description, isEditing]);

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await updateColumn.mutateAsync({ description: value });
      toast.success('설명이 저장되었습니다');
      onEndEdit();
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '설명 저장에 실패했습니다.');
      } else {
        toast.error('설명 저장에 실패했습니다.');
      }
      setValue(col.description ?? '');
      onEndEdit();
    } finally {
      setSaving(false);
    }
  }, [value, col.description, updateColumn, onEndEdit]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        handleSave();
      } else if (e.key === 'Escape') {
        setValue(col.description ?? '');
        onEndEdit();
      }
    },
    [handleSave, col.description, onEndEdit]
  );

  if (isEditing) {
    return (
      <div className="flex items-center gap-1">
        <Input
          className="h-7 text-sm py-0 px-2"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={saving}
          autoFocus
        />
        {saving && <Loader2 className="animate-spin shrink-0" size={14} />}
      </div>
    );
  }

  return (
    <div
      className="group flex items-center gap-1 cursor-pointer max-w-xs"
      onClick={onStartEdit}
    >
      <span className="truncate text-sm">{col.description || <span className="text-muted-foreground">-</span>}</span>
      <Pencil
        size={14}
        className="shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity"
      />
    </div>
  );
}

export const DatasetColumnsTab = React.memo(function DatasetColumnsTab({
  dataset,
  datasetId,
}: DatasetColumnsTabProps) {
  const [addColumnOpen, setAddColumnOpen] = React.useState(false);
  const [editColumnOpen, setEditColumnOpen] = React.useState(false);
  const [deleteColumnOpen, setDeleteColumnOpen] = React.useState(false);
  const [selectedColumn, setSelectedColumn] = React.useState<DatasetColumnResponse | null>(null);
  const [localColumns, setLocalColumns] = React.useState<DatasetColumnResponse[] | null>(null);
  const [expandedColumnId, setExpandedColumnId] = React.useState<number | null>(null);
  const [editingColumnId, setEditingColumnId] = React.useState<number | null>(null);

  const hasData = dataset.rowCount > 0;

  const deleteColumn = useDeleteColumn(datasetId);
  const reorderColumns = useReorderColumns(datasetId);
  const { data: statsData } = useColumnStats(datasetId, hasData);

  // Build a map: columnName -> ColumnStatsResponse
  const statsMap = React.useMemo<Map<string, ColumnStatsResponse>>(() => {
    const m = new Map<string, ColumnStatsResponse>();
    if (statsData) {
      for (const s of statsData) {
        m.set(s.columnName, s);
      }
    }
    return m;
  }, [statsData]);

  // Use localColumns for optimistic UI, fall back to dataset.columns
  const columns = localColumns ?? dataset.columns;

  // Reset local state when dataset changes
  React.useEffect(() => {
    setLocalColumns(null);
  }, [dataset.columns]);

  const handleMoveColumn = useCallback(
    async (index: number, direction: 'up' | 'down') => {
      const currentColumns = localColumns ?? dataset.columns;
      const newIndex = direction === 'up' ? index - 1 : index + 1;
      if (newIndex < 0 || newIndex >= currentColumns.length) return;

      const reordered = [...currentColumns];
      [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
      setLocalColumns(reordered);

      try {
        await reorderColumns.mutateAsync(reordered.map((c) => c.id));
      } catch (error) {
        setLocalColumns(null);
        if (axios.isAxiosError(error) && error.response?.data) {
          const errData = error.response.data as ErrorResponse;
          toast.error(errData.message || '필드 순서 변경에 실패했습니다.');
        } else {
          toast.error('필드 순서 변경에 실패했습니다.');
        }
      }
    },
    [localColumns, dataset.columns, reorderColumns]
  );

  const handleDeleteColumn = useCallback(async () => {
    if (!selectedColumn) return;

    try {
      await deleteColumn.mutateAsync(selectedColumn.id);
      toast.success('필드가 삭제되었습니다.');
      setDeleteColumnOpen(false);
      setSelectedColumn(null);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 삭제에 실패했습니다.');
      } else {
        toast.error('필드 삭제에 실패했습니다.');
      }
    }
  }, [selectedColumn, deleteColumn]);

  const handleEditClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setEditColumnOpen(true);
  }, []);

  const handleDeleteClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setDeleteColumnOpen(true);
  }, []);

  const toggleExpand = useCallback(
    (colId: number) => {
      if (!hasData) return;
      setExpandedColumnId((prev) => (prev === colId ? null : colId));
    },
    [hasData]
  );

  // Total column count: expand-chevron + order + name + displayName + type + nullable + indexed + description + null% + distinct + actions
  const TOTAL_COLS = 11;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">필드 목록 ({dataset.columns.length}개)</h2>
        {addColumnOpen && (
          <Suspense fallback={null}>
            <AddColumnDialog
              open={addColumnOpen}
              onOpenChange={setAddColumnOpen}
              datasetId={datasetId}
            />
          </Suspense>
        )}
        {!addColumnOpen && (
          <Button onClick={() => setAddColumnOpen(true)}>필드 추가</Button>
        )}
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-6" />
              <TableHead>순서</TableHead>
              <TableHead>필드명</TableHead>
              <TableHead>표시명</TableHead>
              <TableHead>데이터 타입</TableHead>
              <TableHead>NULL</TableHead>
              <TableHead>인덱스</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>Null %</TableHead>
              <TableHead>Distinct</TableHead>
              <TableHead>작업</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {columns.map((col, index) => {
              const stats = statsMap.get(col.columnName);
              const isExpanded = expandedColumnId === col.id;
              const isEditingDesc = editingColumnId === col.id;

              return (
                <React.Fragment key={col.id}>
                  <TableRow className={isExpanded ? 'bg-muted/20' : undefined}>
                    {/* Expand chevron */}
                    <TableCell className="w-6 p-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        onClick={() => toggleExpand(col.id)}
                        disabled={!hasData}
                      >
                        <ChevronRight
                          className={`h-3.5 w-3.5 transition-transform ${
                            isExpanded ? 'rotate-90' : ''
                          } ${!hasData ? 'text-muted-foreground/30' : ''}`}
                        />
                      </Button>
                    </TableCell>
                    <TableCell>{index + 1}</TableCell>
                    <TableCell className="font-mono text-sm">{col.columnName}</TableCell>
                    <TableCell>{col.displayName || '-'}</TableCell>
                    <TableCell>{getDataTypeBadge(col.dataType, col.maxLength)}</TableCell>
                    <TableCell>{col.isNullable ? '허용' : '불허'}</TableCell>
                    <TableCell>{col.isIndexed ? '예' : '아니오'}</TableCell>
                    <TableCell className="max-w-[160px]">
                      <DescriptionCell
                        col={col}
                        datasetId={datasetId}
                        isEditing={isEditingDesc}
                        onStartEdit={() => setEditingColumnId(col.id)}
                        onEndEdit={() => setEditingColumnId(null)}
                      />
                    </TableCell>
                    {/* Null % */}
                    <TableCell>
                      {stats ? (
                        <NullProgressBar percent={stats.nullPercent} />
                      ) : (
                        <span className="text-muted-foreground text-sm">-</span>
                      )}
                    </TableCell>
                    {/* Distinct */}
                    <TableCell>
                      {stats ? (
                        <Badge variant="outline">{stats.distinctCount.toLocaleString()}</Badge>
                      ) : (
                        <span className="text-muted-foreground text-sm">-</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-0.5">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => handleMoveColumn(index, 'up')}
                          disabled={index === 0 || reorderColumns.isPending}
                        >
                          <ChevronUp className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => handleMoveColumn(index, 'down')}
                          disabled={index === columns.length - 1 || reorderColumns.isPending}
                        >
                          <ChevronDown className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => handleEditClick(col)}
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => handleDeleteClick(col)}
                          disabled={columns.length <= 1}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>

                  {/* Expanded stats row */}
                  {isExpanded && stats && (
                    <TableRow>
                      <TableCell colSpan={TOTAL_COLS} className="p-0">
                        <ColumnExpandedStats stats={stats} dataType={col.dataType} />
                      </TableCell>
                    </TableRow>
                  )}
                </React.Fragment>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {/* Edit Column Dialog */}
      {editColumnOpen && (
        <Suspense fallback={null}>
          <EditColumnDialog
            open={editColumnOpen}
            onOpenChange={setEditColumnOpen}
            datasetId={datasetId}
            column={selectedColumn}
            hasData={dataset.rowCount > 0}
          />
        </Suspense>
      )}

      {/* Delete Column Confirmation Dialog */}
      <AlertDialog open={deleteColumnOpen} onOpenChange={setDeleteColumnOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>필드 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              '{selectedColumn?.columnName}' 필드를 삭제하시겠습니까? 이 작업은 되돌릴 수
              없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteColumn} variant="destructive">
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
});
