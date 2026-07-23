// 구조질의 seam 통합 테스트 — 실제 Neo4j에 loader로 속성값을 쓰고, structuredQuery로 필터해 읽어
// "적재 → 구조질의" 왕복(spec DoD 항목 4)을 검증한다. 단위 테스트는 loader/query가 각각 getSession을
// 모킹하므로 이 왕복(값이 실제로 저장·비교되는지, JS number로 되돌아오는지)은 통합에서만 잡힌다.
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { structuredQuery } from './structured-query.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');

// 피해액 2억(>1e8, 매칭) 사건과 5천만(<1e8, 비매칭) 사건을 적재한다.
const graph = {
  entities: [
    { key: entityKey(incidentId, '큰불-2026'), type: 'Incident' as const, name: '큰불-2026', properties: { 피해액: 200_000_000 } },
    { key: entityKey(incidentId, '작은불-2026'), type: 'Incident' as const, name: '작은불-2026', properties: { 피해액: 50_000_000 } },
  ],
  relations: [],
};

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); } // 테스트 격리
  await loadGraph(graph, 777, 1);
});
afterAll(async () => { await closeDriver(); });

describe('structuredQuery (integration) — 적재→구조질의 왕복', () => {
  it('피해액>1e8 필터가 매칭 사건만 반환하고, 값이 JS number로 왕복한다', async () => {
    const res = await structuredQuery(CORE_ONTOLOGY, 'Incident',
      [{ property: '피해액', operator: 'gt', value: 100_000_000 }]);

    // 매칭은 큰불-2026 1건.
    expect(res.entities.map((e) => e.name)).toEqual(['큰불-2026']);
    // 저장 청크 id가 출처로 되돌아온다.
    expect(res.sourceChunkIds).toContain(777);

    // 피해액이 Neo4j 내부표현이 아니라 JS number 200000000 으로 왕복한다.
    const amount = res.entities[0].properties.피해액;
    expect(typeof amount).toBe('number');
    expect(amount).toBe(200_000_000);
  });

  it('경계값(피해액>=5천만, gte)은 두 건 모두 반환한다', async () => {
    const res = await structuredQuery(CORE_ONTOLOGY, 'Incident',
      [{ property: '피해액', operator: 'gte', value: 50_000_000 }]);
    expect(res.entities.map((e) => e.name).sort()).toEqual(['작은불-2026', '큰불-2026']);
  });
});
