import React, { useMemo, lazy, Suspense, useRef, useEffect, useState, useCallback } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useDatasetData, useDeleteDataRows, useColumnStats } from '../../../hooks/queries/useDatasets';
import { dataImportsApi } from '../../../api/dataImports';
import type { DatasetDetailResponse } from '../../../types/dataset';
import type { ColumnStatsResponse } from '../../../types/dataset';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Card } from '../../../components/ui/card';
import { Checkbox } from '../../../components/ui/checkbox';
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
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../../../components/ui/popover';
import { Download, Upload, Search, ArrowUpDown, ArrowUp, ArrowDown, Loader2, Terminal, Plus } from 'lucide-react';
import { toast } from 'sonner';
import { formatCellValue, isNullValue, getRawCellValue } from '../../../lib/formatters';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';

const ImportMappingDialog = lazy(() =>
  import('../components/ImportMappingDialog').then((m) => ({ default: m.ImportMappingDialog }))
);

import { SqlQueryEditor } from '../components/SqlQueryEditor';
import { AddRowDialog } from '../components/AddRowDialog';
import { EditRowDialog } from '../components/EditRowDialog';

interface DatasetDataTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

// Mini histogram for numeric columns
function NumericMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const bars = stats.topValues.slice(0, 5);
  if (bars.length === 0) return <div style={{ height: 20 }} />;
  const maxCount = Math.max(...bars.map((b) => b.count));
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      {bars.map((bar, i) => {
        const barWidth = maxCount > 0 ? (bar.count / maxCount) * 100 : 0;
        const x = (i / bars.length) * 100;
        const w = 100 / bars.length - 1;
        const barH = maxCount > 0 ? Math.max(2, (bar.count / maxCount) * 18) : 0;
        return (
          <rect
            key={i}
            x={`${x}%`}
            y={20 - barH}
            width={`${w}%`}
            height={barH}
            fill="hsl(var(--primary))"
            opacity={0.7 + (barWidth / 100) * 0.3}
          />
        );
      })}
    </svg>
  );
}

// Segmented bar for text columns
function TextMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const top3 = stats.topValues.slice(0, 3);
  if (top3.length === 0) return <div style={{ height: 20 }} />;
  const total = top3.reduce((s, v) => s + v.count, 0);
  const colors = ['hsl(215, 70%, 60%)', 'hsl(215, 70%, 75%)', 'hsl(215, 70%, 88%)'];
  let offset = 0;
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      {top3.map((bar, i) => {
        const pct = total > 0 ? (bar.count / total) * 100 : 0;
        const rect = (
          <rect
            key={i}
            x={`${offset}%`}
            y={8}
            width={`${pct}%`}
            height={8}
            fill={colors[i]}
          />
        );
        offset += pct;
        return rect;
      })}
    </svg>
  );
}

// Two-color ratio bar for boolean columns
function BooleanMiniChart({ stats }: { stats: ColumnStatsResponse }) {
  const trueEntry = stats.topValues.find((v) => v.value?.toLowerCase() === 'true');
  const falseEntry = stats.topValues.find((v) => v.value?.toLowerCase() === 'false');
  const trueCount = trueEntry?.count ?? 0;
  const falseCount = falseEntry?.count ?? 0;
  const total = trueCount + falseCount;
  if (total === 0) return <div style={{ height: 20 }} />;
  const truePct = (trueCount / total) * 100;
  return (
    <svg width="100%" height="20" style={{ display: 'block' }}>
      <rect x="0" y="8" width={`${truePct}%`} height={8} fill="hsl(142, 70%, 45%)" />
      <rect x={`${truePct}%`} y="8" width={`${100 - truePct}%`} height={8} fill="hsl(0, 60%, 60%)" />
    </svg>
  );
}

// Date range display
function DateMiniDisplay({ stats }: { stats: ColumnStatsResponse }) {
  const min = stats.minValue ?? '';
  const max = stats.maxValue ?? '';
  if (!min && !max) return <div style={{ height: 20 }} />;
  return (
    <div style={{ height: 20, display: 'flex', alignItems: 'center' }}>
      <span className="text-[10px] text-muted-foreground truncate">
        {min} ~ {max}
      </span>
    </div>
  );
}

// Full stats popover content
function ColumnStatsPopoverContent({ stats }: { stats: ColumnStatsResponse }) {
  const isNumeric = stats.dataType === 'INTEGER' || stats.dataType === 'DECIMAL';
  return (
    <div className="space-y-2 text-sm">
      <div>
        <span className="font-semibold">{stats.columnName}</span>
        <span className="ml-2 text-xs text-muted-foreground bg-muted px-1 py-0.5 rounded">
          {stats.dataType}
        </span>
      </div>
      <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
        <span className="text-muted-foreground">Total</span>
        <span>{stats.totalCount.toLocaleString()}</span>
        <span className="text-muted-foreground">Null</span>
        <span>{stats.nullCount.toLocaleString()} ({stats.nullPercent.toFixed(1)}%)</span>
        <span className="text-muted-foreground">Distinct</span>
        <span>{stats.distinctCount.toLocaleString()}</span>
        {isNumeric && (
          <>
            <span className="text-muted-foreground">Min</span>
            <span>{stats.minValue ?? '-'}</span>
            <span className="text-muted-foreground">Max</span>
            <span>{stats.maxValue ?? '-'}</span>
            <span className="text-muted-foreground">Avg</span>
            <span>{stats.avgValue != null ? stats.avgValue.toFixed(2) : '-'}</span>
          </>
        )}
      </div>
      {stats.topValues.length > 0 && (
        <div>
          <div className="text-xs font-medium mb-1">Top values</div>
          <div className="space-y-0.5">
            {stats.topValues.slice(0, 5).map((v, i) => (
              <div key={i} className="flex justify-between text-xs">
                <span className="text-muted-foreground truncate max-w-[140px]">{v.value ?? 'NULL'}</span>
                <span className="ml-2 font-mono">{v.count.toLocaleString()}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Mini chart dispatcher
function ColumnMiniChart({
  stats,
}: {
  stats: ColumnStatsResponse | undefined;
}) {
  if (!stats) return <div style={{ height: 20 }} />;

  const dt = stats.dataType;

  let chart: React.ReactNode;
  if (dt === 'INTEGER' || dt === 'DECIMAL') {
    chart = <NumericMiniChart stats={stats} />;
  } else if (dt === 'TEXT' || dt === 'VARCHAR') {
    chart = <TextMiniChart stats={stats} />;
  } else if (dt === 'BOOLEAN') {
    chart = <BooleanMiniChart stats={stats} />;
  } else if (dt === 'DATE' || dt === 'TIMESTAMP') {
    chart = <DateMiniDisplay stats={stats} />;
  } else {
    chart = <div style={{ height: 20 }} />;
  }

  return (
    <Popover>
      <PopoverTrigger asChild>
        <div
          style={{ cursor: 'pointer', height: 20 }}
          title="클릭하여 통계 보기"
          onClick={(e) => e.stopPropagation()}
        >
          {chart}
        </div>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64">
        <ColumnStatsPopoverContent stats={stats} />
      </PopoverContent>
    </Popover>
  );
}

export const DatasetDataTab = React.memo(function DatasetDataTab({
  dataset,
  datasetId,
}: DatasetDataTabProps) {
  const [dataSearch, setDataSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [sort, setSort] = useState<{ by: string; dir: 'ASC' | 'DESC' } | null>(null);
  const [selectedRowIds, setSelectedRowIds] = useState<Set<number>>(new Set());
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});
  const [sqlEditorOpen, setSqlEditorOpen] = useState(false);
  const [addRowOpen, setAddRowOpen] = useState(false);
  const [editRowState, setEditRowState] = useState<{ open: boolean; rowId: number; data: Record<string, unknown> } | null>(null);

  const parentRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const resizeStateRef = useRef<{ colKey: string; startX: number; startWidth: number } | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const {
    data: infiniteData,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isLoading,
  } = useDatasetData(datasetId, {
    search: debouncedSearch || undefined,
    size: 50,
    sortBy: sort?.by,
    sortDir: sort?.dir,
  });

  const { data: columnStats } = useColumnStats(datasetId, dataset.rowCount > 0);

  const statsMap = useMemo(() => {
    const map = new Map<string, ColumnStatsResponse>();
    if (columnStats) {
      for (const s of columnStats) {
        map.set(s.columnName, s);
      }
    }
    return map;
  }, [columnStats]);

  const deleteRows = useDeleteDataRows(datasetId);

  // Debounce search
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(dataSearch);
    }, 300);
    return () => clearTimeout(timer);
  }, [dataSearch]);

  // Flatten all pages into a single rows array
  const allRows = useMemo(() => {
    if (!infiniteData) return [];
    return infiniteData.pages.flatMap((page) => page.rows);
  }, [infiniteData]);

  // Get columns from the first page
  const columns = useMemo(() => {
    return infiniteData?.pages[0]?.columns ?? [];
  }, [infiniteData]);

  // Get total count from first page (real totalElements)
  const totalElements = useMemo(() => {
    const first = infiniteData?.pages[0];
    if (!first) return 0;
    return first.totalElements >= 0 ? first.totalElements : allRows.length;
  }, [infiniteData, allRows.length]);

  // Initialize column widths based on container width and column count
  useEffect(() => {
    if (columns.length === 0) return;
    setColumnWidths((prev) => {
      const next: Record<string, number> = {};
      const container = containerRef.current;
      const containerWidth = container ? container.clientWidth : 800;
      // checkbox column is ~40px
      const availableWidth = containerWidth - 40;
      const perCol = Math.max(120, Math.floor(availableWidth / columns.length));
      for (const col of columns) {
        const key = col.columnName ?? String(col.id);
        next[key] = prev[key] ?? perCol;
      }
      return next;
    });
  }, [columns]);

  // Virtualizer
  const rowVirtualizer = useVirtualizer({
    count: allRows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 40,
    overscan: 10,
  });

  // IntersectionObserver to trigger fetchNextPage
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  // Column resize handlers
  const startResize = useCallback(
    (colKey: string, e: React.MouseEvent) => {
      e.preventDefault();
      const startX = e.clientX;
      const startWidth = columnWidths[colKey] ?? 120;
      resizeStateRef.current = { colKey, startX, startWidth };

      const onMouseMove = (moveEvent: MouseEvent) => {
        if (!resizeStateRef.current) return;
        const delta = moveEvent.clientX - resizeStateRef.current.startX;
        const newWidth = Math.min(800, Math.max(80, resizeStateRef.current.startWidth + delta));
        setColumnWidths((prev) => ({ ...prev, [resizeStateRef.current!.colKey]: newWidth }));
      };

      const onMouseUp = () => {
        resizeStateRef.current = null;
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
      };

      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
    },
    [columnWidths]
  );

  // Sort logic
  const handleSortClick = useCallback(
    (colName: string) => {
      setSort((prev) => {
        if (!prev || prev.by !== colName) return { by: colName, dir: 'ASC' };
        if (prev.dir === 'ASC') return { by: colName, dir: 'DESC' };
        return null;
      });
      setSelectedRowIds(new Set());
    },
    []
  );

  // Selection logic
  const handleSelectAll = useCallback(
    (checked: boolean) => {
      if (checked) {
        const newSet = new Set<number>();
        for (const row of allRows) {
          const id = row['_id'];
          if (typeof id === 'number') newSet.add(id);
        }
        setSelectedRowIds(newSet);
      } else {
        setSelectedRowIds(new Set());
      }
    },
    [allRows]
  );

  const handleSelectRow = useCallback((rowId: number, checked: boolean) => {
    setSelectedRowIds((prev) => {
      const next = new Set(prev);
      if (checked) next.add(rowId);
      else next.delete(rowId);
      return next;
    });
  }, []);

  const allLoaded = allRows.length;
  const allSelectedCount = selectedRowIds.size;
  const isAllSelected = allLoaded > 0 && allSelectedCount === allLoaded;
  const isIndeterminate = allSelectedCount > 0 && allSelectedCount < allLoaded;

  // Delete handler
  const handleDeleteConfirm = useCallback(() => {
    const ids = Array.from(selectedRowIds);
    deleteRows.mutate(ids, {
      onSuccess: () => {
        toast.success(`${ids.length}개 행이 삭제되었습니다.`);
        setSelectedRowIds(new Set());
        setDeleteDialogOpen(false);
      },
      onError: () => {
        toast.error('삭제에 실패했습니다.');
        setDeleteDialogOpen(false);
      },
    });
  }, [selectedRowIds, deleteRows]);

  // Export
  const handleExport = async () => {
    try {
      const response = await dataImportsApi.exportCsv(datasetId);
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${dataset?.tableName || 'export'}_export.csv`;
      a.click();
      window.URL.revokeObjectURL(url);
      toast.success('CSV 파일이 다운로드되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '내보내기에 실패했습니다.');
      } else {
        toast.error('내보내기에 실패했습니다.');
      }
    }
  };

  const getSortIcon = (colName: string) => {
    if (!sort || sort.by !== colName) return <ArrowUpDown size={14} className="text-muted-foreground" />;
    if (sort.dir === 'ASC') return <ArrowUp size={14} />;
    return <ArrowDown size={14} />;
  };

  const virtualItems = rowVirtualizer.getVirtualItems();
  const totalHeight = rowVirtualizer.getTotalSize();

  return (
    <div className="space-y-4">
      {/* Toolbar */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="text"
            placeholder="데이터 검색..."
            value={dataSearch}
            onChange={(e) => setDataSearch(e.target.value)}
            maxLength={200}
            className="pl-9"
          />
        </div>
        <Button
          variant={sqlEditorOpen ? 'default' : 'outline'}
          onClick={() => setSqlEditorOpen((prev) => !prev)}
        >
          <Terminal className="mr-2 h-4 w-4" />
          SQL
        </Button>
        <Button variant="outline" onClick={() => setAddRowOpen(true)}>
          <Plus className="mr-2 h-4 w-4" />
          행 추가
        </Button>
        <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
          <Upload className="mr-2 h-4 w-4" />
          임포트
        </Button>
        <Button variant="outline" onClick={handleExport}>
          <Download className="mr-2 h-4 w-4" />
          CSV 내보내기
        </Button>
      </div>

      {/* SQL Query Editor */}
      {sqlEditorOpen && (
        <SqlQueryEditor datasetId={datasetId} columns={dataset.columns} />
      )}

      {/* Total count */}
      <div className="flex items-center gap-4">
        <h2 className="text-lg font-semibold">
          데이터 ({totalElements.toLocaleString()}행)
        </h2>
      </div>

      {/* Selection action bar */}
      {selectedRowIds.size > 0 && (
        <div className="flex items-center gap-3 px-3 py-2 bg-muted rounded-md">
          <span className="text-sm font-medium">{selectedRowIds.size}개 행 선택됨</span>
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setDeleteDialogOpen(true)}
          >
            삭제
          </Button>
        </div>
      )}

      {/* Table area */}
      {isLoading ? (
        <Card className="p-8 flex items-center justify-center">
          <Loader2 className="animate-spin h-6 w-6 text-muted-foreground" />
        </Card>
      ) : columns.length > 0 && allRows.length > 0 ? (
        <div ref={containerRef} className="rounded-md border overflow-x-auto">
          {/* Scroll container for virtual rows */}
          <div
            ref={parentRef}
            style={{ maxHeight: 'calc(100vh - 300px)', overflow: 'auto' }}
          >
            <table
              style={{ tableLayout: 'fixed', width: '100%', borderCollapse: 'collapse' }}
            >
              <colgroup>
                {/* checkbox column */}
                <col style={{ width: 40 }} />
                {columns.map((col) => {
                  const key = col.columnName ?? String(col.id);
                  return <col key={key} style={{ width: columnWidths[key] ?? 120 }} />;
                })}
              </colgroup>
              <thead className="bg-muted/50 sticky top-0 z-10">
                <tr>
                  <th style={{ width: 40, padding: '8px', textAlign: 'center', borderBottom: '1px solid hsl(var(--border))' }}>
                    <Checkbox
                      checked={isAllSelected ? true : isIndeterminate ? 'indeterminate' : false}
                      onCheckedChange={(checked) => handleSelectAll(!!checked)}
                      aria-label="전체 선택"
                    />
                  </th>
                  {columns.map((col) => {
                    const key = col.columnName ?? String(col.id);
                    const label = col.displayName || col.columnName;
                    const colStats = statsMap.get(col.columnName ?? '');
                    return (
                      <th
                        key={key}
                        style={{
                          position: 'relative',
                          width: columnWidths[key] ?? 120,
                          padding: '6px 12px 4px',
                          textAlign: 'left',
                          fontSize: '0.875rem',
                          fontWeight: 600,
                          borderBottom: '1px solid hsl(var(--border))',
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          userSelect: 'none',
                        }}
                      >
                        <button
                          className="flex items-center gap-1 hover:text-foreground transition-colors w-full text-left"
                          onClick={() => handleSortClick(col.columnName ?? '')}
                          style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, fontSize: 'inherit', fontWeight: 'inherit' }}
                        >
                          <span className="truncate">{label}</span>
                          {getSortIcon(col.columnName ?? '')}
                        </button>
                        {/* Mini histogram */}
                        <div style={{ marginTop: 2, overflow: 'hidden' }}>
                          <ColumnMiniChart stats={colStats} />
                        </div>
                        {/* Resize handle */}
                        <div
                          style={{
                            position: 'absolute',
                            right: 0,
                            top: 0,
                            bottom: 0,
                            width: 4,
                            cursor: 'col-resize',
                          }}
                          className="hover:bg-blue-400 active:bg-blue-500"
                          onMouseDown={(e) => startResize(key, e)}
                        />
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {/* Virtual spacer top */}
                {virtualItems.length > 0 && virtualItems[0].start > 0 && (
                  <tr>
                    <td
                      colSpan={columns.length + 1}
                      style={{ height: virtualItems[0].start, padding: 0 }}
                    />
                  </tr>
                )}
                {virtualItems.map((virtualRow) => {
                  const row = allRows[virtualRow.index];
                  const rowId = row['_id'] as number;
                  const isSelected = selectedRowIds.has(rowId);
                  return (
                    <tr
                      key={virtualRow.key}
                      data-index={virtualRow.index}
                      ref={rowVirtualizer.measureElement}
                      style={{
                        height: virtualRow.size,
                        background: isSelected ? 'hsl(var(--accent))' : undefined,
                      }}
                      className="hover:bg-muted/50 transition-colors"
                      onDoubleClick={() => {
                        const rowData: Record<string, unknown> = {};
                        for (const col of columns) {
                          rowData[col.columnName] = row[col.columnName ?? ''];
                        }
                        setEditRowState({ open: true, rowId, data: rowData });
                      }}
                    >
                      <td
                        style={{
                          width: 40,
                          padding: '0 8px',
                          textAlign: 'center',
                          borderBottom: '1px solid hsl(var(--border))',
                          verticalAlign: 'middle',
                        }}
                        onDoubleClick={(e) => e.stopPropagation()}
                      >
                        <Checkbox
                          checked={isSelected}
                          onCheckedChange={(checked) => handleSelectRow(rowId, !!checked)}
                          aria-label={`행 ${virtualRow.index + 1} 선택`}
                        />
                      </td>
                      {columns.map((col) => {
                        const key = col.columnName ?? String(col.id);
                        const rawValue = row[col.columnName ?? ''];
                        const displayValue = formatCellValue(rawValue, col.dataType);
                        const isNull = isNullValue(rawValue);
                        return (
                          <td
                            key={key}
                            title={!isNull ? displayValue : undefined}
                            style={{
                              padding: '0 12px',
                              fontSize: '0.875rem',
                              borderBottom: '1px solid hsl(var(--border))',
                              verticalAlign: 'middle',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              cursor: 'pointer',
                              maxWidth: columnWidths[key] ?? 120,
                            }}
                            onClick={() => {
                              navigator.clipboard.writeText(getRawCellValue(rawValue)).then(() => {
                                toast.success('클립보드에 복사됨');
                              });
                            }}
                          >
                            {isNull ? (
                              <span className="text-muted-foreground italic text-xs">NULL</span>
                            ) : (
                              displayValue
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  );
                })}
                {/* Virtual spacer bottom */}
                {virtualItems.length > 0 && (() => {
                  const lastItem = virtualItems[virtualItems.length - 1];
                  const bottom = totalHeight - lastItem.end;
                  return bottom > 0 ? (
                    <tr>
                      <td
                        colSpan={columns.length + 1}
                        style={{ height: bottom, padding: 0 }}
                      />
                    </tr>
                  ) : null;
                })()}
              </tbody>
            </table>

            {/* Sentinel for infinite scroll */}
            <div ref={sentinelRef} style={{ height: 1 }} />

            {/* Loading indicator */}
            {isFetchingNextPage && (
              <div className="flex items-center justify-center py-4">
                <Loader2 className="animate-spin h-5 w-5 text-muted-foreground" />
              </div>
            )}
          </div>
        </div>
      ) : (
        <Card className="p-8">
          <p className="text-center text-muted-foreground">
            {dataSearch ? '검색 결과가 없습니다.' : '데이터가 없습니다.'}
          </p>
        </Card>
      )}

      {/* Delete confirmation dialog */}
      <AlertDialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>행 삭제 확인</AlertDialogTitle>
            <AlertDialogDescription>
              선택한 {selectedRowIds.size}개 행을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDeleteConfirm}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Import Dialog */}
      {importDialogOpen && (
        <Suspense fallback={null}>
          <ImportMappingDialog
            open={importDialogOpen}
            onOpenChange={setImportDialogOpen}
            datasetId={datasetId}
            datasetColumns={dataset.columns}
          />
        </Suspense>
      )}

      {/* Add Row Dialog */}
      <AddRowDialog
        open={addRowOpen}
        onOpenChange={setAddRowOpen}
        datasetId={datasetId}
        columns={dataset.columns}
      />

      {/* Edit Row Dialog */}
      {editRowState && (
        <EditRowDialog
          open={editRowState.open}
          onOpenChange={(open) => {
            if (!open) setEditRowState(null);
          }}
          datasetId={datasetId}
          columns={dataset.columns}
          rowId={editRowState.rowId}
          initialData={editRowState.data}
        />
      )}
    </div>
  );
});
