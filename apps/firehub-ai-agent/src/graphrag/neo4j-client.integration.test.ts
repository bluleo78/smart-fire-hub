// 실제 Neo4j 필요: `pnpm db:up` 후 실행. 미기동 시 이 파일은 실패한다.
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver, renameEntityType } from './neo4j-client.js';
import { loadGraph } from './loader.js';
import { entityKey } from './resolver.js';

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

// 5-5: 실 Neo4j 대상 — 리네임 후 key 접두어와 type이 모두 새 이름으로 갱신되는지 검증.
describe('renameEntityType (integration)', () => {
  beforeAll(async () => {
    await bootstrapConstraints();
    const s = getSession();
    try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); } // 테스트 격리
  });

  it('key 접두어와 type을 새 타입명으로 재작성한다', async () => {
    const oldKey = entityKey('Cause', '전기적 요인');
    await loadGraph(
      { entities: [{ key: oldKey, type: 'Cause', name: '전기적 요인' }], relations: [] },
      901,
      1,
    );

    const migrated = await renameEntityType('Cause', 'RootCause');
    expect(migrated).toBe(1);

    const session = getSession();
    try {
      const r = await session.run('MATCH (n:Entity {name: $name}) RETURN n.key AS key, n.type AS type', {
        name: '전기적 요인',
      });
      expect(r.records[0].get('key')).toBe(entityKey('RootCause', '전기적 요인'));
      expect(r.records[0].get('type')).toBe('RootCause');
    } finally { await session.close(); }
  });
});
