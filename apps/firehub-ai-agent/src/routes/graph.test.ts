import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';

vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn() }));
vi.mock('../middleware/auth.js', () => ({
  internalAuth: (_req: unknown, _res: unknown, next: () => void) => next(),
}));

import { readWholeGraph } from '../graphrag/neo4j-client.js';
import graphRouter from './graph.js';

const app = express();
app.use(express.json());
app.use('/agent', graphRouter);

describe('graph routes', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GET /agent/graph 는 전체 그래프를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockResolvedValue({
      nodes: [{ key: 'a', type: 'Incident', name: 'x', sourceChunkCount: 1 }],
      edges: [],
    });
    const res = await request(app).get('/agent/graph');
    expect(res.status).toBe(200);
    expect(res.body.nodes).toHaveLength(1);
  });

  it('GET /agent/graph 는 실패 시 502를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockRejectedValue(new Error('neo4j down'));
    const res = await request(app).get('/agent/graph');
    expect(res.status).toBe(502);
  });

  // 5-6: 엔티티 타입 리네임이 entity_type_id 보존 기반의 순수 DB 연산이 되어 Neo4j 마이그레이션
  // 라우트(5-5의 POST /agent/graph/rename-type)가 불필요해져 제거했다 — 관련 테스트도 함께 제거.
});
