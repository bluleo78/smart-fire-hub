import { useMemo, useRef, useState } from 'react';

import { SearchInput } from '@/components/ui/search-input';
import { SearchNoResult } from '@/components/ui/table-empty';
import type { TypePalette } from '@/lib/ontology-colors';
import { cn } from '@/lib/utils';
import type { EntityTypeDef, GraphData } from '@/types/ontology';

import { typeFilterPanelRootClass } from './type-filter-panel-root';

interface Props {
  // (#677 정리) 이 패널은 entities만 읽으므로 스키마 전체(domain/schemaVersion/relations)를 받을
  // 필요가 없다 — 호출부가 여러 온톨로지를 합친 목록을 넘길 때도 가짜 스키마 객체를 만들지 않아도 된다.
  entities?: EntityTypeDef[];
  graph?: GraphData;
  activeTypes: Set<string>;
  // (#404) 클릭한 타입뿐 아니라 이 패널이 알고 있는 전체 타입 목록(allTypes)도 함께 넘긴다 —
  // "빈 Set = 전체 표시" 상태에서 하나를 끄려면 호출자가 "전체 목록 - 클릭한 타입"을 계산해야
  // 하는데, 그 전체 목록의 단일 원본은 이 패널의 groups다(빈 스키마 폴백 등 예외를 이미 알고 있음).
  onToggle: (type: string, allTypes: string[]) => void;
  onReset: () => void;
  collapsed: boolean;
  // (#413) 툴바의 이름 검색어 — InstanceGraph가 캔버스에 적용하는 것과 같은 값을 받아 타입별
  // 개수 배지에도 반영한다. 스키마 탭처럼 검색 UI가 없는 호출부는 넘기지 않아도 되며, 그 경우
  // 배지는 지금처럼 전체 개수를 보여준다(생략 시 빈 문자열과 동일하게 동작).
  search?: string;
  // (#396) 현재 온톨로지 전체 타입에 색을 고정한 팔레트 — OntologyPage가 한 번 만들어 캔버스·
  // 인스펙터와 공유한다. 이 패널이 자체적으로 타입 목록을 모아 팔레트를 만들면(패널은 검색·
  // 그룹핑으로 목록을 걸러 렌더하므로) 같은 타입이 캔버스와 다른 색이 된다.
  palette: TypePalette;
}

// resolution(임베딩/정확) 코드 → 표시 라벨. 그룹 헤더로 사용한다.
const RESOLUTION_LABEL: Record<string, string> = {
  embedding: '임베딩 해소',
  exact: '정확 매칭',
};

// 좌측 타입 필터 패널 — flat 칩 범례(TypeLegend)를 대체.
// resolution별 그룹핑 + 패널 내 검색 + 개수 표시 + 토글 필터(빈 activeTypes = 전체 활성).
// 접기(collapsed) 시 폭 0으로 트랜지션해 캔버스를 넓힌다.
export default function TypeFilterPanel({ entities: entityList, graph, activeTypes, onToggle, onReset, collapsed, search = '', palette }: Props) {
  const [filter, setFilter] = useState('');
  // WD-1128: 「검색 초기화」 뒤 초점을 타입 검색 입력으로 돌려주기 위한 ref(SearchNoResult 의 resetFocusRef).
  const filterInputRef = useRef<HTMLInputElement>(null);

  // resolution별로 타입을 그룹화.
  // (#407) entities는 왔지만 빈 배열인 경우는 "정상적으로 비어있는 온톨로지"이므로 데모
  // 타입으로 대체하지 않고 빈 그룹(= 빈 목록)을 그대로 반환해야 한다 — 예전엔 두 상태를
  // entities.length===0 하나로 뭉뚱그려 판별해, 방금 만든 빈 초안 온톨로지에서도 이전 온톨로지의
  // 데모 타입 6종이 실재하는 것처럼 표시됐다.
  // (#396) 로딩 중(entities === undefined)에도 마찬가지로 빈 목록을 반환한다 — 예전엔 색상표의 키
  // (데모 타입 6종)를 스켈레톤으로 보여줬는데, 그 색상표 자체가 사라졌을 뿐 아니라 실재하지 않는
  // 타입 이름을 잠시라도 보여주는 것은 #407이 막으려던 오인과 같은 부류다.
  const groups = useMemo(() => {
    const entities = entityList ?? [];
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
  }, [entityList]);

  // groups에 속한 전체 타입(검색 필터와 무관) — onToggle 호출 시 "빈 Set=전체" 해석에 쓸
  // 전체 타입 목록으로 그대로 전달한다(#404).
  const allTypes = useMemo(() => groups.flatMap((g) => g.types), [groups]);

  // 타입별 노드 개수(그래프 로드 후에만 표시).
  // (#413) InstanceGraph가 캔버스에 적용하는 이름 검색(name 부분일치, 대소문자 무시)과 동일한
  // 조건으로 세어야 한다 — 그렇지 않으면 검색 결과가 0건인데 배지만 전체 개수를 그대로 보여줘
  // 사용자가 데이터가 존재한다고 오인한다.
  const searchQuery = search.trim().toLowerCase();
  const countByType = (t: string) =>
    graph?.nodes.filter((n) => n.type === t && (searchQuery === '' || n.name.toLowerCase().includes(searchQuery)))
      .length ?? 0;
  // 필터 활성 여부 — 빈 activeTypes는 전체 활성으로 본다.
  const isActive = (t: string) => activeTypes.size === 0 || activeTypes.has(t);
  // 패널 내 검색어로 타입 이름을 부분일치 필터.
  const trimmedFilter = filter.trim();
  const q = trimmedFilter.toLowerCase();
  const matches = (t: string) => q === '' || t.toLowerCase().includes(q);
  // WD-1128: 그룹별 보이는 타입을 한 번만 계산해 렌더와 0건 판정이 같은 집합을 보게 한다
  // (따로 계산하면 두 조건이 어긋나 빈 패널이나 잘못된 0건 안내가 날 수 있다).
  const visibleGroups = groups
    .map((group) => ({ label: group.label, visible: group.types.filter(matches) }))
    .filter((group) => group.visible.length > 0);
  // 검색어(공백 제외)가 있는데 보이는 타입이 하나도 없으면 빈 패널 대신 공용 0건 안내를 띄운다.
  // 그룹이 0개(빈 온톨로지·로딩 중)면 검색 탓이 아니므로 allTypes.length > 0 일 때만 판정한다.
  const noResult = q !== '' && allTypes.length > 0 && visibleGroups.length === 0;

  return (
    <div
      // 루트 클래스의 단일 원본은 typeFilterPanelRootClass — 목록 로딩 스켈레톤이 같은 함수로 같은
      // 폭·보더에 서야 교체 시 레이아웃이 밀리지 않으므로(WD-1041) 여기에 문자열을 따로 두지 않는다.
      className={typeFilterPanelRootClass(collapsed)}
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
        <SearchInput placeholder="타입 검색" aria-label="타입 검색" value={filter} onChange={setFilter} className="w-full" inputRef={filterInputRef} />
      </div>

      {/* resolution 그룹별 타입 목록. */}
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4" data-testid="type-filter-list">
        {noResult && (
          // WD-1128: InstanceGraph·TableEmptyRow 와 같은 래퍼 — 글자색 text-muted-foreground, max-w-full.
          // 긴 검색어는 SearchNoResultText 의 overflow-wrap:anywhere 가 줄바꿈하므로 break-all 은 쓰지 않는다.
          // role=group + 이름: 그래프 탐색 탭에선 InstanceGraph 도 자기 0건 안내(「검색 초기화」)를 함께 띄우므로,
          // 스크린리더가 이 버튼이 어느 검색(패널의 타입 검색)을 초기화하는지 구분하도록 영역에 이름을 준다.
          <div
            role="group"
            aria-label="타입 검색 결과"
            className="flex max-w-full flex-col items-center gap-3 px-2 pt-8 text-center text-muted-foreground"
          >
            <SearchNoResult searchKeyword={trimmedFilter} onResetSearch={() => setFilter('')} resetFocusRef={filterInputRef} />
          </div>
        )}
        {visibleGroups.map(({ label, visible }) => {
          return (
            <div key={label} className="mb-3">
              <div className="px-2 py-1 text-xs font-medium uppercase text-muted-foreground">{label}</div>
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
                      <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: palette.color(t) }} />
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
