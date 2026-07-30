// schema-version-type.integration.test.ts — #308 회귀 가드. 실 Neo4j 통합, VITEST_INTEGRATION=1에서만.
// ZZTEST_ 마커 노드만 생성/삭제(dev 그래프 무손상). blanket delete 절대 금지.
//
// 왜 "저장 타입"을 검증하는가: 읽기측(neo4j-client.toJsNumber)이 FLOAT/INTEGER를 모두 받아주므로
// loadGraph → readWholeGraph 왕복값 검증은 쓰기 버그가 있어도 통과한다(무의미한 가드). 실제 결함은
// "Neo4j에 무엇으로 저장되는가"이므로 valueType()으로 저장 타입을 직접 확인한다.
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver, readWholeGraph } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { addRelation } from './relation-add.js';
import { entityKey } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const MARK = 'ZZTEST_';

async function cleanupMarked(): Promise<void> {
  const s = getSession();
  try { await s.run('MATCH (n:Entity) WHERE n.name STARTS WITH $mark DETACH DELETE n', { mark: MARK }); }
  finally { await s.close(); }
}

async function nodeValueType(key: string): Promise<string> {
  const s = getSession();
  try {
    const r = await s.run('MATCH (n:Entity {key:$key}) RETURN valueType(n.schemaVersion) AS t', { key });
    return r.records[0].get('t');
  } finally { await s.close(); }
}

async function relValueType(a: string, b: string): Promise<string> {
  const s = getSession();
  try {
    const r = await s.run(
      'MATCH (:Entity {key:$a})-[x:REL]->(:Entity {key:$b}) RETURN valueType(x.schemaVersion) AS t', { a, b });
    return r.records[0].get('t');
  } finally { await s.close(); }
}

const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');
const kA = entityKey(incidentId, 'ZZTEST_308사건');
const kB = entityKey(causeId, 'ZZTEST_308원인');

const graph = {
  entities: [
    { key: kA, type: 'Incident' as const, name: 'ZZTEST_308사건' },
    { key: kB, type: 'Cause' as const, name: 'ZZTEST_308원인' },
  ],
  relations: [{ subjectKey: kA, type: 'CAUSED_BY' as const, objectKey: kB }],
};

describe('schemaVersion 저장 타입 (실 Neo4j, #308)', () => {
  beforeAll(async () => { await bootstrapConstraints(); await cleanupMarked(); });
  afterEach(async () => { await cleanupMarked(); });
  afterAll(async () => { await closeDriver(); });

  it('loadGraph는 노드/엣지 schemaVersion을 INTEGER로 저장한다', async () => {
    await loadGraph(graph, 30801, 1);
    expect(await nodeValueType(kA)).toMatch(/^INTEGER/);
    expect(await relValueType(kA, kB)).toMatch(/^INTEGER/);
  });

  it('addRelation도 엣지 schemaVersion을 INTEGER로 저장한다', async () => {
    await loadGraph({ entities: graph.entities, relations: [] }, 30802, 1);
    await addRelation(CORE_ONTOLOGY.schemaVersion, kA, 'CAUSED_BY', kB, [30802]);
    expect(await relValueType(kA, kB)).toMatch(/^INTEGER/);
  });

  // FLOAT로 적재된 과거 데이터가 섞여 있어도 그래프 전체 읽기가 죽지 않아야 한다(읽기측 방어).
  it('FLOAT로 저장된 레거시 노드가 있어도 readWholeGraph가 throw하지 않는다', async () => {
    const s = getSession();
    try {
      await s.run(
        'MERGE (n:Entity {key:$key}) SET n.name=$name, n.type=$type, n.schemaVersion=1.0',
        { key: kA, name: 'ZZTEST_308사건', type: 'Incident' },
      );
    } finally { await s.close(); }
    expect(await nodeValueType(kA)).toMatch(/^FLOAT/); // 전제 확인 — 실제로 FLOAT로 심어졌는지

    const g = await readWholeGraph();
    expect(g.nodes.find((n) => n.key === kA)?.schemaVersion).toBe(1);
  });
});
