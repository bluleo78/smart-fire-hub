import React, { useMemo, lazy, Suspense, useRef, useEffect, useState, useCallback } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useDatasetData, useDeleteDataRows } from '../../../hooks/queries/useDatasets';
import { dataImportsApi } from '../../../api/dataImports';
import type { DatasetDetailResponse } from '../../../types/dataset';
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
import { ArrowUpDown, ArrowUp, ArrowDown, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import { formatCellValue, isNullValue, getRawCellValue } from '../../../lib/formatters';
import { handleApiError } from '../../../lib/api-error';
import { downloadBlob } from '../../../lib/download';
import { useColumnStatsMap } from '../../../hooks/useColumnStatsMap';
import { useColumnResize } from '../hooks/useColumnResize';
import { useInfiniteScrollSentinel } from '../hooks/useInfiniteScrollSentinel';
import { useRowSelection } from '../hooks/useRowSelection';
import { DataTableToolbar } from '../components/DataTableToolbar';
import { SelectionActionBar } from '../components/SelectionActionBar';

const ImportMappingDialog = lazy(() =>
  import('../components/ImportMappingDialog').then((m) => ({ default: m.ImportMappingDialog }))
);

const ApiImportWizard = lazy(() =>
  import('../components/ApiImportWizard').then((m) => ({ default: m.ApiImportWizard }))
);

import { SqlQueryEditor } from '../components/SqlQueryEditor';
import { AddRowDialog } from '../components/AddRowDialog';
import { EditRowDialog } from '../components/EditRowDialog';
import { ColumnMiniChart } from '../components/ColumnStats';

interface DatasetDataTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

export const DatasetDataTab = React.memo(function DatasetDataTab({
  dataset,
  datasetId,
}: DatasetDataTabProps) {
  const [dataSearch, setDataSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [apiWizardOpen, setApiWizardOpen] = useState(false);
  const [sort, setSort] = useState<{ by: string; dir: 'ASC' | 'DESC' } | null>(null);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [sqlEditorOpen, setSqlEditorOpen] = useState(false);
  const [addRowOpen, setAddRowOpen] = useState(false);
  const [editRowState, setEditRowState] = useState<{ open: boolean; rowId: number; data: Record<string, unknown> } | null>(null);

  const parentRef = useRef<HTMLDivElement>(null);
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

  const statsMap = useColumnStatsMap(datasetId, dataset.rowCount > 0);
  const deleteRows = useDeleteDataRows(datasetId);

  // Debounce search
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(dataSearch), 300);
    return () => clearTimeout(timer);
  }, [dataSearch]);

  const allRows = useMemo(() => {
    if (!infiniteData) return [];
    return infiniteData.pages.flatMap((page) => page.rows);
  }, [infiniteData]);

  const columns = useMemo(() => infiniteData?.pages[0]?.columns ?? [], [infiniteData]);

  const totalElements = useMemo(() => {
    const first = infiniteData?.pages[0];
    if (!first) return 0;
    return first.totalElements >= 0 ? first.totalElements : allRows.length;
  }, [infiniteData, allRows.length]);

  const allRowIds = useMemo(
    () => allRows.map((row) => row['_id'] as number).filter((id) => typeof id === 'number'),
    [allRows]
  );

  const { columnWidths, startResize } = useColumnResize({ columns, containerRef });
  const { sentinelRef } = useInfiniteScrollSentinel({ hasNextPage, isFetchingNextPage, fetchNextPage });
  const {
    selectedRowIds,
    setSelectedRowIds,
    handleSelectAll,
    handleSelectRow,
    isAllSelected,
    isIndeterminate,
    selectedCount,
    clearSelection,
  } = useRowSelection(allRowIds);

  const rowVirtualizer = useVirtualizer({
    count: allRows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 40,
    overscan: 10,
  });

  const handleSortClick = useCallback((colName: string) => {
    setSort((prev) => {
      if (!prev || prev.by !== colName) return { by: colName, dir: 'ASC' };
      if (prev.dir === 'ASC') return { by: colName, dir: 'DESC' };
      return null;
    });
    clearSelection();
  }, [clearSelection]);

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
  }, [selectedRowIds, deleteRows, setSelectedRowIds]);

  const handleExport = async () => {
    try {
      const response = await dataImportsApi.exportCsv(datasetId);
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' });
      downloadBlob(`${dataset?.tableName || 'export'}_export.csv`, blob);
      toast.success('CSV 파일이 다운로드되었습니다.');
    } catch (error) {
      handleApiError(error, '내보내기에 실패했습니다.');
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
      <DataTableToolbar
        dataSearch={dataSearch}
        onSearchChange={setDataSearch}
        sqlEditorOpen={sqlEditorOpen}
        onToggleSqlEditor={() => setSqlEditorOpen((prev) => !prev)}
        onAddRow={() => setAddRowOpen(true)}
        onImport={() => setImportDialogOpen(true)}
        onExport={handleExport}
        onApiImport={() => setApiWizardOpen(true)}
      />

      {sqlEditorOpen && (
        <SqlQueryEditor datasetId={datasetId} columns={dataset.columns} />
      )}

      <div className="flex items-center gap-4">
        <h2 className="text-lg font-semibold">데이터 ({totalElements.toLocaleString()}행)</h2>
      </div>

      <SelectionActionBar
        selectedCount={selectedCount}
        onDeleteSelected={() => setDeleteDialogOpen(true)}
      />

      {isLoading ? (
        <Card className="p-8 flex items-center justify-center">
          <Loader2 className="animate-spin h-6 w-6 text-muted-foreground" />
        </Card>
      ) : columns.length > 0 && allRows.length > 0 ? (
        <div ref={containerRef} className="rounded-md border overflow-x-auto">
          <div ref={parentRef} style={{ maxHeight: 'calc(100vh - 300px)', overflow: 'auto' }}>
            <table style={{ tableLayout: 'fixed', width: '100%', borderCollapse: 'collapse' }}>
              <colgroup>
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
                        <div style={{ marginTop: 2, overflow: 'hidden' }}>
                          <ColumnMiniChart stats={colStats} />
                        </div>
                        <div
                          style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 4, cursor: 'col-resize' }}
                          className="hover:bg-blue-400 active:bg-blue-500"
                          onMouseDown={(e) => startResize(key, e)}
                        />
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {virtualItems.length > 0 && virtualItems[0].start > 0 && (
                  <tr>
                    <td colSpan={columns.length + 1} style={{ height: virtualItems[0].start, padding: 0 }} />
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
                      style={{ height: virtualRow.size, background: isSelected ? 'hsl(var(--accent))' : undefined }}
                      className="hover:bg-muted/50 transition-colors"
                      onDoubleClick={() => {
                        const rowData: Record<string, unknown> = {};
                        for (const col of columns) rowData[col.columnName] = row[col.columnName ?? ''];
                        setEditRowState({ open: true, rowId, data: rowData });
                      }}
                    >
                      <td
                        style={{ width: 40, padding: '0 8px', textAlign: 'center', borderBottom: '1px solid hsl(var(--border))', verticalAlign: 'middle' }}
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
                {virtualItems.length > 0 && (() => {
                  const lastItem = virtualItems[virtualItems.length - 1];
                  const bottom = totalHeight - lastItem.end;
                  return bottom > 0 ? (
                    <tr>
                      <td colSpan={columns.length + 1} style={{ height: bottom, padding: 0 }} />
                    </tr>
                  ) : null;
                })()}
              </tbody>
            </table>

            <div ref={sentinelRef} style={{ height: 1 }} />

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

      <AddRowDialog
        open={addRowOpen}
        onOpenChange={setAddRowOpen}
        datasetId={datasetId}
        columns={dataset.columns}
      />

      {editRowState && (
        <EditRowDialog
          open={editRowState.open}
          onOpenChange={(open) => { if (!open) setEditRowState(null); }}
          datasetId={datasetId}
          columns={dataset.columns}
          rowId={editRowState.rowId}
          initialData={editRowState.data}
        />
      )}

      {apiWizardOpen && (
        <Suspense fallback={null}>
          <ApiImportWizard
            open={apiWizardOpen}
            onOpenChange={setApiWizardOpen}
            datasetId={datasetId}
            datasetName={dataset.name}
            datasetColumns={dataset.columns}
          />
        </Suspense>
      )}
    </div>
  );
});
