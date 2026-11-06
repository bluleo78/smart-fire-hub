import { ChevronDown, ChevronRight, MoreHorizontal, Plus, Search, Table2, X } from 'lucide-react';
import { type KeyboardEvent, useMemo, useRef, useState } from 'react';

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../../components/ui/dropdown-menu';
import { Input } from '../../../components/ui/input';
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
}

export function SchemaExplorer({ tables, dialect, schema, onInsertAtCursor }: SchemaExplorerProps) {
  const dialectInfo = useMemo(() => ({ dialect, schema }), [dialect, schema]);
  const [searchQuery, setSearchQuery] = useState('');
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

  if (tables.length === 0) {
    return (
      <div className="p-3 text-xs text-muted-foreground">
        테이블 정보를 불러오는 중...
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
}
