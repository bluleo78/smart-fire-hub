import { useMemo, useState } from 'react';

import { SearchInput } from '@/components/ui/search-input';
import { colorForType, ENTITY_TYPE_COLORS } from '@/lib/ontology-colors';
import { cn } from '@/lib/utils';
import type { GraphData, OntologySchema } from '@/types/ontology';

interface Props {
  schema?: OntologySchema;
  graph?: GraphData;
  activeTypes: Set<string>;
  onToggle: (type: string) => void;
  onReset: () => void;
  collapsed: boolean;
}

// resolution(임베딩/정확) 코드 → 표시 라벨. 그룹 헤더로 사용한다.
const RESOLUTION_LABEL: Record<string, string> = {
  embedding: '임베딩 해소',
  exact: '정확 매칭',
};

// 좌측 타입 필터 패널 — flat 칩 범례(TypeLegend)를 대체.
// resolution별 그룹핑 + 패널 내 검색 + 개수 표시 + 토글 필터(빈 activeTypes = 전체 활성).
// 접기(collapsed) 시 폭 0으로 트랜지션해 캔버스를 넓힌다.
export default function TypeFilterPanel({ schema, graph, activeTypes, onToggle, onReset, collapsed }: Props) {
  const [filter, setFilter] = useState('');

  // resolution별로 타입을 그룹화(스키마 없으면 색상표 키로 단일 그룹).
  const groups = useMemo(() => {
    const entities = schema?.entities ?? [];
    if (entities.length === 0) {
      return [{ label: '타입', types: Object.keys(ENTITY_TYPE_COLORS) }];
    }
    const byResolution = new Map<string, string[]>();
    for (const e of entities) {
      const list = byResolution.get(e.resolution) ?? [];
      list.push(e.type);
      byResolution.set(e.resolution, list);
    }
    return [...byResolution.entries()].map(([resolution, types]) => ({
      label: RESOLUTION_LABEL[resolution] ?? resolution,
      types,
    }));
  }, [schema]);

  // 타입별 노드 개수(그래프 로드 후에만 표시).
  const countByType = (t: string) => graph?.nodes.filter((n) => n.type === t).length ?? 0;
  // 필터 활성 여부 — 빈 activeTypes는 전체 활성으로 본다.
  const isActive = (t: string) => activeTypes.size === 0 || activeTypes.has(t);
  // 패널 내 검색어로 타입 이름을 부분일치 필터.
  const q = filter.trim().toLowerCase();
  const matches = (t: string) => q === '' || t.toLowerCase().includes(q);

  return (
    <div
      className={cn(
        'flex shrink-0 flex-col overflow-hidden border-r transition-[width] duration-200',
        collapsed ? 'w-0 border-r-0' : 'w-64',
      )}
      data-testid="type-filter-panel"
      aria-hidden={collapsed}
    >
      {/* 헤더 — 타이틀 + 전체 리셋(활성 필터가 있을 때만 노출). */}
      <div className="flex items-center justify-between px-4 pt-4 pb-2">
        <span className="text-xs font-medium uppercase text-muted-foreground">타입 필터</span>
        {activeTypes.size > 0 && (
          <button type="button" onClick={onReset} className="text-xs text-primary hover:underline">
            전체
          </button>
        )}
      </div>

      {/* 패널 내 타입 검색(타입 다수 대비). */}
      <div className="px-4 pb-3">
        <SearchInput placeholder="타입 검색" aria-label="타입 검색" value={filter} onChange={setFilter} className="w-full" />
      </div>

      {/* resolution 그룹별 타입 목록. */}
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4" data-testid="type-filter-list">
        {groups.map((group) => {
          const visible = group.types.filter(matches);
          if (visible.length === 0) return null;
          return (
            <div key={group.label} className="mb-3">
              <div className="px-2 py-1 text-xs font-medium uppercase text-muted-foreground">{group.label}</div>
              <div className="space-y-0.5">
                {visible.map((t) => {
                  const active = isActive(t);
                  return (
                    <button
                      key={t}
                      type="button"
                      onClick={() => onToggle(t)}
                      aria-pressed={active}
                      className={cn(
                        'flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted',
                        !active && 'opacity-35',
                      )}
                    >
                      <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: colorForType(t) }} />
                      <span className="min-w-0 flex-1 truncate">{t}</span>
                      {graph && <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{countByType(t)}</span>}
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
