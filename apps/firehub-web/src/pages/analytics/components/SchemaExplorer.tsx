import { ChevronDown, ChevronRight, MoreHorizontal, Plus, Search, Table2, X } from 'lucide-react';
import { type KeyboardEvent, memo, useMemo, useRef, useState } from 'react';

import { PendingButton } from '../../../components/PendingButton';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../../components/ui/dropdown-menu';
import { Input } from '../../../components/ui/input';
import { extractApiError } from '../../../lib/api-error';
import { cn } from '../../../lib/utils';
import type { SchemaInfo, SchemaTable } from '../../../types/analytics';
import {
  columnReference,
  generateSnippets,
  getTypeBadge,
  tableReference,
} from './schema-explorer-utils';

interface SchemaExplorerProps {
  tables: SchemaTable[];
  /** 테넌트 방언·스키마 — 삽입 텍스트를 방언에 맞춘다(없으면 PG) */
  dialect?: SchemaInfo['dialect'];
  schema?: string;
  onInsertAtCursor: (text: string) => void;
  /** 스키마 조회 진행 중(데이터 없음) — WD-912: 빈 목록과 로딩을 구분한다 */
  isLoading?: boolean;
  /** 스키마 조회 오류 — 목록이 비어 있을 때만 오류 UI 로 보인다 */
  error?: unknown;
  /** 오류 UI 의 「다시 시도」 */
  onRetry?: () => void;
  /** 스키마를 지금 (재)조회 중인지 — 「다시 시도」 잠금과 기억한 오류를 보일지 정한다(WD-912 리뷰 후속) */
  isFetching?: boolean;
}

// WD-1075: 부모(쿼리 편집기)가 SQL 입력마다 다시 그려져도 props 가 같으면 테이블 목록을 건너뛴다.
export const SchemaExplorer = memo(function SchemaExplorer({
  tables,
  dialect,
  schema,
  onInsertAtCursor,
  isLoading,
  error,
  onRetry,
  isFetching,
}: SchemaExplorerProps) {
  const dialectInfo = useMemo(() => ({ dialect, schema }), [dialect, schema]);
  const [searchQuery, setSearchQuery] = useState('');
  // 마지막 조회 오류 — 데이터 없이 재조회하는 동안에도 오류 UI 를 유지하는 데 쓴다(WD-912)
  const [lastError, setLastError] = useState<unknown>(null);
  const [expandedTables, setExpandedTables] = useState<Set<string>>(new Set());

  const searchExpandedTables = useMemo(() => {
    if (!searchQuery.trim()) return new Set<string>();
    const q = searchQuery.toLowerCase();
    const autoExpand = new Set<string>();

    for (const table of tables) {
      const hasColumnMatch = table.columns.some(
        (column) =>
          column.columnName.toLowerCase().includes(q)
          || (column.displayName?.toLowerCase().includes(q) ?? false)
      );
      if (hasColumnMatch) {
        autoExpand.add(table.tableName);
      }
    }

    return autoExpand;
  }, [tables, searchQuery]);

  const filteredTables = useMemo(() => {
    if (!searchQuery.trim()) return tables;
    const q = searchQuery.toLowerCase();

    return tables
      .map((table) => {
        const tableMatch = table.tableName.toLowerCase().includes(q)
          || (table.datasetName?.toLowerCase().includes(q) ?? false);
        const matchingColumns = table.columns.filter(
          (column) =>
            column.columnName.toLowerCase().includes(q)
            || (column.displayName?.toLowerCase().includes(q) ?? false)
        );

        if (tableMatch) return table;
        if (matchingColumns.length > 0) return { ...table, columns: matchingColumns };
        return null;
      })
      .filter(Boolean) as SchemaTable[];
  }, [tables, searchQuery]);

  const toggleTable = (tableName: string) => {
    setExpandedTables((prev) => {
      const next = new Set(prev);
      if (next.has(tableName)) {
        next.delete(tableName);
      } else {
        next.add(tableName);
      }
      return next;
    });
  };

  const isExpanded = (tableName: string) =>
    expandedTables.has(tableName) || searchExpandedTables.has(tableName);

  /**
   * 테이블 목록 키보드 이동 — 목록 전체를 Tab 정지 하나로 묶는다(roving tabindex, WD-526).
   *
   * 왜: 예전에는 테이블마다 「테이블 이름」「테이블명 삽입」「빠른 쿼리」 버튼이 모두 Tab 을 받아,
   * 데이터셋이 125개면 SQL 편집기까지 Tab 을 400번 넘게 눌러야 했다. 이제 Tab 은 지금 고른(활성) 테이블 한 줄에만
   * 멈추고, 테이블 사이는 위·아래 방향키(Home·End)로 옮긴다. 활성 줄의 삽입·빠른 쿼리 버튼과 펼친 컬럼은 그대로
   * Tab 으로 닿는다.
   */
  const [activeTable, setActiveTable] = useState<string | null>(null);
  const toggleRefs = useRef(new Map<string, HTMLButtonElement>());
  // 고른 테이블이 검색으로 사라지면 첫 테이블로 돌아가 Tab 정지가 늘 하나 남게 한다.
  const currentActive =
    activeTable && filteredTables.some((t) => t.tableName === activeTable)
      ? activeTable
      : (filteredTables[0]?.tableName ?? null);

  const moveFocus = (index: number) => {
    const target = filteredTables[index];
    if (!target) return;
    setActiveTable(target.tableName);
    toggleRefs.current.get(target.tableName)?.focus();
  };

  const handleTableKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = filteredTables.length - 1;
    const next =
      e.key === 'ArrowDown' ? Math.min(index + 1, last)
      : e.key === 'ArrowUp' ? Math.max(index - 1, 0)
      : e.key === 'Home' ? 0
      : e.key === 'End' ? last
      : null;
    if (next === null) return;
    e.preventDefault();
    moveFocus(next);
  };

  // WD-912: 목록이 비었을 때 오류 / 로딩 / 빈 상태를 구분한다 — 예전에는 무조건 「불러오는 중」이라 0건 응답과
  // 조회 실패도 영원히 로딩처럼 보였다. 데이터가 있으면(재조회 실패 포함) 아래 목록을 그대로 유지한다.
  // react-query 는 데이터 없는 오류에서 다시 조회를 시작하면 error 를 비우고 pending 으로 돌린다 — 그러면 다시 시도를
  // 누르는 순간 버튼(과 포커스)이 사라지므로, 마지막 오류를 기억해 재조회 동안 오류 UI 를 유지한다.
  // DashboardWidgetCard 의 lastError 와 같은 렌더 중 상태 맞추기(WD-912 리뷰 후속):
  // - 오류가 오면 늘 새 오류로 바꾼다 — 다시 실패한 뒤 자동 재조회(창 포커스 등) 동안 옛 문구가 보이지 않게.
  // - 조회가 성공하면(pending 아님·오류 없음) 지운다 — 남겨 두면 나중에 쿼리가 데이터 없이 다시 pending 이 될 때
  //   (쿼리 reset·캐시 제거) 로딩 대신 옛 오류가 보인다. 오프라인으로 멈춘(paused) 재조회는 pending 이라 지우지 않는다.
  // 기억은 오류가 있을 때만, 지우기는 오류가 없을 때만 해 두 갈래가 서로 배타적이다(끝없이 다시 그리지 않음).
  if (error != null) {
    if (error !== lastError) setLastError(error);
  } else if (!isLoading && lastError !== null) {
    setLastError(null);
  }
  const shownError = error ?? lastError;
  if (tables.length === 0) {
    if (shownError) {
      const detail = extractApiError(shownError, '');
      return (
        <div role="alert" className="flex flex-col items-start gap-2 p-3 text-xs">
          <p className="font-medium text-destructive">테이블 정보를 불러오지 못했습니다.</p>
          {detail && <p className="text-muted-foreground">{detail}</p>}
          {onRetry && (
            // WD-912: 진행 중 잠금은 native disabled 가 아니라 PendingButton(aria-disabled)으로 —
            // disabled 면 키보드로 누른 버튼의 포커스가 body 로 떨어진다(WD-637·WD-678).
            <PendingButton
              variant="outline"
              size="sm"
              className="h-8"
              // 잠금은 재조회 중 여부로 정한다 — 오프라인으로 멈춘 재조회는 잠그지 않아 다시 누를 수 있다.
              // isFetching 을 넘기지 않는 호출부는 「오류가 비었는데 오류 UI 가 보임 = 재조회 중」으로 추론한다.
              pending={isFetching ?? !error}
              onClick={onRetry}
            >
              다시 시도
            </PendingButton>
          )}
        </div>
      );
    }
    if (isLoading) {
      return (
        <div role="status" className="p-3 text-xs text-muted-foreground">
          테이블 정보를 불러오는 중...
        </div>
      );
    }
    return (
      <div className="p-3 text-xs text-muted-foreground">
        <p className="font-medium text-foreground">표시할 테이블이 없습니다.</p>
        <p className="mt-1">볼 수 있는 데이터셋이 생기면 여기에 테이블과 컬럼이 보입니다.</p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto">
      <div className="px-2 py-1.5 border-b">
        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="테이블 · 컬럼 검색..."
            className="h-7 border-0 bg-transparent text-xs pl-7 pr-6 focus-visible:ring-1"
          />
          {searchQuery && (
            <button
              type="button"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              onClick={() => setSearchQuery('')}
              aria-label="검색어 지우기"
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>

      {filteredTables.length === 0 ? (
        <div className="p-4 text-center text-xs text-muted-foreground">
          일치하는 테이블이 없습니다
        </div>
      ) : (
        <div role="group" aria-label="테이블 목록 — 위·아래 방향키로 테이블을 옮깁니다">
        {filteredTables.map((table, index) => {
          const tableExpanded = isExpanded(table.tableName);
          // 활성 줄만 Tab 순서에 남긴다(나머지는 방향키로 닿는다)
          const tabIndex = table.tableName === currentActive ? 0 : -1;
          return (
            <div key={table.tableName}>
              <div className="group flex items-center gap-1.5 px-3 py-1.5 text-xs hover:bg-muted/50 transition-colors">
                <button
                  type="button"
                  ref={(el) => {
                    if (el) toggleRefs.current.set(table.tableName, el);
                    else toggleRefs.current.delete(table.tableName);
                  }}
                  tabIndex={tabIndex}
                  aria-expanded={tableExpanded}
                  className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
                  onClick={() => {
                    setActiveTable(table.tableName);
                    toggleTable(table.tableName);
                  }}
                  onFocus={() => setActiveTable(table.tableName)}
                  onKeyDown={(e) => handleTableKeyDown(e, index)}
                >
                  {tableExpanded ? (
                    <ChevronDown className="h-3 w-3 shrink-0 text-muted-foreground" />
                  ) : (
                    <ChevronRight className="h-3 w-3 shrink-0 text-muted-foreground" />
                  )}
                  <Table2 className="h-3 w-3 shrink-0 text-info" />
                  <span className="truncate font-medium" title={table.tableName}>
                    {table.tableName}
                  </span>
                </button>

                {!tableExpanded && (
                  <span className="shrink-0 text-[11px] text-muted-foreground tabular-nums">
                    {table.columns.length}
                  </span>
                )}

                <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                  <button
                    type="button"
                    className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    onClick={() => onInsertAtCursor(tableReference(table, dialectInfo))}
                    tabIndex={tabIndex}
                    title="테이블명 삽입"
                    aria-label={`${table.tableName} 테이블명 삽입`}
                  >
                    <Plus className="h-3 w-3" />
                  </button>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <button
                        type="button"
                        className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                        tabIndex={tabIndex}
                        title="빠른 쿼리"
                        aria-label={`${table.tableName} 빠른 쿼리`}
                      >
                        <MoreHorizontal className="h-3 w-3" />
                      </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent
                      align="start"
                      side="right"
                      sideOffset={4}
                      className="w-56"
                    >
                      {generateSnippets(table, dialectInfo).map((snippet) => (
                        <DropdownMenuItem
                          key={snippet.label}
                          onClick={() => onInsertAtCursor(snippet.sql)}
                        >
                          <span className="font-mono text-xs">{snippet.label}</span>
                        </DropdownMenuItem>
                      ))}
                      <DropdownMenuSeparator />
                      <DropdownMenuItem
                        onClick={() => onInsertAtCursor(tableReference(table, dialectInfo))}
                      >
                        테이블명 삽입
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              </div>

              {tableExpanded && (
                <div className="pl-7">
                  {table.columns.map((column) => {
                    const badge = getTypeBadge(column.dataType);
                    return (
                      <div
                        key={column.columnName}
                        className="group/col flex items-center gap-1.5 px-3 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted/30 hover:text-foreground"
                      >
                        <span
                          className={cn(
                            'inline-flex h-4 w-4 shrink-0 items-center justify-center rounded text-[10px] font-bold leading-none',
                            badge.color
                          )}
                          title={column.dataType}
                        >
                          {badge.letter}
                        </span>
                        <span className="flex-1 truncate" title={column.columnName}>
                          {column.displayName || column.columnName}
                        </span>
                        <span className="shrink-0 text-[11px] text-muted-foreground">
                          {column.dataType}
                        </span>
                        <button
                          type="button"
                          className="shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-all hover:bg-muted hover:text-foreground group-hover/col:opacity-100 focus-visible:opacity-100"
                          onClick={() => onInsertAtCursor(columnReference(column.columnName, dialect))}
                          title="컬럼명 삽입"
                        >
                          <Plus className="h-3 w-3" />
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
        </div>
      )}
    </div>
  );
});
