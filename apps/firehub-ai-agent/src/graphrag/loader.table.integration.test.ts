// loadTableGraph 통합 테스트 — 실 Neo4j에 sourceDatasetIds 누적·멱등을 검증한다.
// 공유 dev/test Neo4j 보호: ZZTEST_ 접두 노드만 생성하고 그 스코프만 정리한다(blanket 삭제 금지).
import { describe, it, expect, beforeEach, afterAll } from 'vitest';
import { isInt } from 'neo4j-driver';
import { loadTableGraph } from './loader.js';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { ResolvedGraph } from './resolver.js';

const CLEANUP = "MATCH (n:Entity) WHERE n.name STARTS WITH 'ZZTEST_' DETACH DELETE n";

async function clean() {
  const s = getSession();
  try { await s.run(CLEANUP); } finally { await s.close(); }
}
async function countScoped(): Promise<number> {
  const s = getSession();
  try {
    const r = await s.run("MATCH (n:Entity) WHERE n.name STARTS WITH 'ZZTEST_' RETURN count(n) AS c");
    return r.records[0].get('c').toNumber();
  } finally { await s.close(); }
}

const graph: ResolvedGraph = {
  entities: [
    { key: '10:zztest_a', type: 'Incident', name: 'ZZTEST_A' },
    { key: '11:zztest_b', type: 'Building', name: 'ZZTEST_B' },
  ],
  relations: [{ subjectKey: '10:zztest_a', type: 'OCCURRED_AT', objectKey: '11:zztest_b' }],
};

describe('loadTableGraph (integration)', () => {
  beforeEach(async () => { await bootstrapConstraints(); await clean(); });
  afterAll(async () => { await clean(); await closeDriver(); });

  it('두 번 적재해도 노드 수가 불변이고 sourceDatasetIds가 누적된다', async () => {
    await loadTableGraph(graph, 501, 1);
    expect(await countScoped()).toBe(2);
    await loadTableGraph(graph, 502, 1); // 다른 데이터셋 재적재 → 멱등 + provenance 누적
    expect(await countScoped()).toBe(2);

    const s = getSession();
    try {
      const r = await s.run("MATCH (n:Entity {key:'10:zztest_a'}) RETURN n.sourceDatasetIds AS ids");
      // neo4j-driver는 JS number 파라미터를 Integer로 자동 승격하지 않아(코드상 $datasetId가
      // plain number로 전달됨) 저장된 배열 원소가 Integer 객체가 아닌 plain number로 돌아올 수 있다.
      // 두 형태 모두 허용해 드라이버 표현 방식에 테스트가 결합되지 않게 한다.
      const ids: number[] = r.records[0].get('ids').map((x: unknown) => (isInt(x) ? x.toNumber() : (x as number)));
      expect(ids.sort()).toEqual([501, 502]);
    } finally { await s.close(); }
  });
});
