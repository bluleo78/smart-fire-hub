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

  // #311 — 형식을 벗어난 date 정정값이 실제로 노드에 남지 않는지 실 DB로 확인한다.
  // 예전에는 '작년겨울'이 그대로 write되어 이 속성을 날짜로 읽는 질의가 전부 깨졌다.
  it('date 정정값이 형식을 벗어나면 거절하고 노드를 변경하지 않는다', async () => {
    await expect(setEntityProperty(KEY, '발생일', 'date', '작년겨울')).rejects.toThrow(/YYYY-MM-DD/);
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.발생일 AS v', { k: KEY });
      expect(r.records[0].get('v')).toBeNull();
    } finally { await s.close(); }
  });

  it('관용 표기 date 정정값은 YYYY-MM-DD로 정규화되어 write된다', async () => {
    await setEntityProperty(KEY, '발생일', 'date', '2026.1.5');
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.발생일 AS v', { k: KEY });
      expect(r.records[0].get('v')).toBe('2026-01-05');
    } finally { await s.close(); }
  });
});
