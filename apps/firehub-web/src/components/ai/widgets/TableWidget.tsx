import { useState, useMemo } from 'react';
import { ChevronUp, ChevronDown, ChevronsUpDown, Code2 } from 'lucide-react';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { downloadBlob, downloadCsv } from '../../../lib/download';
import { WidgetShell } from './WidgetShell';
import type { WidgetProps } from './types';
import { CellRenderer } from './table/CellRenderer';
import { ActiveFilterChips } from './table/ActiveFilterChips';
import { ColumnFilterDropdown } from './table/ColumnFilterDropdown';
import { Pagination } from './table/Pagination';
import { ExportDropdown } from './table/ExportDropdown';

SyntaxHighlighter.registerLanguage('sql', sql);

const PAGE_SIZE = 50;

interface ShowTableInput {
  title?: string;
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows?: number;
}

type SortDir = 'asc' | 'desc' | null;

function cellText(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value);
}

function escapeCsv(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n') || value.includes('\r')) {
    return '"' + value.replace(/"/g, '""') + '"';
  }
  return value;
}

export default function TableWidget({ input, onNavigate, displayMode }: WidgetProps<ShowTableInput>) {
  const columns = input.columns ?? [];
  const rows = input.rows ?? [];
  const title = input.title ?? '쿼리 결과';
  const totalRows = input.totalRows ?? rows.length;

  const [sqlOpen, setSqlOpen] = useState(false);
  const [filters, setFilters] = useState<Record<string, string[]>>({});
  const [sortCol, setSortCol] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<SortDir>(null);
  const [page, setPage] = useState(0);

  // Compute unique values per column from full dataset
  const uniqueValues = useMemo(() => {
    const result: Record<string, string[]> = {};
    for (const col of columns) {
      const seen = new Set<string>();
      for (const row of rows) {
        const v = cellText(row[col]);
        if (v !== '') seen.add(v);
      }
      result[col] = Array.from(seen);
    }
    return result;
  }, [rows, columns]);

  const filteredRows = useMemo(() => {
    let result = rows;
    for (const col of columns) {
      const selectedValues = filters[col];
      if (selectedValues && selectedValues.length > 0) {
        result = result.filter((row) => selectedValues.includes(cellText(row[col])));
      }
    }
    return result;
  }, [rows, columns, filters]);

  const sortedRows = useMemo(() => {
    if (!sortCol || !sortDir) return filteredRows;
    return [...filteredRows].sort((a, b) => {
      const av = cellText(a[sortCol]);
      const bv = cellText(b[sortCol]);
      const cmp = av.localeCompare(bv, undefined, { numeric: true, sensitivity: 'base' });
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [filteredRows, sortCol, sortDir]);

  const totalPages = Math.max(1, Math.ceil(sortedRows.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const pageRows = sortedRows.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE);

  function handleSort(col: string) {
    if (sortCol !== col) {
      setSortCol(col);
      setSortDir('asc');
    } else if (sortDir === 'asc') {
      setSortDir('desc');
    } else if (sortDir === 'desc') {
      setSortDir(null);
      setSortCol(null);
    } else {
      setSortDir('asc');
    }
    setPage(0);
  }

  function handleFilterChange(col: string, values: string[]) {
    setFilters((prev) => ({ ...prev, [col]: values }));
    setPage(0);
  }

  function handleRemoveFilter(col: string, value: string) {
    setFilters((prev) => {
      const next = { ...prev };
      next[col] = (next[col] ?? []).filter((v) => v !== value);
      if (next[col].length === 0) delete next[col];
      return next;
    });
    setPage(0);
  }

  function handleClearAllFilters() {
    setFilters({});
    setPage(0);
  }

  function handleExport(format: 'csv' | 'json') {
    if (format === 'csv') {
      const header = columns.map(escapeCsv).join(',');
      const body = sortedRows
        .map((row) => columns.map((col) => escapeCsv(cellText(row[col]))).join(','))
        .join('\r\n');
      const csv = '\uFEFF' + header + '\r\n' + body;
      downloadCsv(`${title}.csv`, csv);
    } else {
      const json = JSON.stringify(sortedRows, null, 2);
      const blob = new Blob([json], { type: 'application/json;charset=utf-8;' });
      downloadBlob(`${title}.json`, blob);
    }
  }

  const subtitle = `${totalRows.toLocaleString()}행`;

  const actions = (
    <>
      <button
        type="button"
        title="SQL 보기"
        onClick={() => setSqlOpen((v) => !v)}
        className={`flex items-center gap-1 rounded px-1.5 py-0.5 text-xs transition-colors hover:bg-muted ${sqlOpen ? 'text-primary' : 'text-muted-foreground'}`}
      >
        <Code2 className="h-3.5 w-3.5" />
        SQL
      </button>
      <ExportDropdown onExport={handleExport} />
    </>
  );

  return (
    <WidgetShell
      title={title}
      icon="📋"
      subtitle={subtitle}
      actions={actions}
      onNavigate={onNavigate}
      displayMode={displayMode}
    >
      {/* SQL collapse */}
      {sqlOpen && (
        <div className="border-b border-border bg-muted/20 px-3 py-2">
          <SyntaxHighlighter
            style={oneDark}
            language="sql"
            PreTag="div"
            customStyle={{ margin: 0, fontSize: '0.72rem', borderRadius: '0.375rem' }}
          >
            {String(input.sql ?? '')}
          </SyntaxHighlighter>
        </div>
      )}

      {/* Active filter chips */}
      <ActiveFilterChips
        filters={filters}
        onRemove={handleRemoveFilter}
        onClearAll={handleClearAllFilters}
      />

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-border bg-muted/30">
              {columns.map((col) => {
                const isActive = sortCol === col;
                const dir = isActive ? sortDir : null;
                return (
                  <th
                    key={col}
                    className="select-none whitespace-nowrap px-3 py-1.5 text-left font-medium text-muted-foreground"
                  >
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        className="flex items-center gap-1 cursor-pointer hover:text-foreground"
                        onClick={() => handleSort(col)}
                      >
                        <span className="truncate">{col}</span>
                        {dir === 'asc' ? (
                          <ChevronUp className="h-3 w-3 shrink-0 text-primary" />
                        ) : dir === 'desc' ? (
                          <ChevronDown className="h-3 w-3 shrink-0 text-primary" />
                        ) : (
                          <ChevronsUpDown className="h-3 w-3 shrink-0 opacity-30" />
                        )}
                      </button>
                      <ColumnFilterDropdown
                        columnName={col}
                        uniqueValues={uniqueValues[col] ?? []}
                        selectedValues={filters[col] ?? []}
                        onFilterChange={(values) => handleFilterChange(col, values)}
                      />
                    </div>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {pageRows.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-3 py-4 text-center text-muted-foreground">
                  결과 없음
                </td>
              </tr>
            ) : (
              pageRows.map((row, i) => (
                <tr
                  key={i}
                  className="border-b border-border/50 transition-colors duration-150 hover:bg-muted/20 odd:bg-background even:bg-muted/10"
                >
                  {columns.map((col) => (
                    <td key={col} className="whitespace-nowrap px-3 py-2 text-foreground">
                      <CellRenderer value={row[col]} />
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {sortedRows.length > PAGE_SIZE && (
        <Pagination
          currentPage={currentPage}
          totalPages={totalPages}
          totalItems={sortedRows.length}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </WidgetShell>
  );
}
