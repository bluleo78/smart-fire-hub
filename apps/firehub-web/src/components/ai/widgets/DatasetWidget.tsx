import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronsUpDown,ChevronUp } from 'lucide-react';
import { useMemo, useState } from 'react';

import { datasetsApi } from '../../../api/datasets';
import { downloadBlob, downloadCsv } from '../../../lib/download';
import { ActiveFilterChips } from './table/ActiveFilterChips';
import { CellRenderer } from './table/CellRenderer';
import { ColumnFilterDropdown } from './table/ColumnFilterDropdown';
import { ExportDropdown } from './table/ExportDropdown';
import { Pagination } from './table/Pagination';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface ShowDatasetInput {
  datasetId: number;
}

const DATASET_TYPE_LABEL: Record<string, string> = {
  SOURCE: '원본',
  DERIVED: '파생',
  TEMP: '임시',
};

const PAGE_SIZE = 20;

function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
}

function formatRowCount(count: number): string {
  return count.toLocaleString('ko-KR');
}

export default function DatasetWidget({ input, onNavigate, displayMode }: WidgetProps<ShowDatasetInput>) {
  const datasetId = Number(input.datasetId);

  const [filters, setFilters] = useState<Record<string, string[]>>({});
  const [sortBy, setSortBy] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const [page, setPage] = useState(0);

  const { data: dataset, isLoading: metaLoading, isError: metaError } = useQuery({
    queryKey: ['dataset', datasetId],
    queryFn: () => datasetsApi.getDatasetById(datasetId).then(r => r.data),
    staleTime: 30_000,
    enabled: !!datasetId,
  });

  const { data: dataResult, isLoading: dataLoading } = useQuery({
    queryKey: ['dataset-data', datasetId, { sortBy, sortDir, page, size: PAGE_SIZE }],
    queryFn: () => datasetsApi.getDatasetData(datasetId, {
      page,
      size: PAGE_SIZE,
      ...(sortBy ? { sortBy, sortDir } : {}),
      includeTotalCount: true,
    }).then(r => r.data),
    staleTime: 15_000,
    enabled: !!datasetId,
  });

  const isLoading = metaLoading;

  const maxCols = displayMode === 'fullscreen' ? 10 : 4;
  const columns = dataset?.columns ?? [];
  const visibleCols = columns.slice(0, maxCols);
  const hiddenColCount = Math.max(0, columns.length - maxCols);
  const rawRows = dataResult?.rows ?? [];
  const totalElements = dataResult?.totalElements ?? dataset?.rowCount ?? 0;
  const pageCount = Math.ceil(totalElements / PAGE_SIZE);

  // Unique values per visible column (from current page's rawRows)
  const uniqueValuesByCol = useMemo(() => {
    const map: Record<string, string[]> = {};
    for (const col of visibleCols) {
      const seen = new Set<string>();
      for (const row of rawRows) {
        const val = row[col.columnName];
        if (val != null) seen.add(String(val));
      }
      map[col.columnName] = Array.from(seen);
    }
    return map;
  }, [rawRows, visibleCols]);

  // Client-side column filtering on current page
  const rows = useMemo(() => {
    return rawRows.filter(row =>
      Object.entries(filters).every(([col, values]) => {
        if (!values || values.length === 0) return true;
        const cell = row[col];
        return cell != null && values.includes(String(cell));
      }),
    );
  }, [rawRows, filters]);

  function toggleSort(colName: string) {
    if (sortBy === colName) {
      if (sortDir === 'asc') {
        setSortDir('desc');
      } else {
        setSortBy(null);
        setSortDir('asc');
      }
    } else {
      setSortBy(colName);
      setSortDir('asc');
    }
    setPage(0);
  }

  function handleFilterChange(col: string, values: string[]) {
    setFilters(prev => ({ ...prev, [col]: values }));
  }

  function handleFilterRemove(col: string, value: string) {
    setFilters(prev => {
      const next = { ...prev };
      next[col] = (next[col] ?? []).filter(v => v !== value);
      if (next[col].length === 0) delete next[col];
      return next;
    });
  }

  function handleClearAllFilters() {
    setFilters({});
  }

  function handleExport(format: 'csv' | 'json') {
    const datasetName = dataset?.name ?? 'dataset';
    if (format === 'json') {
      const blob = new Blob([JSON.stringify(rows, null, 2)], { type: 'application/json' });
      downloadBlob(`${datasetName}.json`, blob);
    } else {
      const colNames = visibleCols.map(c => c.columnName);
      const header = colNames.join(',');
      const body = rows.map(row =>
        colNames.map(col => {
          const val = row[col];
          if (val == null) return '';
          const str = String(val);
          return str.includes(',') || str.includes('"') || str.includes('\n')
            ? `"${str.replace(/"/g, '""')}"`
            : str;
        }).join(','),
      );
      downloadCsv(`${datasetName}.csv`, '\uFEFF' + [header, ...body].join('\n'));
    }
  }

  if (isLoading) {
    return (
      <WidgetShell title="데이터셋 불러오는 중..." icon="📦" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">로딩 중...</div>
      </WidgetShell>
    );
  }

  if (metaError || !dataset) {
    return (
      <WidgetShell title="데이터셋을 찾을 수 없음" icon="📦" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-destructive">데이터셋을 불러올 수 없습니다.</div>
      </WidgetShell>
    );
  }

  return (
    <WidgetShell
      title={dataset.name}
      icon="📦"
      subtitle={DATASET_TYPE_LABEL[dataset.datasetType]}
      navigateTo={`/data/datasets/${dataset.id}`}
      onNavigate={onNavigate}
      displayMode={displayMode}
      actions={<ExportDropdown onExport={handleExport} />}
    >
      {/* Meta row — pill chips */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-1.5">
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
          📐 {columns.length}개 컬럼
        </span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
          📋 {formatRowCount(dataset.rowCount)}건
        </span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
          🕒 {formatDate(dataset.updatedAt)}
        </span>
      </div>

      {/* Active filter chips */}
      <ActiveFilterChips
        filters={filters}
        onRemove={handleFilterRemove}
        onClearAll={handleClearAllFilters}
      />

      {/* Data table */}
      {visibleCols.length > 0 ? (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                {visibleCols.map(col => (
                  <th
                    key={col.id}
                    className="whitespace-nowrap px-3 py-1.5 text-left font-medium text-muted-foreground"
                  >
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        className="flex items-center gap-1 cursor-pointer select-none hover:text-foreground"
                        onClick={() => toggleSort(col.columnName)}
                      >
                        <span className="truncate">{col.displayName || col.columnName}</span>
                        {sortBy === col.columnName ? (
                          sortDir === 'asc'
                            ? <ChevronUp className="h-3 w-3 shrink-0 text-primary" />
                            : <ChevronDown className="h-3 w-3 shrink-0 text-primary" />
                        ) : (
                          <ChevronsUpDown className="h-3 w-3 shrink-0 opacity-30" />
                        )}
                      </button>
                      <ColumnFilterDropdown
                        columnName={col.displayName || col.columnName}
                        uniqueValues={uniqueValuesByCol[col.columnName] ?? []}
                        selectedValues={filters[col.columnName] ?? []}
                        onFilterChange={values => handleFilterChange(col.columnName, values)}
                      />
                    </div>
                  </th>
                ))}
                {hiddenColCount > 0 && (
                  <th className="whitespace-nowrap px-3 py-1.5 text-left font-medium text-muted-foreground">
                    +{hiddenColCount}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {dataLoading ? (
                <tr>
                  <td colSpan={visibleCols.length + (hiddenColCount > 0 ? 1 : 0)} className="px-3 py-4 text-center text-muted-foreground">
                    로딩 중...
                  </td>
                </tr>
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={visibleCols.length + (hiddenColCount > 0 ? 1 : 0)} className="px-3 py-4 text-center text-muted-foreground">
                    데이터가 없습니다.
                  </td>
                </tr>
              ) : (
                rows.map((row, rowIdx) => (
                  <tr
                    key={rowIdx}
                    className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors duration-150"
                  >
                    {visibleCols.map(col => (
                      <td key={col.id} className="max-w-[160px] truncate px-3 py-2">
                        <CellRenderer value={row[col.columnName]} />
                      </td>
                    ))}
                    {hiddenColCount > 0 && <td className="px-3 py-2 text-muted-foreground">…</td>}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="px-3 py-4 text-center text-xs text-muted-foreground">컬럼이 없습니다.</div>
      )}

      {/* Pagination */}
      <Pagination
        currentPage={page}
        totalPages={pageCount}
        totalItems={totalElements}
        pageSize={PAGE_SIZE}
        onPageChange={setPage}
      />
    </WidgetShell>
  );
}
