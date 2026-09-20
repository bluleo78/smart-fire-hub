// 실제 Neo4j 필요: `pnpm db:up` 후 실행. 미기동 시 이 파일은 실패한다.
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import neo4j from 'neo4j-driver';
import { getSession, bootstrapConstraints, closeDriver, readWholeGraph } from './neo4j-client.js';

afterAll(async () => { await closeDriver(); });

describe('neo4j-client (integration)', () => {
  it('제약을 부트스트랩하고 세션으로 쿼리할 수 있다', async () => {
    await bootstrapConstraints();
    const session = getSession();
    try {
      const r = await session.run('RETURN 1 AS n');
      expect(r.records[0].get('n').toNumber()).toBe(1);
    } finally { await session.close(); }
  });
});

// 5-6: 엔티티 타입 리네임이 entity_type_id 보존 기반의 순수 DB 연산이 되어(entityKey가 typeId 기반)
// Neo4j 마이그레이션(renameEntityType)이 불필요해졌다 — 이 describe 블록 전체를 제거했다.

// 크로스 온톨로지(=크로스 테넌트) 격리 회귀 가드. 목 기반 단위 테스트는 "쿼리에 술어가 들어 있다"까지만
// 증명하므로, 술어가 실제로 남의 노드를 걸러내는지는 실 DB 에 두 온톨로지를 함께 심어야만 증명된다 —
// 온톨로지 하나만 심은 테스트는 술어를 통째로 지워도 통과한다(공허한 테스트).
describe('readWholeGraph 온톨로지 스코프 (integration)', () => {
  const OWN = 900001; // 테스트 전용 대역 — 실 데이터의 ontologyId 와 겹치지 않게 크게 잡는다.
  const OTHER = 900002;
  const keys = ['scope-test:own-a', 'scope-test:own-b', 'scope-test:other-a'];

  afterAll(async () => {
    const session = getSession();
    try {
      await session.run('MATCH (n:Entity) WHERE n.key IN $keys DETACH DELETE n', { keys });
    } finally { await session.close(); }
  });

  // 시딩은 beforeAll 로 둔다 — 아래 두 it 이 같은 데이터를 보되, 어느 하나만 단독 실행해도
  // (it.only / -t 필터 / 순서 셔플) 전제가 성립해야 한다. 앞 테스트의 CREATE 에 기대면 단독
  // 실행 시 0행을 보고 실패한다.
  beforeAll(async () => {
    await bootstrapConstraints();
    const session = getSession();
    try {
      await session.run('MATCH (n:Entity) WHERE n.key IN $keys DETACH DELETE n', { keys });
      await session.run(
        `CREATE (a:Entity {key: $ka, type: 'Incident', name: '내것A', ontologyId: $own})
         CREATE (b:Entity {key: $kb, type: 'Building', name: '내것B', ontologyId: $own})
         CREATE (c:Entity {key: $kc, type: 'Incident', name: '남의것', ontologyId: $other})
         CREATE (a)-[:REL {type: 'OCCURRED_AT'}]->(b)
         CREATE (a)-[:REL {type: 'CROSS'}]->(c)`,
        { ka: keys[0], kb: keys[1], kc: keys[2], own: neo4j.int(OWN), other: neo4j.int(OTHER) },
      );
    } finally { await session.close(); }
  });

  it('요청한 온톨로지의 노드만 반환하고, 다른 온톨로지의 노드·엣지는 제외한다', async () => {
    const g = await readWholeGraph(OWN);
    const got = g.nodes.filter((n) => keys.includes(n.key)).map((n) => n.key).sort();
    expect(got).toEqual([keys[0], keys[1]]);
    expect(got).not.toContain(keys[2]);

    // 한쪽 끝이 다른 온톨로지인 엣지(CROSS)는 딸려 나오면 안 된다 — 엣지만 새도 남의 노드 key 가 노출된다.
    const ourEdges = g.edges.filter((e) => keys.includes(e.subjectKey) || keys.includes(e.objectKey));
    expect(ourEdges).toEqual([{ subjectKey: keys[0], type: 'OCCURRED_AT', objectKey: keys[1] }]);
  });

  it('다른 온톨로지를 요청하면 그쪽 노드만 반환한다(대칭 확인)', async () => {
    const g = await readWholeGraph(OTHER);
    const got = g.nodes.filter((n) => keys.includes(n.key)).map((n) => n.key);
    expect(got).toEqual([keys[2]]);
  });
});
