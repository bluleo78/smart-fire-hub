// rowToGraph / projectTableDataset 단위 테스트(Neo4j·API mock).
import { describe, it, expect, vi } from 'vitest';
import { rowToGraph, projectTableDataset } from './table-projection.js';
import type { MappingSpec } from './table-projection.js';
import { CORE_ONTOLOGY } from './ontology.js';

// CORE_ONTOLOGY에 존재하는 타입/속성/트리플로 매핑 구성(Incident.피해액 number, Incident-OCCURRED_AT->Building).
const mapping: MappingSpec = {
  entities: [
    { entityType: 'Incident', nameColumn: 'inc', properties: [{ column: 'loss', propertyName: '피해액' }] },
    { entityType: 'Building', nameColumn: 'bld', properties: [] },
  ],
  relations: [{ subjectRef: 0, relation: 'OCCURRED_AT', objectRef: 1 }],
};

describe('rowToGraph', () => {
  it('행을 엔티티·속성·관계로 변환한다', () => {
    const g = rowToGraph({ inc: '사건1', bld: '건물1', loss: '120000000' }, CORE_ONTOLOGY, mapping);
    expect(g.entities).toHaveLength(2);
    const inc = g.entities.find((e) => e.type === 'Incident')!;
    expect(inc.properties).toEqual({ 피해액: 120000000 }); // number 타입은 숫자로 강제
    expect(g.relations).toHaveLength(1);
    expect(g.relations[0].type).toBe('OCCURRED_AT');
  });

  it('빈 nameColumn 엔티티와 그에 의존하는 관계는 skip', () => {
    const g = rowToGraph({ inc: '사건2', bld: '', loss: '' }, CORE_ONTOLOGY, mapping);
    expect(g.entities).toHaveLength(1); // Building skip
    expect(g.relations).toHaveLength(0); // objectRef 없음 → 관계 skip
    expect(g.entities[0].properties).toBeUndefined(); // 빈 속성값 제외
  });

  it('number 속성이 숫자로 파싱 불가하면 문자열로 저장', () => {
    const g = rowToGraph({ inc: '사건3', bld: 'b', loss: 'N/A' }, CORE_ONTOLOGY, mapping);
    const inc = g.entities.find((e) => e.type === 'Incident')!;
    expect(inc.properties).toEqual({ 피해액: 'N/A' });
  });
});

describe('projectTableDataset', () => {
  it('페이지를 순회하며 적재하고 distinct 노드/엣지를 센다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 0, relations: 0 });
    const pages: Record<number, { rows: Record<string, unknown>[]; totalPages: number }> = {
      0: { rows: [{ inc: 'A', bld: 'X', loss: '1' }, { inc: 'A', bld: 'X', loss: '1' }], totalPages: 2 },
      1: { rows: [{ inc: 'B', bld: 'X', loss: '2' }], totalPages: 2 },
    };
    const deps = { fetchRows: vi.fn(async (_id: number, page: number) => pages[page]), load };
    const summary = await projectTableDataset(deps, 900, CORE_ONTOLOGY, mapping);

    expect(summary.rowCount).toBe(3);
    expect(summary.pageCount).toBe(2);
    // 노드 키: A(Incident), X(Building), B(Incident) → 같은 A·X는 병합돼 distinct 3.
    expect(summary.nodeCount).toBe(3);
    expect(load).toHaveBeenCalledTimes(2); // 페이지당 1회
  });

  it('행이 없으면 load를 호출하지 않는다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 0, relations: 0 });
    const deps = { fetchRows: vi.fn(async () => ({ rows: [], totalPages: 1 })), load };
    const summary = await projectTableDataset(deps, 901, CORE_ONTOLOGY, mapping);
    expect(summary.rowCount).toBe(0);
    expect(load).not.toHaveBeenCalled();
  });
});
