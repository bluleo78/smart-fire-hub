import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { retrieve } from './retriever.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

const incidentId = entityTypeId(CORE_ONTOLOGY, 'Incident');
const buildingId = entityTypeId(CORE_ONTOLOGY, 'Building');
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');
const regulationId = entityTypeId(CORE_ONTOLOGY, 'Regulation');

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); }
  // 시드 그래프: 사건 2026-001 -(OCCURRED_AT)-> 건물, -(CAUSED_BY)-> 원인. 모두 chunkId 500에서 유래.
  await loadGraph({
    entities: [
      { key: entityKey(incidentId, '2026-001'), type: 'Incident', name: '2026-001' },
      { key: entityKey(buildingId, '중앙로 상가'), type: 'Building', name: '중앙로 상가' },
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
    ],
    relations: [
      { subjectKey: entityKey(incidentId, '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey(buildingId, '중앙로 상가') },
      { subjectKey: entityKey(incidentId, '2026-001'), type: 'CAUSED_BY', objectKey: entityKey(causeId, '전기적 요인') },
    ],
  }, 500, 1);
});
afterAll(async () => { await closeDriver(); });

describe('retrieve (integration)', () => {
  it('시드 청크로부터 서브그래프와 출처를 회수한다', async () => {
    // searchDocuments를 스텁: chunkId 500 하나 반환.
    const deps = {
      searchDocuments: async () => [{ chunkId: 500, fileName: 'report-01.md', content: '중앙로 상가건물 화재...' }],
    };
    const result = await retrieve(deps, '중앙로 상가건물 화재의 원인은?');
    const names = result.nodes.map((n) => n.name).sort();
    expect(names).toContain('2026-001');
    expect(names).toContain('전기적 요인'); // 1홉 확장으로 원인 도달
    expect(result.relations.some((r) => r.type === 'CAUSED_BY')).toBe(true);
    expect(result.sourceChunks[0].fileName).toBe('report-01.md');
  });

  it('허브 노드(고차수 규정)에 연결된 먼 노드는 반환하지 않는다 — 결함 A 회귀 테스트', async () => {
    // 시드 사건(2026-002)이 공용 허브 규정과 연결되고, 그 허브가 이 사건과 무관한
    // 다른 사건들(무관-001..010)과도 VIOLATED로 연결된 상황을 구성한다.
    // hubDegree 기본값(10) 이상이 되도록 무관 사건 10개를 만든다.
    const hubKey = entityKey(regulationId, '소방시설법 제12조');
    const seedIncidentKey = entityKey(incidentId, '2026-002');
    const unrelatedKeys = Array.from({ length: 10 }, (_, i) => entityKey(incidentId, `무관-${i}`));

    // 주의: loadGraph는 전달된 entities 전부에 sourceChunkId를 기록하므로, 허브 노드를
    // 시드 청크(600)로도 적재하면 허브 자체가 "시드"가 되어(항상 확장) 테스트 의도가 깨진다.
    // 먼저 허브+무관 엔티티를 별도 chunkId(601)로 적재해 허브 노드를 만든 뒤,
    // 시드(2026-002)와 그 관계만 chunkId 600으로 적재한다(관계 MATCH는 기존 허브 노드를 그대로 찾는다).
    await loadGraph({
      entities: [
        { key: hubKey, type: 'Regulation', name: '소방시설법 제12조' },
        ...unrelatedKeys.map((k, i) => ({ key: k, type: 'Incident' as const, name: `무관-${i}` })),
      ],
      relations: unrelatedKeys.map((k) => ({ subjectKey: k, type: 'VIOLATED' as const, objectKey: hubKey })),
    }, 601, 1);
    await loadGraph({
      entities: [
        { key: seedIncidentKey, type: 'Incident', name: '2026-002' },
      ],
      relations: [
        { subjectKey: seedIncidentKey, type: 'VIOLATED', objectKey: hubKey },
      ],
    }, 600, 1);

    const deps = {
      searchDocuments: async () => [{ chunkId: 600, fileName: 'report-02.md', content: '2026-002 화재...' }],
    };
    const result = await retrieve(deps, '2026-002 화재는 어떤 규정을 위반했는가?');
    const names = result.nodes.map((n) => n.name);
    expect(names).toContain('2026-002');
    expect(names).toContain('소방시설법 제12조'); // 허브 자체는 포함(직접 연결)
    // 허브를 거쳐야만 도달 가능한 무관 사건들은 제외되어야 한다.
    for (let i = 0; i < 10; i += 1) expect(names).not.toContain(`무관-${i}`);
  });
});
