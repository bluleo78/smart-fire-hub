// 실제 Neo4j 필요: `pnpm db:up` 후 실행. 미기동 시 이 파일은 실패한다.
import { describe, it, expect, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client.js';

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
