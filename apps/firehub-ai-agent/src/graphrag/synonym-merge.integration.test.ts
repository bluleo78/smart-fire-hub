// synonym-merge.integration.test.ts — 실 Neo4j 통합. VITEST_INTEGRATION=1에서만.
//
// 왜 통합이 필요한가: mergeEntities 의 단위 테스트는 getSession 을 모킹하므로 Cypher 문자열에
// 술어가 들어 있는지까지만 본다. 이 경로는 DETACH DELETE 로 노드를 **지우므로**, 술어가 실제
// 저장 형태(ontologyId 가 INTEGER 로 스탬프됨, #308)와 맞물려 진짜로 거르는지는 실 DB로만 증명된다.
//
// ⚠️ 공유 dev Neo4j 를 쓴다 — 이름이 'ZZTEST_'로 시작하는 노드만 만들고 그 접두사로만 정리한다.
// `MATCH (n:Entity) DETACH DELETE n` 같은 전체 삭제는 dev 그래프를 날리므로 절대 금지.
import { VerifiedOntologyId } from './verified-ontology-id.js';
import { describe, it, expect, afterEach, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { cleanupMarked, makeNode, nodeExists } from './__fixtures__/neo4j.js';
import { mergeEntities } from './synonym-merge.js';
import { GraphTargetMissingError } from './graph-mutation-guard.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const OID = 9 as VerifiedOntologyId;
// 남의 온톨로지 역할 — 같은 키 공간을 쓰지만 mergeEntities(…, OID, …) 에는 보이지 않아야 한다.
const OTHER_OID = 900024 as VerifiedOntologyId;
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

// 이 파일의 노드는 전부 Cause 타입이라 이름만 받는 얇은 래퍼를 둔다.
const cause = (name: string, ontologyId: number) => makeNode(entityKey(causeId, name), name, 'Cause', ontologyId);

describe('mergeEntities (실 Neo4j)', () => {
  beforeAll(async () => { await bootstrapConstraints(); await cleanupMarked(); });
  afterEach(async () => { await cleanupMarked(); });
  afterAll(async () => { await closeDriver(); });

  // 기준선 — 같은 온톨로지 안에서는 병합이 실제로 일어난다. 이게 없으면 아래 차단 테스트가
  // "술어가 항상 거짓"인 경우에도 통과해 버린다.
  it('같은 온톨로지의 두 노드는 병합되어 loser가 삭제된다', async () => {
    const shortKey = await cause('ZZTEST_누전', OID);
    const longKey = await cause('ZZTEST_분전반 누전', OID); // 더 긴 이름 = keeper

    await mergeEntities(CORE_ONTOLOGY, OID, 'Cause', 'ZZTEST_누전', 'ZZTEST_분전반 누전');

    expect(await nodeExists(longKey)).toBe(true);
    expect(await nodeExists(shortKey)).toBe(false);
  });

  // 핵심 — 추측 가능한 키 하나로 남의 온톨로지 노드를 지울 수 있으면 안 된다.
  it('한쪽이 다른 온톨로지면 병합하지 않고, 남의 노드를 지우지 않는다', async () => {
    const mine = await cause('ZZTEST_누전', OID);
    const foreign = await cause('ZZTEST_분전반 누전', OTHER_OID);

    await expect(mergeEntities(CORE_ONTOLOGY, OID, 'Cause', 'ZZTEST_누전', 'ZZTEST_분전반 누전'))
      .rejects.toThrow(GraphTargetMissingError);

    // 양쪽 모두 그대로 살아 있어야 한다 — 남의 노드는 물론, 내 노드도 병합되지 않았다.
    expect(await nodeExists(foreign)).toBe(true);
    expect(await nodeExists(mine)).toBe(true);
  });

  // 사유 문구가 갈리면 그 자체가 "그 이름의 노드가 남의 그래프에 있다"를 알려주는 읽기 오라클이 된다.
  it('남의 노드와 없는 노드의 실패 사유가 구별되지 않는다(읽기 오라클 방지)', async () => {
    await cause('ZZTEST_누전', OID);
    await cause('ZZTEST_분전반 누전', OTHER_OID);
    const foreignErr = await mergeEntities(CORE_ONTOLOGY, OID, 'Cause', 'ZZTEST_누전', 'ZZTEST_분전반 누전')
      .catch((e: Error) => e);
    await cleanupMarked();

    await cause('ZZTEST_누전', OID); // 상대는 아예 없는 상태
    const absentErr = await mergeEntities(CORE_ONTOLOGY, OID, 'Cause', 'ZZTEST_누전', 'ZZTEST_분전반 누전')
      .catch((e: Error) => e);

    expect((foreignErr as Error).constructor).toBe((absentErr as Error).constructor);
    expect((foreignErr as Error).message).toBe((absentErr as Error).message);
  });

  // 재배선 누수 — loser 가 남의 온톨로지 노드와 엣지로 이어져 있어도, 그 상대는 keeper 로 끌려오면
  // 안 된다(끌려오면 두 그래프가 이어지고, 남의 노드 key 가 이쪽 그래프에 박힌다).
  it('loser의 관계 중 상대가 다른 온톨로지면 keeper로 재배선하지 않는다', async () => {
    const loser = await cause('ZZTEST_누전', OID);
    await cause('ZZTEST_분전반 누전', OID); // keeper(더 긴 이름)
    const foreignNeighbor = await cause('ZZTEST_남의이웃', OTHER_OID);
    const mineNeighbor = await cause('ZZTEST_내이웃', OID);

    const s = getSession();
    try {
      await s.run(
        `MATCH (l:Entity {key:$loser}), (f:Entity {key:$foreign}), (m:Entity {key:$mine})
         CREATE (l)-[:REL {type:'CAUSED_BY'}]->(f)
         CREATE (l)-[:REL {type:'CAUSED_BY'}]->(m)`,
        { loser, foreign: foreignNeighbor, mine: mineNeighbor },
      );
    } finally { await s.close(); }

    await mergeEntities(CORE_ONTOLOGY, OID, 'Cause', 'ZZTEST_누전', 'ZZTEST_분전반 누전');

    const keeper = entityKey(causeId, 'ZZTEST_분전반 누전');
    const s2 = getSession();
    try {
      const r = await s2.run('MATCH (k:Entity {key:$k})-[:REL]->(o:Entity) RETURN o.key AS key', { k: keeper });
      const neighbors = r.records.map((rec) => rec.get('key'));
      expect(neighbors).toContain(mineNeighbor);      // 내 이웃은 끌려온다
      expect(neighbors).not.toContain(foreignNeighbor); // 남의 이웃은 끌려오지 않는다
    } finally { await s2.close(); }
  });
});
