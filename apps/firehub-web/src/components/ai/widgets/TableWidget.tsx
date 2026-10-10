import { ChevronDown, ChevronsUpDown, ChevronUp, Code2, Download } from 'lucide-react';
import { useMemo,useState } from 'react';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';

import { useExportCheck } from '../../../hooks/queries/useAnalytics';
import { downloadBlob, downloadCsv } from '../../../lib/download';
import { ActiveFilterChips } from './table/ActiveFilterChips';
import { CellRenderer } from './table/CellRenderer';
import { ColumnFilterDropdown } from './table/ColumnFilterDropdown';
import { ExportDropdown } from './table/ExportDropdown';
import { Pagination } from './table/Pagination';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

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

  // AI 가 만든 표는 서버 플래그가 없다(입력은 LLM 이 조립) — 화면의 SQL 로 내보내기 가능 여부를 서버에 묻는다(S4).
  // 응답 전·실패·SQL 없음은 숨김(fail-closed). 숨김·파싱 실패·정책 위반은 서버가 모두 false 로 준다.
  const { data: exportCheck, isPending: exportCheckPending } = useExportCheck(input.sql);
  const exportAllowed = exportCheck?.exportAllowed === true;

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
      {/* 보조 다운로드 → 차단이면 숨김(스펙 §5-4). 판정 응답 전에는 같은 크기 자리만 잡아 둔다 — 허용으로 판정돼
          버튼이 나타날 때 제목·SQL 버튼이 밀리지 않게(조작 불가, 스크린리더 비노출). */}
      {exportAllowed ? (
        <ExportDropdown onExport={handleExport} />
      ) : (
        exportCheckPending &&
        Boolean(input.sql) && (
          <span
            aria-hidden="true"
            data-testid="export-check-placeholder"
            className="invisible flex items-center gap-1 px-1.5 py-0.5 text-xs"
          >
            <Download className="h-3.5 w-3.5" />
            내보내기
          </span>
        )
      )}
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
