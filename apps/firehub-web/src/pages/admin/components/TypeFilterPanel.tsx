import { useMemo, useState } from 'react';

import { SearchInput } from '@/components/ui/search-input';
import { colorForType, ENTITY_TYPE_COLORS } from '@/lib/ontology-colors';
import { cn } from '@/lib/utils';
import type { GraphData, OntologySchema } from '@/types/ontology';

interface Props {
  schema?: OntologySchema;
  graph?: GraphData;
  activeTypes: Set<string>;
  // (#404) 클릭한 타입뿐 아니라 이 패널이 알고 있는 전체 타입 목록(allTypes)도 함께 넘긴다 —
  // "빈 Set = 전체 표시" 상태에서 하나를 끄려면 호출자가 "전체 목록 - 클릭한 타입"을 계산해야
  // 하는데, 그 전체 목록의 단일 원본은 이 패널의 groups다(빈 스키마 폴백 등 예외를 이미 알고 있음).
  onToggle: (type: string, allTypes: string[]) => void;
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

  // resolution별로 타입을 그룹화.
  // (#407) schema 자체가 아직 안 온 "로딩 중"(undefined)에만 색상표 키로 스켈레톤을 보여준다.
  // schema는 왔지만 entities가 빈 배열인 경우는 "정상적으로 비어있는 온톨로지"이므로 데모 타입으로
  // 대체하지 않고 빈 그룹(= 빈 목록)을 그대로 반환해야 한다 — 예전엔 두 상태를 entities.length===0
  // 하나로 뭉뚱그려 판별해, 방금 만든 빈 초안 온톨로지에서도 이전 온톨로지의 데모 타입 6종이 실재하는
  // 것처럼 표시됐다.
  const groups = useMemo(() => {
    if (!schema) {
      return [{ label: '타입', types: Object.keys(ENTITY_TYPE_COLORS) }];
    }
    const entities = schema.entities ?? [];
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

  // groups에 속한 전체 타입(검색 필터와 무관) — onToggle 호출 시 "빈 Set=전체" 해석에 쓸
  // 전체 타입 목록으로 그대로 전달한다(#404).
  const allTypes = useMemo(() => groups.flatMap((g) => g.types), [groups]);

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
        // (#414) sm(640px) 미만에서는 collapsed 상태와 무관하게 항상 숨긴다 — w-64(256px) + 캔버스
        // 최소폭(280px, OntologyPage.tsx의 min-w-[280px])이 536px로, 640px부터는 이 조합이 항상
        // 들어가지만 그 미만(모바일 375px 등)에서는 캔버스가 뷰포트 밖으로 밀리거나 슬리버로
        // 압착된다. ModelOutline/인스펙터가 xl 미만에서 숨는 것(#403)과 같은 패턴 — 다만 이 패널은
        // 실사용 폭이 더 좁아(576px가 아니라 536px) sm이면 충분하다.
        'hidden shrink-0 flex-col overflow-hidden border-r transition-[width] duration-200 sm:flex',
        collapsed ? 'sm:w-0 sm:border-r-0' : 'sm:w-64',
      )}
      data-testid="type-filter-panel"
      // inert: 접힌 패널의 내부 컨트롤을 포커스 순서와 접근성 트리에서 동시에 제거한다(#327).
      // aria-hidden만으로는 접근성 트리에서만 빠지고 탭 스톱이 남아, 보이지 않는 곳에 포커스가 갇혔다.
      // w-0(+overflow-hidden)은 display:none/visibility:hidden이 아니라 포커스를 막지 못한다.
      inert={collapsed}
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
                      onClick={() => onToggle(t, allTypes)}
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
