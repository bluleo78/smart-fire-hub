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
  await loadGraph(graph, 777, 1, 9);
});
afterAll(async () => { await closeDriver(); });

describe('structuredQuery (integration) — 적재→구조질의 왕복', () => {
  it('피해액>1e8 필터가 매칭 사건만 반환하고, 값이 JS number로 왕복한다', async () => {
    const res = await structuredQuery(CORE_ONTOLOGY, 9, 'Incident',
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
    const res = await structuredQuery(CORE_ONTOLOGY, 9, 'Incident',
      [{ property: '피해액', operator: 'gte', value: 50_000_000 }]);
    expect(res.entities.map((e) => e.name).sort()).toEqual(['작은불-2026', '큰불-2026']);
  });

  // 크로스 온톨로지(=크로스 테넌트) 격리. 같은 타입명(Incident)을 쓰는 다른 온톨로지의 노드가
  // 함께 심겨 있어야만 술어가 실제로 거르는지 증명된다 — 한쪽만 심은 테스트는 술어를 지워도
  // 통과한다. 속성값(properties(n))까지 새는지도 함께 본다: 이 경로는 이름만이 아니라 속성 전부를
  // 돌려주므로 누수의 폭이 넓다.
  it('같은 타입명이라도 다른 온톨로지의 엔티티는 반환하지 않는다', async () => {
    const otherOntologyId = 900011;
    // 실 적재 경로(loadGraph)로 심는다 — 직접 CREATE 하면 ontologyId 가 INTEGER 로 스탬프되는지
    // 같은 쓰기측 규약을 우회해, 읽기 술어가 실제 저장 형태와 맞는지를 증명하지 못한다.
    await loadGraph({
      entities: [{
        key: entityKey(incidentId, '남의불-2026'), type: 'Incident' as const,
        name: '남의불-2026', properties: { 피해액: 900_000_000 },
      }],
      relations: [],
    }, 778, 1, otherOntologyId);

    // 내 온톨로지(9)로 질의하면 남의 노드는 조건(피해액>1e8)을 만족해도 나오지 않는다.
    const mine = await structuredQuery(CORE_ONTOLOGY, 9, 'Incident',
      [{ property: '피해액', operator: 'gt', value: 100_000_000 }]);
    expect(mine.entities.map((e) => e.name)).toEqual(['큰불-2026']);

    // 대칭 확인 — 술어가 "항상 빈 결과"가 아니라 진짜로 온톨로지를 가르는지.
    const theirs = await structuredQuery(CORE_ONTOLOGY, otherOntologyId, 'Incident',
      [{ property: '피해액', operator: 'gt', value: 100_000_000 }]);
    expect(theirs.entities.map((e) => e.name)).toEqual(['남의불-2026']);
  });
});
