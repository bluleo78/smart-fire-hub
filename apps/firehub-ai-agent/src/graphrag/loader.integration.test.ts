// loader의 멱등성 통합 테스트 — 실제 Neo4j에 연결해 두 번 적재해도 노드/관계 수가 불변임을 검증한다.
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';

const graph = {
  entities: [
    { key: entityKey('Incident', '2026-001'), type: 'Incident' as const, name: '2026-001' },
    { key: entityKey('Cause', '전기적 요인'), type: 'Cause' as const, name: '전기적 요인' },
  ],
  relations: [
    { subjectKey: entityKey('Incident', '2026-001'), type: 'CAUSED_BY' as const, objectKey: entityKey('Cause', '전기적 요인') },
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
    await loadGraph(graph, 101);
    const n1 = await count('Entity'), r1 = await count('REL');
    await loadGraph(graph, 101);
    expect(await count('Entity')).toBe(n1);
    expect(await count('REL')).toBe(r1);
    expect(n1).toBe(2);
    expect(r1).toBe(1);
  });
});
