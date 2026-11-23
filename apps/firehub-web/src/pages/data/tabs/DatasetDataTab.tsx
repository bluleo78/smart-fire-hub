import { useVirtualizer } from '@tanstack/react-virtual';
import { ArrowDown, ArrowUp, ArrowUpDown, Loader2, Pencil } from 'lucide-react';
import React, { lazy, Suspense, useCallback,useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../../components/ui/alert-dialog';
import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { Checkbox } from '../../../components/ui/checkbox';
import { OnceAlertDialogAction } from '../../../components/ui/once-alert-dialog-action';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../../../components/ui/tooltip';
import { useDatasetData, useDatasetPermissions, useDeleteDataRows } from '../../../hooks/queries/useDatasets';
import { useColumnStatsMap } from '../../../hooks/useColumnStatsMap';
import { toCsvCell } from '../../../lib/csv';
import { formatCellValue, getRawCellValue,isNullValue } from '../../../lib/formatters';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { DataTableToolbar } from '../components/DataTableToolbar';
import { SelectionActionBar } from '../components/SelectionActionBar';
import { useColumnResize } from '../hooks/useColumnResize';
import { useInfiniteScrollSentinel } from '../hooks/useInfiniteScrollSentinel';
import { useRowSelection } from '../hooks/useRowSelection';

const ImportMappingDialog = lazy(() =>
  import('../components/ImportMappingDialog').then((m) => ({ default: m.ImportMappingDialog }))
);

const ApiImportWizard = lazy(() =>
  import('../components/ApiImportWizard').then((m) => ({ default: m.ApiImportWizard }))
);

const ExportDialog = lazy(() =>
  import('../components/ExportDialog').then((m) => ({ default: m.ExportDialog }))
);

import { AddRowDialog } from '../components/AddRowDialog';
import { ColumnMiniChart } from '../components/ColumnStats';
import { EditRowDialog } from '../components/EditRowDialog';
import { SqlQueryEditor } from '../components/SqlQueryEditor';

interface DatasetDataTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

// 고정 폭 열 — 선택 열(40px)과 쓰기 권한일 때만 붙는 작업 열(48px). col/th/td 와 열 폭 계산(reservedWidth)이 같은 값을 쓴다(WD-977).
const SELECT_COL_WIDTH = 40;
const ACTION_COL_WIDTH = 48;

/**
 * 행 끝 「행 N 수정」 버튼 + 툴팁.
 * 행 수정 창을 닫으면 Radix 가 초점을 이 버튼으로 되돌리는데, 그때 focus 이벤트로 툴팁이 매번 열리는 것은 의도가 아니다.
 * 그래서 창이 닫힌 직후(suppressRef 의 until 전)에 **창을 연 그 행**(rowId 일치) 버튼의 첫 "열림" 요청만 무시하고,
 * Tab 이동·마우스 올림·다른 행 버튼은 그대로 연다(WD-977 리뷰, WD-999).
 * 초점이 버튼으로 돌아오지 않는 경우(행 삭제 등)에도 억제가 남지 않도록 시각으로 1초만 유효하다.
 */
type EditTooltipSuppress = { rowId: number; until: number } | null;

function EditRowButton({ label, rowId, onEdit, suppressRef }: { label: string; rowId: number; onEdit: () => void; suppressRef: { current: EditTooltipSuppress } }) {
  const [open, setOpen] = useState(false);
  return (
    <Tooltip
      open={open}
      onOpenChange={(next) => {
        const s = suppressRef.current;
        if (next && s && s.rowId === rowId && Date.now() < s.until) {
          suppressRef.current = null;
          return;
        }
        setOpen(next);
      }}
    >
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon" className="h-7 w-7" aria-label={label} onClick={onEdit}
          // 마우스를 올리는 것은 초점 복귀가 아니므로 이 행의 억제를 먼저 풀어 첫 hover 가 무시되지 않게 한다.
          // 다른 행 버튼을 지나가는 것으로는 풀지 않는다 — 억제는 행마다라서(WD-999 코드 리뷰)
          onPointerEnter={() => { if (suppressRef.current?.rowId === rowId) suppressRef.current = null; }}>
          <Pencil className="h-4 w-4" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>행 수정</TooltipContent>
    </Tooltip>
  );
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
  const [editRowState, setEditRowState] = useState<{ open: boolean; rowId: number; data: Record<string, unknown>; fromButton: boolean } | null>(null);
  const [exportDialogOpen, setExportDialogOpen] = useState(false);
  // 조회자 기준 내보내기 가능(S4) — 서버가 명시적으로 true 를 준 경우만 허용(없거나 null 이면 fail-closed).
  const exportAllowed = dataset.exportAllowed === true;
  // 쓰기 진입점은 서버가 요구하는 권한별로 따로 연다 (WD-139) — 행 추가·행 수정(더블클릭)·임포트·SQL 실행은 data:import,
  // 선택 행 삭제는 data:delete, API 가져오기는 pipeline:write. 조회만 가능하면 검색·정렬·복사·내보내기만 남는다.
  const { canImportData, canDeleteData, canApiImport } = useDatasetPermissions();

  const parentRef = useRef<HTMLDivElement>(null);
  // 열 폭 훅이 요소가 바뀔 때(표가 사라졌다 다시 생김) 관찰을 갈아 끼우도록 스크롤 영역 요소를 상태로도 들고 있는다(WD-977 리뷰)
  const [scrollEl, setScrollEl] = useState<HTMLDivElement | null>(null);
  const setScrollRef = useCallback((node: HTMLDivElement | null) => {
    parentRef.current = node;
    setScrollEl(node);
  }, []);
  // 행 수정 창을 수정 버튼으로 열었다 닫은 직후, 초점 복귀로 그 행 버튼의 툴팁이 열리지 않게 하는 억제(행 id + 끝 시각 ms).
  // 행 id 를 같이 기억해 초점이 돌아오지 못한 경우에도 다른 행 버튼 툴팁은 막지 않는다(WD-977 리뷰, WD-999).
  const suppressEditTooltipRef = useRef<EditTooltipSuppress>(null);
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

  const statsMap = useColumnStatsMap(datasetId, (dataset.rowCount ?? 0) > 0);
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
    () => allRows.map((row) => row['id'] as number).filter((id) => typeof id === 'number'),
    [allRows]
  );

  const { columnWidths, startResize } = useColumnResize({
    columns,
    // 표가 실제로 놓이는 스크롤 영역 기준 — clientWidth 가 세로 스크롤바 폭을 뺀 값이라 클래식 스크롤바에서도 넘치지 않는다(WD-977)
    container: scrollEl,
    // 작업 열 폭까지 빼고 데이터 열에 나눠야 표가 가로로 넘치지 않는다. 권한이 늦게 오면 값이 바뀌어 다시 계산된다(WD-977)
    reservedWidth: SELECT_COL_WIDTH + (canImportData ? ACTION_COL_WIDTH : 0),
  });
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

  // 검색 필터가 변경되면 선택된 행을 초기화한다.
  // 이유: 검색 결과로 보이지 않게 된 행이 선택 상태로 남아 있으면 사용자가 인지하지 못한 채
  // "삭제"를 눌러 보이지 않는 행까지 삭제될 위험이 있다. 안전한 기본 동작으로 필터 변경 시
  // 전체 선택을 해제한다 (정렬 변경은 handleSortClick에서 이미 clearSelection 호출).
  useEffect(() => {
    clearSelection();
  }, [debouncedSearch, clearSelection]);

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

  // 선택된 행을 클라이언트에서 CSV로 변환해 다운로드한다.
  // 이유: 백엔드 다운로드 엔드포인트는 전체/검색 결과 단위라 "선택된 N개"만 추출하는
  // 경로가 없다. 이미 메모리에 있는 행을 RFC 4180 규칙(쌍따옴표/콤마/개행 escape)에 맞춰
  // 직렬화하고 Blob으로 즉시 다운로드한다.
  const handleExportSelected = useCallback(() => {
    if (selectedRowIds.size === 0) return;
    const selectedRows = allRows.filter((row) => selectedRowIds.has(row['id'] as number));
    if (selectedRows.length === 0) {
      toast.error('선택된 행을 찾을 수 없습니다.');
      return;
    }

    // CSV 셀 직렬화는 공용 toCsvCell — RFC 4180 따옴표 처리 + 문자열 값(표시명 헤더 포함)의 수식 주입 중화
    // (OWASP CSV Injection, WD-156). 숫자 값은 원형 유지, 객체(GeoJSON 등)는 JSON 문자열.
    const headerRow = columns.map((col) => toCsvCell(col.displayName || col.columnName)).join(',');
    const dataRows = selectedRows.map((row) =>
      columns.map((col) => toCsvCell(row[col.columnName ?? ''])).join(',')
    );
    // BOM 추가로 Excel에서 UTF-8 한글 깨짐 방지
    const csv = '﻿' + [headerRow, ...dataRows].join('\r\n');

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    link.href = url;
    link.download = `${dataset.name || 'dataset'}_selected_${ts}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    toast.success(`${selectedRows.length}개 행을 CSV로 내보냈습니다.`);
  }, [selectedRowIds, allRows, columns, dataset.name]);

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

  const getSortIcon = (colName: string) => {
    if (!sort || sort.by !== colName) return <ArrowUpDown size={14} className="text-muted-foreground" />;
    if (sort.dir === 'ASC') return <ArrowUp size={14} />;
    return <ArrowDown size={14} />;
  };

  /**
   * 머리글 th 의 aria-sort — 정렬 상태를 아이콘으로만 보이면 화면 읽기 프로그램이 알 수 없다(WD-337).
   * 공용 SortableHeader(#80)와 같은 값(ascending·descending·none)을 쓴다. 이 표는 크기 조절·미니 차트 때문에 머리글을 직접 그린다.
   */
  const getAriaSort = (colName: string): 'ascending' | 'descending' | 'none' => {
    if (!sort || sort.by !== colName) return 'none';
    return sort.dir === 'ASC' ? 'ascending' : 'descending';
  };

  /**
   * 행 수정 창을 연다 — 더블클릭(마우스 지름길)과 행 끝 수정 버튼(키보드 진입점, WD-977)이 같은 함수를 쓴다.
   * 컬럼 순서대로 값을 모아 EditRowDialog 에 넘긴다.
   */
  const openEditRow = (row: Record<string, unknown>, fromButton = false) => {
    const rowData: Record<string, unknown> = {};
    for (const col of columns) rowData[col.columnName] = row[col.columnName ?? ''];
    // fromButton 은 창이 실제로 열릴 때 함께 저장한다 — 열지 못한 채 남는 표시가 나중 창에 새지 않게(WD-999)
    setEditRowState({ open: true, rowId: row['id'] as number, data: rowData, fromButton });
  };

  // 열 수 = 선택 열 1 + 데이터 열 + (쓰기 권한 시) 작업 열 1 — spacer 행 colSpan 이 표 전체 폭을 덮도록 맞춘다
  const totalColSpan = columns.length + 1 + (canImportData ? 1 : 0);

  const virtualItems = rowVirtualizer.getVirtualItems();
  const totalHeight = rowVirtualizer.getTotalSize();

  return (
    /*
     * 뷰포트를 꽉 채우는 flex 컬럼 — 남는 높이를 전부 테이블에 준다.
     * 이전에는 테이블 스크롤러 높이를 `calc(100vh - 300px)` 매직넘버로 잡았는데, 위쪽 크롬
     * (브레드크럼+타이틀+파이프라인 칩+탭바+툴바)의 실제 높이보다 88px 작아서 main이 88px 넘쳤다.
     * 그 결과 테이블(1636px)과 main(88px)이 동시에 스크롤되는 이중 스크롤이 됐다 —
     * 테이블 끝에 닿으면 페이지가 딸려 움직여 헤더가 흔들렸다.
     * 고정 요소는 shrink-0으로 못 박는다. 그러지 않으면 음수 공간이 basis:auto인 이쪽으로 몰려
     * 툴바가 찌그러진다(테이블은 basis:0이라 덜 줄어든다).
     */
    <div className="flex h-full min-h-0 flex-col gap-4">
      <DataTableToolbar
        dataSearch={dataSearch}
        onSearchChange={setDataSearch}
        sqlEditorOpen={sqlEditorOpen}
        onToggleSqlEditor={canImportData ? () => setSqlEditorOpen((prev) => !prev) : undefined}
        onAddRow={canImportData ? () => setAddRowOpen(true) : undefined}
        onImport={canImportData ? () => setImportDialogOpen(true) : undefined}
        onExport={() => setExportDialogOpen(true)}
        onApiImport={canApiImport ? () => setApiWizardOpen(true) : undefined}
        exportAllowed={exportAllowed}
      />

      {sqlEditorOpen && canImportData && (
        <SqlQueryEditor datasetId={datasetId} columns={dataset.columns} />
      )}

      <div className="flex shrink-0 items-center gap-4">
        <h2 className="text-xl leading-7 font-semibold">데이터 ({totalElements.toLocaleString()}행)</h2>
      </div>

      <SelectionActionBar
        selectedCount={selectedCount}
        onDeleteSelected={canDeleteData ? () => setDeleteDialogOpen(true) : undefined}
        // 보조 다운로드(선택 행 CSV)는 차단이면 숨긴다 — 주 버튼만 비활성+툴팁으로 사유를 안내한다(스펙 §5-4)
        onExportSelected={exportAllowed ? handleExportSelected : undefined}
        onClearSelection={clearSelection}
      />

      {isLoading ? (
        <Card className="p-8 flex items-center justify-center">
          <Loader2 className="animate-spin h-6 w-6 text-muted-foreground" />
        </Card>
      ) : columns.length > 0 && allRows.length > 0 ? (
        /* 바깥은 테두리·클리핑만 담당한다. 이전의 `overflow-x-auto`는 안쪽 div가 100% 폭 블록이라
           절대 발동하지 않는 죽은 코드였다(테이블을 3000px로 강제해도 outerOverflowX=0).
           min-h-80(20rem): 남는 높이만 받는 구조라 모바일처럼 위쪽 머리 영역이 화면을 거의 차지하면 표가 0 근처로
           줄었다(WD-336). 표에 최소 높이를 두어, 공간이 모자랄 때는 main 이 스크롤돼 머리 영역이 위로 지나가고
           표는 행 몇 줄 이상을 늘 보여 준다. 넉넉한 화면(데스크톱)에서는 남는 높이가 이보다 커서 지금처럼 main 이
           넘치지 않는다(이중 스크롤 없음). */
        <div ref={containerRef} className="flex min-h-80 flex-1 flex-col overflow-hidden rounded-md border">
          {/*
            scroll-pt-14(56px): sticky thead 뒤로 행이 숨지 않도록(가상 스크롤의 scrollToIndex 포함).
            이 표의 헤더는 정렬 버튼 + 값 분포 바가 겹쳐 실측 54px다 — 다른 표(약 36px)보다 두꺼워서
            일괄 40px로는 13px이 모자랐다. E2E가 "행이 헤더에 가리지 않는다"를 직접 단언하므로,
            헤더 구성이 바뀌어 높이가 달라지면 이 값이 부족해지는 즉시 red가 된다.
          */}
          <div ref={setScrollRef} className="min-h-0 flex-1 overflow-auto overscroll-contain scroll-pt-14">
            {/* 행 수정 버튼 툴팁용 Provider 는 표 전체에 한 번만 — AppLayout 의 Provider 는 사이드바만 감싸 본문에는 닿지 않는다 */}
            <TooltipProvider>
            <table style={{ tableLayout: 'fixed', width: '100%', borderCollapse: 'collapse' }}>
              <colgroup>
                <col style={{ width: SELECT_COL_WIDTH }} />
                {columns.map((col) => {
                  const key = col.columnName ?? String(col.id);
                  return <col key={key} style={{ width: columnWidths[key] ?? 120 }} />;
                })}
                {/* 작업 열(행 수정 버튼) — 쓰기 권한이 있을 때만 (WD-977, WD-139) */}
                {canImportData && <col style={{ width: ACTION_COL_WIDTH }} />}
              </colgroup>
              <thead className="bg-muted sticky top-0 z-10">
                <tr>
                  <th style={{ width: SELECT_COL_WIDTH, padding: '8px', textAlign: 'center', borderBottom: '1px solid var(--border)' }}>
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
                        aria-sort={getAriaSort(col.columnName ?? '')}
                        style={{
                          position: 'relative',
                          width: columnWidths[key] ?? 120,
                          padding: '6px 12px 4px',
                          textAlign: 'left',
                          fontSize: '0.875rem',
                          fontWeight: 600,
                          borderBottom: '1px solid var(--border)',
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
                          className="hover:bg-primary/80 active:bg-primary/90"
                          onMouseDown={(e) => startResize(key, e)}
                        />
                      </th>
                    );
                  })}
                  {canImportData && (
                    <th style={{ width: ACTION_COL_WIDTH, padding: '8px', borderBottom: '1px solid var(--border)' }}>
                      <span className="sr-only">작업</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {virtualItems.length > 0 && virtualItems[0].start > 0 && (
                  <tr>
                    <td colSpan={totalColSpan} style={{ height: virtualItems[0].start, padding: 0 }} />
                  </tr>
                )}
                {virtualItems.map((virtualRow) => {
                  const row = allRows[virtualRow.index];
                  const rowId = row['id'] as number;
                  const isSelected = selectedRowIds.has(rowId);
                  return (
                    <tr
                      key={virtualRow.key}
                      data-index={virtualRow.index}
                      ref={rowVirtualizer.measureElement}
                      style={{ height: virtualRow.size, background: isSelected ? 'var(--accent)' : undefined }}
                      className="hover:bg-muted/50 transition-colors"
                      // 더블클릭 행 수정(PUT 행)은 data:import — 권한이 없으면 핸들러째 두지 않는다 (WD-139)
                      onDoubleClick={canImportData ? () => openEditRow(row) : undefined}
                    >
                      <td
                        style={{ width: SELECT_COL_WIDTH, padding: '0 8px', textAlign: 'center', borderBottom: '1px solid var(--divider)', verticalAlign: 'middle' }}
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
                              // WD-755: 가상 스크롤 행 아래 선은 행 구분선(divider) — 머리(th) 아래만 외곽 border
                              borderBottom: '1px solid var(--divider)',
                              verticalAlign: 'middle',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              cursor: 'pointer',
                              maxWidth: columnWidths[key] ?? 120,
                            }}
                            onClick={() => {
                              navigator.clipboard.writeText(getRawCellValue(rawValue)).then(() => {
                                toast.success('클립보드에 복사됨.');
                              });
                            }}
                          >
                            {isNull ? (
                              // null 값은 시각적으로 구분되는 dash로 표시 (빈 셀과 달리 null임을 명시)
                              <span className="text-muted-foreground italic text-xs select-none">-</span>
                            ) : (
                              displayValue
                            )}
                          </td>
                        );
                      })}
                      {canImportData && (
                        // 키보드 진입점 — <tr> 에 role·tabIndex 를 주지 않고 행 끝 버튼으로 연다(WD-977, WD-816).
                        // 닫으면 Radix FocusScope 가 열기 전 포커스(이 버튼)로 되돌린다.
                        <td
                          style={{ width: ACTION_COL_WIDTH, padding: '0 8px', textAlign: 'center', borderBottom: '1px solid var(--divider)', verticalAlign: 'middle' }}
                          onDoubleClick={(e) => e.stopPropagation()}
                        >
                          <EditRowButton
                            label={`행 ${virtualRow.index + 1} 수정`}
                            rowId={rowId}
                            onEdit={() => openEditRow(row, true)}
                            suppressRef={suppressEditTooltipRef}
                          />
                        </td>
                      )}
                    </tr>
                  );
                })}
                {virtualItems.length > 0 && (() => {
                  const lastItem = virtualItems[virtualItems.length - 1];
                  const bottom = totalHeight - lastItem.end;
                  return bottom > 0 ? (
                    <tr>
                      <td colSpan={totalColSpan} style={{ height: bottom, padding: 0 }} />
                    </tr>
                  ) : null;
                })()}
              </tbody>
            </table>
            </TooltipProvider>

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
            {/* WD-13: 색 className 흉내 대신 variant — 다크 모드·호버 토큰이 Button destructive 와 같아진다 */}
            {/* WD-522: 더블클릭해도 행 삭제 요청은 한 번만 — 창이 열린 동안 확인을 한 번만 받는다 */}
            <OnceAlertDialogAction variant="destructive" onClick={handleDeleteConfirm}>
              삭제
            </OnceAlertDialogAction>
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
          onOpenChange={(open) => {
            if (!open) {
              // 버튼으로 연 창만 초점이 그 행 버튼으로 돌아오므로 그때만, 그 행에 한해 툴팁 억제를 건다.
              // id 가 없는 행은 억제하지 않는다 — undefined 끼리 같다고 보면 모든 행 버튼에 억제가 번진다(WD-999 코드 리뷰)
              if (editRowState.fromButton && editRowState.rowId != null) suppressEditTooltipRef.current = { rowId: editRowState.rowId, until: Date.now() + 1000 };
              setEditRowState(null);
            }
          }}
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

      {/* 차단이면 다이얼로그 자체를 열지 않는다 — 버튼 비활성과 이중 방어(서버도 다시 판정) */}
      {exportDialogOpen && exportAllowed && (
        <Suspense fallback={null}>
          <ExportDialog
            open={exportDialogOpen}
            onOpenChange={setExportDialogOpen}
            datasetId={datasetId}
            datasetName={dataset.name}
            search={debouncedSearch || undefined}
          />
        </Suspense>
      )}
    </div>
  );
});
