// loader의 멱등성 통합 테스트 — 실제 Neo4j에 연결해 두 번 적재해도 노드/관계 수가 불변임을 검증한다.
import { VerifiedOntologyId } from './verified-ontology-id.js';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { edgeCount } from './__fixtures__/neo4j.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

const graph = {
  entities: [
    { key: entityKey(incidentId, '2026-001'), type: 'Incident' as const, name: '2026-001' },
    { key: entityKey(causeId, '전기적 요인'), type: 'Cause' as const, name: '전기적 요인' },
  ],
  relations: [
    { subjectKey: entityKey(incidentId, '2026-001'), type: 'CAUSED_BY' as const, objectKey: entityKey(causeId, '전기적 요인') },
  ],
};

async function count(label: 'Entity' | 'REL'): Promise<number> {
  const s = getSession();
  try {
    const q = label === 'Entity' ? 'MATCH (n:Entity) RETURN count(n) AS c'
                                 : 'MATCH ()-[r:REL]->() RETURN count(r) AS c';
    return (await s.run(q)).records[0].get('c').toNumber();
  } finally { await s.close(); }
}

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); } // 테스트 격리
});
afterAll(async () => { await closeDriver(); });

describe('loadGraph (integration)', () => {
  it('두 번 적재해도 노드/관계 수가 불변이다(멱등)', async () => {
    await loadGraph(graph, 101, 1, 9 as VerifiedOntologyId);
    const n1 = await count('Entity'), r1 = await count('REL');
    await loadGraph(graph, 101, 1, 9 as VerifiedOntologyId);
    expect(await count('Entity')).toBe(n1);
    expect(await count('REL')).toBe(r1);
    expect(n1).toBe(2);
    expect(r1).toBe(1);
  });
});

// 크로스 온톨로지 격리 — loadGraph 의 관계 MERGE 는 양 끝점에 ontologyId 술어를 건다.
// 배치에 함께 실린 노드는 바로 앞 단계에서 이 온톨로지로 스탬프되므로 영향이 없고,
// **배치에 없는 키**(다른 온톨로지의 기존 노드)로 엣지를 긋는 것만 막힌다.
//
// 전역 카운트가 아니라 키 단위로 단언한다 — 위 멱등 테스트처럼 전체 개수로 보면 다른 테스트의
// 잔여 노드가 증거를 가린다.
describe('loadGraph 크로스 온톨로지 관계 차단', () => {
  const MY_OID = 9;
  const OTHER_OID = 900025;
  const subjectKey = entityKey(incidentId, 'ZZTEST_적재사건');
  const foreignKey = entityKey(causeId, 'ZZTEST_남의원인');

  it('배치에 없는 남의 온톨로지 노드로는 엣지를 만들지 않는다', async () => {
    // 남의 온톨로지 노드를 먼저 적재해 둔다(별도 배치 = 이후 배치의 entities 에 없다).
    await loadGraph({
      entities: [{ key: foreignKey, type: 'Cause' as const, name: 'ZZTEST_남의원인' }],
      relations: [],
    }, 201, 1, OTHER_OID as VerifiedOntologyId);

    // 내 배치는 주어만 싣고, 목적어로 남의 노드 키를 참조한다.
    await loadGraph({
      entities: [{ key: subjectKey, type: 'Incident' as const, name: 'ZZTEST_적재사건' }],
      relations: [{ subjectKey, type: 'CAUSED_BY' as const, objectKey: foreignKey }],
    }, 202, 1, MY_OID as VerifiedOntologyId);

    expect(await edgeCount(subjectKey, foreignKey)).toBe(0);
  });

  it('같은 온톨로지의 기존 노드라면 배치에 없어도 엣지를 만든다(술어가 스코프를 가른다)', async () => {
    const mineKey = entityKey(causeId, 'ZZTEST_내원인');
    await loadGraph({
      entities: [{ key: mineKey, type: 'Cause' as const, name: 'ZZTEST_내원인' }],
      relations: [],
    }, 203, 1, MY_OID as VerifiedOntologyId);

    await loadGraph({
      entities: [{ key: subjectKey, type: 'Incident' as const, name: 'ZZTEST_적재사건' }],
      relations: [{ subjectKey, type: 'CAUSED_BY' as const, objectKey: mineKey }],
    }, 204, 1, MY_OID as VerifiedOntologyId);

    expect(await edgeCount(subjectKey, mineKey)).toBe(1);
  });
});
