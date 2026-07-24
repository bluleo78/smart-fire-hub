// entity-add.integration.test.ts — 실 Neo4j 통합 테스트.
// ⚠️ 공유 dev Neo4j(localhost:7687)를 사용한다 — 격리된 테스트 DB가 없다.
// 반드시 이름이 'ZZTEST_'로 시작하는 노드만 생성하고, 정리도 그 접두사로만 한정한다
// (synonym-merge.test.ts류의 `MATCH (n:Entity) DETACH DELETE n` 전체 삭제는 dev 데이터를 날리므로 금지).
import { afterEach, describe, expect, it } from 'vitest';
import { getSession, closeDriver } from './neo4j-client.js';
import { addEntity } from './entity-add.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

async function nodeExists(key: string): Promise<boolean> {
  const s = getSession();
  try {
    const r = await s.run('MATCH (n:Entity {key:$key}) RETURN count(n) AS c', { key });
    return r.records[0].get('c').toNumber() > 0;
  } finally { await s.close(); }
}

async function edgeCount(fromKey: string, toKey: string): Promise<number> {
  const s = getSession();
  try {
    const r = await s.run('MATCH (a:Entity {key:$a})-[x:REL]->(b:Entity {key:$b}) RETURN count(x) AS c', { a: fromKey, b: toKey });
    return r.records[0].get('c').toNumber();
  } finally { await s.close(); }
}

describe('addEntity (실 Neo4j)', () => {
  // ZZTEST_ 접두사 노드만 정리 — blanket delete 금지(공유 dev DB 보호).
  afterEach(async () => {
    const s = getSession();
    try { await s.run("MATCH (n:Entity) WHERE n.name STARTS WITH 'ZZTEST_' DETACH DELETE n"); }
    finally { await s.close(); }
    await closeDriver();
  });

  const typeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

  it('as-extracted 타입/이름으로 노드를 MERGE한다', async () => {
    await addEntity(CORE_ONTOLOGY, { entityType: 'Cause', name: 'ZZTEST_노후배선', sourceChunkIds: [9], relations: [] });
    expect(await nodeExists(entityKey(typeId, 'ZZTEST_노후배선'))).toBe(true);
  });

  it('끝점이 존재하는 관계만 MERGE하고, 부재 끝점 관계는 스킵한다', async () => {
    // 상대 끝점(과부하)을 먼저 만들어 둔다.
    await addEntity(CORE_ONTOLOGY, { entityType: 'Cause', name: 'ZZTEST_과부하', sourceChunkIds: [1], relations: [] });
    const otherKey = entityKey(typeId, 'ZZTEST_과부하');
    const missingKey = entityKey(typeId, 'ZZTEST_존재하지않음');
    await addEntity(CORE_ONTOLOGY, {
      entityType: 'Cause', name: 'ZZTEST_노후배선', sourceChunkIds: [9],
      relations: [
        { relType: 'CAUSED_BY', direction: 'out', otherKey },      // 끝점 존재 → 생성
        { relType: 'CAUSED_BY', direction: 'out', otherKey: missingKey }, // 끝점 부재 → 스킵
      ],
    });
    const selfKey = entityKey(typeId, 'ZZTEST_노후배선');
    expect(await edgeCount(selfKey, otherKey)).toBe(1);
    expect(await edgeCount(selfKey, missingKey)).toBe(0);
  });
});
