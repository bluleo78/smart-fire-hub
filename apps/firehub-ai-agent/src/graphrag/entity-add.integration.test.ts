// entity-add.integration.test.ts — 실 Neo4j 통합 테스트.
// ⚠️ 공유 dev Neo4j(localhost:7687)를 사용한다 — 격리된 테스트 DB가 없다.
// 반드시 이름이 'ZZTEST_'로 시작하는 노드만 생성하고, 정리도 그 접두사로만 한정한다
// (synonym-merge.test.ts류의 `MATCH (n:Entity) DETACH DELETE n` 전체 삭제는 dev 데이터를 날리므로 금지).
import { VerifiedOntologyId } from './verified-ontology-id.js';
import { afterEach, describe, expect, it } from 'vitest';
import { getSession, closeDriver } from './neo4j-client.js';
import { cleanupMarked, edgeCount, nodeExists } from './__fixtures__/neo4j.js';
import { addEntity } from './entity-add.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';



describe('addEntity (실 Neo4j)', () => {
  // ZZTEST_ 접두사 노드만 정리 — blanket delete 금지(공유 dev DB 보호).
  afterEach(async () => {
    await cleanupMarked();
    await closeDriver();
  });

  const typeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

  it('as-extracted 타입/이름으로 노드를 MERGE한다', async () => {
    await addEntity(CORE_ONTOLOGY, 9 as VerifiedOntologyId, { entityType: 'Cause', name: 'ZZTEST_노후배선', sourceChunkIds: [9], relations: [] });
    expect(await nodeExists(entityKey(typeId, 'ZZTEST_노후배선'))).toBe(true);
  });

  it('끝점이 존재하는 관계만 MERGE하고, 부재 끝점 관계는 스킵한다', async () => {
    // 상대 끝점(과부하)을 먼저 만들어 둔다.
    await addEntity(CORE_ONTOLOGY, 9 as VerifiedOntologyId, { entityType: 'Cause', name: 'ZZTEST_과부하', sourceChunkIds: [1], relations: [] });
    const otherKey = entityKey(typeId, 'ZZTEST_과부하');
    const missingKey = entityKey(typeId, 'ZZTEST_존재하지않음');
    await addEntity(CORE_ONTOLOGY, 9 as VerifiedOntologyId, {
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

  it('ontologyId를 노드에 INTEGER로 스탬프한다(#678)', async () => {
    await addEntity(CORE_ONTOLOGY, 9 as VerifiedOntologyId, { entityType: 'Cause', name: 'ZZTEST_ontologyId스탬프', sourceChunkIds: [1], relations: [] });
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$key}) RETURN n.ontologyId AS id, valueType(n.ontologyId) AS t',
        { key: entityKey(typeId, 'ZZTEST_ontologyId스탬프') });
      expect(r.records[0].get('id').toNumber()).toBe(9);
      expect(r.records[0].get('t')).toMatch(/^INTEGER/);
    } finally { await s.close(); }
  });

  // 크로스 온톨로지 격리 — 보류 관계의 상대 끝점(otherKey)은 호출자가 준 문자열이라, 스코프가 없으면
  // 남의 온톨로지 노드로 엣지를 그어 두 그래프를 이어붙일 수 있었다(읽기측 양끝점 술어를 통과하지
  // 못해 조용히 사라지는 엣지가 된다).
  it('상대 끝점이 다른 온톨로지면 관계를 MERGE하지 않는다', async () => {
    // 남의 온톨로지(900023) 소속 상대 끝점을 실 적재 경로로 심는다.
    await addEntity(CORE_ONTOLOGY, 900023 as VerifiedOntologyId, { entityType: 'Cause', name: 'ZZTEST_남의과부하', sourceChunkIds: [1], relations: [] });
    const foreignKey = entityKey(typeId, 'ZZTEST_남의과부하');
    await addEntity(CORE_ONTOLOGY, 9 as VerifiedOntologyId, {
      entityType: 'Cause', name: 'ZZTEST_노후배선', sourceChunkIds: [9],
      relations: [{ relType: 'CAUSED_BY', direction: 'out', otherKey: foreignKey }],
    });
    expect(await edgeCount(entityKey(typeId, 'ZZTEST_노후배선'), foreignKey)).toBe(0);
    // 같은 스코프로 부르면 생성된다 — 술어가 스코프를 가르는 것이지 무조건 막는 게 아니다.
    await addEntity(CORE_ONTOLOGY, 900023 as VerifiedOntologyId, {
      entityType: 'Cause', name: 'ZZTEST_남의노후배선', sourceChunkIds: [9],
      relations: [{ relType: 'CAUSED_BY', direction: 'out', otherKey: foreignKey }],
    });
    expect(await edgeCount(entityKey(typeId, 'ZZTEST_남의노후배선'), foreignKey)).toBe(1);
  });
});
