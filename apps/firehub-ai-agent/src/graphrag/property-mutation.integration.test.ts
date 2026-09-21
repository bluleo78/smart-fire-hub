import { VerifiedOntologyId } from './verified-ontology-id.js';
import neo4j from 'neo4j-driver';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { getSession, closeDriver } from './neo4j-client.js';
import { setEntityProperty } from './property-mutation.js';
import { GraphTargetMissingError } from './graph-mutation-guard.js';

// 테스트용 온톨로지 id — 모든 쓰기는 이 스코프 안에서만 일어나야 한다.
const TEST_ONTOLOGY_ID = 9 as VerifiedOntologyId;
// 남의 온톨로지 역할. 같은 키 공간을 쓰지만 스코프가 달라 건드려지면 안 된다.
const OTHER_ONTOLOGY_ID = 900021 as VerifiedOntologyId;


// 실 Neo4j에 노드를 심고 setEntityProperty로 속성이 실제 write되는지 검증한다
// (동의어 병합 때 getSession 목킹이 실버그를 놓친 전례 → seam은 실 DB로 확인).
describe('setEntityProperty (integration)', () => {
  const KEY = 'test-int:피해액노드';
  // 남의 온톨로지 소속 노드. setEntityProperty 는 이 키를 "없는 키"로 취급해야 한다.
  const FOREIGN_KEY = 'test-int:남의피해액노드';
  beforeAll(async () => {
    const s = getSession();
    try {
      // ontologyId 를 반드시 스탬프한다 — 적재 경로(loader/entity-add)가 항상 찍는 값이고,
      // 이게 없으면 스코프 술어가 픽스처 결함으로 항상 거짓이 되어 테스트가 의미를 잃는다.
      await s.run('MERGE (n:Entity {key:$k}) SET n.type=$t, n.name=$n, n.ontologyId=$oid',
        { k: KEY, t: 'Incident', n: '테스트화재', oid: neo4j.int(TEST_ONTOLOGY_ID) });
      await s.run('MERGE (n:Entity {key:$k}) SET n.type=$t, n.name=$n, n.ontologyId=$oid',
        { k: FOREIGN_KEY, t: 'Incident', n: '남의화재', oid: neo4j.int(OTHER_ONTOLOGY_ID) });
    } finally { await s.close(); }
  });
  afterAll(async () => {
    const s = getSession();
    try {
      await s.run('MATCH (n:Entity) WHERE n.key IN [$k, $f] DETACH DELETE n', { k: KEY, f: FOREIGN_KEY });
    } finally { await s.close(); }
    await closeDriver();
  });

  it('number 속성이 노드에 write된다', async () => {
    await setEntityProperty(TEST_ONTOLOGY_ID, KEY, '피해액', 'number', '30000000');
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.피해액 AS v', { k: KEY });
      expect(r.records[0].get('v').toNumber?.() ?? r.records[0].get('v')).toBe(30000000);
    } finally { await s.close(); }
  });

  // #311 — 형식을 벗어난 date 정정값이 실제로 노드에 남지 않는지 실 DB로 확인한다.
  // 예전에는 '작년겨울'이 그대로 write되어 이 속성을 날짜로 읽는 질의가 전부 깨졌다.
  it('date 정정값이 형식을 벗어나면 거절하고 노드를 변경하지 않는다', async () => {
    await expect(setEntityProperty(TEST_ONTOLOGY_ID, KEY, '발생일', 'date', '작년겨울')).rejects.toThrow(/YYYY-MM-DD/);
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.발생일 AS v', { k: KEY });
      expect(r.records[0].get('v')).toBeNull();
    } finally { await s.close(); }
  });

  it('관용 표기 date 정정값은 YYYY-MM-DD로 정규화되어 write된다', async () => {
    await setEntityProperty(TEST_ONTOLOGY_ID, KEY, '발생일', 'date', '2026.1.5');
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.발생일 AS v', { k: KEY });
      expect(r.records[0].get('v')).toBe('2026-01-05');
    } finally { await s.close(); }
  });

  // 크로스 온톨로지 쓰기 차단. entityKey 는 `<entity_type_id>:<name>` 이라 추측 가능하고 Neo4j 는
  // 전 테넌트 공유 DB 라, 스코프 술어가 없으면 이 호출이 남의 노드를 그대로 덮어썼다.
  it('다른 온톨로지의 노드는 "없는 노드"와 똑같이 취급해 write하지 않는다', async () => {
    await expect(setEntityProperty(TEST_ONTOLOGY_ID, FOREIGN_KEY, '피해액', 'number', '99'))
      .rejects.toThrow(GraphTargetMissingError);
    const s = getSession();
    try {
      const r = await s.run('MATCH (n:Entity {key:$k}) RETURN n.피해액 AS v', { k: FOREIGN_KEY });
      expect(r.records[0].get('v')).toBeNull(); // 남의 노드는 손대지 않았다.
    } finally { await s.close(); }
  });

  // 사유 문구가 갈리면 그 자체가 "그 키가 남의 그래프에 존재한다"를 알려주는 읽기 오라클이 된다.
  it('남의 키와 없는 키의 실패 사유가 구별되지 않는다(읽기 오라클 방지)', async () => {
    const foreign = await setEntityProperty(TEST_ONTOLOGY_ID, FOREIGN_KEY, '피해액', 'number', '99')
      .catch((e: Error) => e);
    const absent = await setEntityProperty(TEST_ONTOLOGY_ID, 'test-int:존재하지않는키', '피해액', 'number', '99')
      .catch((e: Error) => e);
    expect((foreign as Error).constructor).toBe((absent as Error).constructor);
    // 키 문자열만 다르고 나머지 문구는 동일해야 한다.
    expect((foreign as Error).message.replace(FOREIGN_KEY, 'KEY'))
      .toBe((absent as Error).message.replace('test-int:존재하지않는키', 'KEY'));
  });
});
