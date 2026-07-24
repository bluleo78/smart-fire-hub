import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { getSession, closeDriver } from './neo4j-client.js';
import { setEntityProperty } from './property-mutation.js';

// 실 Neo4j에 노드를 심고 setEntityProperty로 속성이 실제 write되는지 검증한다
// (동의어 병합 때 getSession 목킹이 실버그를 놓친 전례 → seam은 실 DB로 확인).
describe('setEntityProperty (integration)', () => {
  const KEY = 'test-int:피해액노드';
  beforeAll(async () => {
    const s = getSession();
    try {
      await s.run('MERGE (n:Entity {key:$k}) SET n.type=$t, n.name=$n', { k: KEY, t: 'Incident', n: '테스트화재' });
    } finally { await s.close(); }
  });
  afterAll(async () => {
    const s = getSession();
    try { await s.run('MATCH (n:Entity {key:$k}) DETACH DELETE n', { k: KEY }); } finally { await s.close(); }
    await closeDriver();
  });

  it('number 속성이 노드에 write된다', async () => {
    await setEntityProperty(KEY, '피해액', 'number', '30000000');
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.피해액 AS v', { k: KEY });
      expect(r.records[0].get('v').toNumber?.() ?? r.records[0].get('v')).toBe(30000000);
    } finally { await s.close(); }
  });
});
