// relation-add.integration.test.ts — 실 Neo4j 통합. VITEST_INTEGRATION=1에서만.
// ZZTEST_ 마커 노드만 생성/삭제(dev 그래프 무손상). blanket delete 절대 금지.
import { VerifiedOntologyId } from './verified-ontology-id.js';
import { describe, it, expect, afterEach, beforeAll, afterAll } from 'vitest';
import { bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { cleanupMarked, edgeCount, makeNode } from './__fixtures__/neo4j.js';
import { addRelation } from './relation-add.js';
import { GraphTargetMissingError, OntologyConformanceError } from './graph-mutation-guard.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const OID = 9 as VerifiedOntologyId;
// 남의 온톨로지 역할 — 같은 키 공간을 쓰지만 addRelation(…, OID, …) 에는 보이지 않아야 한다.
const OTHER_OID = 900022 as VerifiedOntologyId;


describe('addRelation (실 Neo4j)', () => {
  beforeAll(async () => { await bootstrapConstraints(); await cleanupMarked(); });
  afterEach(async () => { await cleanupMarked(); });
  afterAll(async () => { await closeDriver(); });

  // CORE_ONTOLOGY 허용 트리플(Incident -CAUSED_BY-> Cause)에 맞춰 끝점 타입을 구성한다.
  const kA = entityKey(entityTypeId(CORE_ONTOLOGY, 'Incident'), 'ZZTEST_2026-001');
  const kB = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_누전');
  const mkA = () => makeNode(kA, 'ZZTEST_2026-001', 'Incident', OID);
  const mkB = () => makeNode(kB, 'ZZTEST_누전', 'Cause', OID);

  it('양 끝점이 존재하면 엣지를 MERGE한다', async () => {
    await mkA(); await mkB();
    await addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kA, 'CAUSED_BY', kB, [7]);
    expect(await edgeCount(kA, kB)).toBe(1);
  });

  it('끝점이 없으면 엣지를 만들지 않고 실패를 던진다 — 무음 유실 방지(#310)', async () => {
    await mkA(); // kB 없음
    await expect(addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kA, 'CAUSED_BY', kB, [7]))
      .rejects.toThrow(GraphTargetMissingError);
    expect(await edgeCount(kA, kB)).toBe(0);
  });

  it('이미 같은 엣지가 있으면 성공한다(멱등) — 새 엣지 생성 수가 아니라 끝점 바인딩으로 판정', async () => {
    await mkA(); await mkB();
    await addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kA, 'CAUSED_BY', kB, [7]);
    await expect(addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kA, 'CAUSED_BY', kB, [8])).resolves.toBeUndefined();
    expect(await edgeCount(kA, kB)).toBe(1);
  });

  it('온톨로지에 없는 관계 타입은 엣지를 만들지 않고 거부한다(#319)', async () => {
    await mkA(); await mkB();
    await expect(addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kA, 'ZZTEST_UNKNOWN_REL', kB, [7]))
      .rejects.toThrow(OntologyConformanceError);
    expect(await edgeCount(kA, kB)).toBe(0);
  });

  it('허용되지 않은 (주어타입, 관계, 목적어타입) 조합은 엣지를 만들지 않고 거부한다(#319)', async () => {
    // Cause -CAUSED_BY-> Cause 는 온톨로지에 없는 트리플이다(허용: Incident -> Cause).
    const kC = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_과부하');
    await makeNode(kC, 'ZZTEST_과부하', 'Cause', OID); await mkB();
    await expect(addRelation(CORE_ONTOLOGY, 9 as VerifiedOntologyId, kC, 'CAUSED_BY', kB, [7]))
      .rejects.toThrow(OntologyConformanceError);
    expect(await edgeCount(kC, kB)).toBe(0);
  });

  // 크로스 온톨로지 격리. 양 끝점 술어가 없으면 추측 가능한 키 하나로 두 그래프를 잇는 엣지를
  // 그을 수 있었다 — 그렇게 생긴 엣지는 읽기측 술어를 통과하지 못해 조용히 사라지는 엣지가 된다.
  it('목적어가 다른 온톨로지면 엣지를 만들지 않는다', async () => {
    await mkA();
    const kForeign = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_남의누전');
    await makeNode(kForeign, 'ZZTEST_남의누전', 'Cause', OTHER_OID);
    await expect(addRelation(CORE_ONTOLOGY, OID, kA, 'CAUSED_BY', kForeign, [7]))
      .rejects.toThrow(GraphTargetMissingError);
    expect(await edgeCount(kA, kForeign)).toBe(0);
  });

  // 술어가 "항상 실패"가 아니라 진짜로 온톨로지를 가르는지 — 같은 두 노드를 남의 스코프로 부르면
  // 대칭적으로 이쪽이 막혀야 한다.
  it('주어가 다른 온톨로지면 엣지를 만들지 않는다(대칭 확인)', async () => {
    const kForeignIncident = entityKey(entityTypeId(CORE_ONTOLOGY, 'Incident'), 'ZZTEST_남의사건');
    await makeNode(kForeignIncident, 'ZZTEST_남의사건', 'Incident', OTHER_OID);
    await mkB();
    await expect(addRelation(CORE_ONTOLOGY, OID, kForeignIncident, 'CAUSED_BY', kB, [7]))
      .rejects.toThrow(GraphTargetMissingError);
    expect(await edgeCount(kForeignIncident, kB)).toBe(0);
    // 남의 스코프로 부르면 같은 조합이 성립한다 — 술어가 스코프를 가르는 것이지 무조건 막는 게 아니다.
    await makeNode(kB, 'ZZTEST_누전', 'Cause', OTHER_OID);
    await expect(addRelation(CORE_ONTOLOGY, OTHER_OID, kForeignIncident, 'CAUSED_BY', kB, [7]))
      .resolves.toBeUndefined();
    expect(await edgeCount(kForeignIncident, kB)).toBe(1);
  });

  // 읽기 오라클 차단: 남의 끝점은 conformance 409(끝점 타입을 사유 문구에 싣는다)에 닿기 전에
  // 대상 부재로 갈려야 한다 — 아니면 그 문구가 남의 노드 타입을 알려주는 조회 수단이 된다.
  it('남의 온톨로지 끝점은 타입을 노출하는 conformance 사유가 아니라 대상 부재로 실패한다', async () => {
    await mkA();
    // Cause -CAUSED_BY-> Cause 는 허용되지 않는 트리플 — 같은 온톨로지였다면 conformance 로 갈린다.
    const kForeignCause = entityKey(entityTypeId(CORE_ONTOLOGY, 'Cause'), 'ZZTEST_남의과부하');
    await makeNode(kForeignCause, 'ZZTEST_남의과부하', 'Cause', OTHER_OID);
    const err = await addRelation(CORE_ONTOLOGY, OID, kForeignCause, 'CAUSED_BY', kA, [7])
      .catch((e: Error) => e);
    expect(err).toBeInstanceOf(GraphTargetMissingError);
    expect((err as Error).message).not.toMatch(/Cause|Incident/); // 남의 노드 타입이 새지 않는다.
  });
});
