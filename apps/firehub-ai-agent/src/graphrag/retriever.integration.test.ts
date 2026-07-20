import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';
import { retrieve } from './retriever.js';

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); }
  // 시드 그래프: 사건 2026-001 -(OCCURRED_AT)-> 건물, -(CAUSED_BY)-> 원인. 모두 chunkId 500에서 유래.
  await loadGraph({
    entities: [
      { key: entityKey('Incident', '2026-001'), type: 'Incident', name: '2026-001' },
      { key: entityKey('Building', '중앙로 상가'), type: 'Building', name: '중앙로 상가' },
      { key: entityKey('Cause', '전기적 요인'), type: 'Cause', name: '전기적 요인' },
    ],
    relations: [
      { subjectKey: entityKey('Incident', '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey('Building', '중앙로 상가') },
      { subjectKey: entityKey('Incident', '2026-001'), type: 'CAUSED_BY', objectKey: entityKey('Cause', '전기적 요인') },
    ],
  }, 500);
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
});
