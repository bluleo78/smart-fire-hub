// relation-add.integration.test.ts — 실 Neo4j 통합. VITEST_INTEGRATION=1에서만.
// ZZTEST_ 마커 노드만 생성/삭제(dev 그래프 무손상). blanket delete 절대 금지.
import { describe, it, expect, afterEach, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { addRelation } from './relation-add.js';
import { GraphTargetMissingError, OntologyConformanceError } from './graph-mutation-guard.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const MARK = 'ZZTEST_';
// 노드 타입은 온톨로지 트리플 검증(#319)의 입력이므로 호출측이 명시한다.
async function makeNode(key: string, name: string, type: string): Promise<void> {
  const s = getSession();
  try { await s.run('MERGE (n:Entity {key:$key}) SET n.name=$name, n.type=$type', { key, name, type }); }
  finally { await s.close(); }
}
async function edgeCount(a: string, b: string): Promise<number> {
  const s = getSession();
  try { const r = await s.run('MATCH (a:Entity {key:$a})-[x:REL]->(b:Entity {key:$b}) RETURN count(x) AS c', { a, b }); return r.records[0].get('c').toNumber(); }
  finally { await s.close(); }
}
async function cleanupMarked(): Promise<void> {
  const s = getSession();
  try { await s.run("MATCH (n:Entity) WHERE n.name STARTS WITH $mark DETACH DELETE n", { mark: MARK }); }
  finally { await s.close(); }
}

describe('addRelation (실 Neo4j)', () => {
  beforeAll(async () => { await bootstrapConstraints(); await cleanupMarked(); });
  afterEach(async () => { await cleanupMarked(); });
  afterAll(async () => { await closeDriver(); });

  // CORE_ONTOLOGY 허용 트리플(Incident -CAUSED_BY-> Cause)에 맞춰 끝점 타입을 구성한다.
  const kA = entityKey(entityTypeId(CORE_ONTOLOGY, 'Incident'), 'ZZTEST_2026-001');
  const kB = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_누전');
  const mkA = () => makeNode(kA, 'ZZTEST_2026-001', 'Incident');
  const mkB = () => makeNode(kB, 'ZZTEST_누전', 'Cause');

  it('양 끝점이 존재하면 엣지를 MERGE한다', async () => {
    await mkA(); await mkB();
    await addRelation(CORE_ONTOLOGY, kA, 'CAUSED_BY', kB, [7]);
    expect(await edgeCount(kA, kB)).toBe(1);
  });

  it('끝점이 없으면 엣지를 만들지 않고 실패를 던진다 — 무음 유실 방지(#310)', async () => {
    await mkA(); // kB 없음
    await expect(addRelation(CORE_ONTOLOGY, kA, 'CAUSED_BY', kB, [7]))
      .rejects.toThrow(GraphTargetMissingError);
    expect(await edgeCount(kA, kB)).toBe(0);
  });

  it('이미 같은 엣지가 있으면 성공한다(멱등) — 새 엣지 생성 수가 아니라 끝점 바인딩으로 판정', async () => {
    await mkA(); await mkB();
    await addRelation(CORE_ONTOLOGY, kA, 'CAUSED_BY', kB, [7]);
    await expect(addRelation(CORE_ONTOLOGY, kA, 'CAUSED_BY', kB, [8])).resolves.toBeUndefined();
    expect(await edgeCount(kA, kB)).toBe(1);
  });

  it('온톨로지에 없는 관계 타입은 엣지를 만들지 않고 거부한다(#319)', async () => {
    await mkA(); await mkB();
    await expect(addRelation(CORE_ONTOLOGY, kA, 'ZZTEST_UNKNOWN_REL', kB, [7]))
      .rejects.toThrow(OntologyConformanceError);
    expect(await edgeCount(kA, kB)).toBe(0);
  });

  it('허용되지 않은 (주어타입, 관계, 목적어타입) 조합은 엣지를 만들지 않고 거부한다(#319)', async () => {
    // Cause -CAUSED_BY-> Cause 는 온톨로지에 없는 트리플이다(허용: Incident -> Cause).
    const kC = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_과부하');
    await makeNode(kC, 'ZZTEST_과부하', 'Cause'); await mkB();
    await expect(addRelation(CORE_ONTOLOGY, kC, 'CAUSED_BY', kB, [7]))
      .rejects.toThrow(OntologyConformanceError);
    expect(await edgeCount(kC, kB)).toBe(0);
  });
});
