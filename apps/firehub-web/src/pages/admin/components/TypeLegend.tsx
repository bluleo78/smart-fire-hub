import { ENTITY_TYPE_COLORS } from '@/lib/ontology-colors';
import type { GraphData, OntologySchema } from '@/types/ontology';

interface Props {
  schema?: OntologySchema;
  graph?: GraphData;
  activeTypes: Set<string>;
  onToggle: (type: string) => void;
}

// 상시 타입 색상 범례 — 클릭 시 필터 토글(스키마·인스턴스 탭 공유).
export default function TypeLegend({ schema, graph, activeTypes, onToggle }: Props) {
  // 스키마 로딩 전에도 6타입 색상표를 기본값으로 노출한다.
  const types = schema?.entities.map((e) => e.type) ?? Object.keys(ENTITY_TYPE_COLORS);
  const countByType = (t: string) => graph?.nodes.filter((n) => n.type === t).length ?? 0;
  const policyByType = (t: string) => schema?.entities.find((e) => e.type === t)?.resolution;

  return (
    <div className="flex flex-wrap gap-3 rounded-lg border p-3" data-testid="type-legend">
      {types.map((t) => {
        // 빈 activeTypes = 전체 활성. 그 외에는 activeTypes에 포함된 타입만 활성 표시.
        const active = activeTypes.size === 0 || activeTypes.has(t);
        return (
          <button
            key={t}
            type="button"
            onClick={() => onToggle(t)}
            className={`flex items-center gap-1.5 text-xs rounded px-1.5 py-0.5 ${active ? '' : 'opacity-35'}`}
          >
            <span className="w-2.5 h-2.5 rounded-full" style={{ background: ENTITY_TYPE_COLORS[t] ?? '#64748b' }} />
            {t}
            <span className="text-muted-foreground">
              {policyByType(t) ?? ''}
              {graph ? `·${countByType(t)}` : ''}
            </span>
          </button>
        );
      })}
    </div>
  );
}
