import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';

vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn(), renameEntityType: vi.fn() }));
vi.mock('../middleware/auth.js', () => ({
  internalAuth: (_req: unknown, _res: unknown, next: () => void) => next(),
}));

import { readWholeGraph, renameEntityType } from '../graphrag/neo4j-client.js';
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

  // 5-5: 리네임 마이그레이션 라우트 — api가 DB 리네임 커밋 직후 동기 호출한다.
  it('POST /agent/graph/rename-type 은 마이그레이션된 노드 수를 반환한다', async () => {
    vi.mocked(renameEntityType).mockResolvedValue(3);
    const res = await request(app).post('/agent/graph/rename-type').send({ oldType: 'Cause', newType: 'RootCause' });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ migrated: 3 });
    expect(renameEntityType).toHaveBeenCalledWith('Cause', 'RootCause');
  });

  it('POST /agent/graph/rename-type 은 oldType/newType 누락 시 400을 반환한다', async () => {
    const res = await request(app).post('/agent/graph/rename-type').send({ oldType: 'Cause' });
    expect(res.status).toBe(400);
    expect(renameEntityType).not.toHaveBeenCalled();
  });

  it('POST /agent/graph/rename-type 은 마이그레이션 실패 시 502를 반환한다', async () => {
    vi.mocked(renameEntityType).mockRejectedValue(new Error('constraint violation'));
    const res = await request(app).post('/agent/graph/rename-type').send({ oldType: 'Cause', newType: 'RootCause' });
    expect(res.status).toBe(502);
  });
});
