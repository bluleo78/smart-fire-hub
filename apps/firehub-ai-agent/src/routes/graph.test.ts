import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';

vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn() }));
vi.mock('../middleware/auth.js', () => ({ internalAuth: (_req: any, _res: any, next: any) => next() }));

import { readWholeGraph } from '../graphrag/neo4j-client.js';
import graphRouter from './graph.js';

const app = express();
app.use('/agent', graphRouter);

describe('graph routes', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GET /agent/graph 는 전체 그래프를 반환한다', async () => {
    (readWholeGraph as any).mockResolvedValue({ nodes: [{ key: 'a', type: 'Incident', name: 'x', sourceChunkCount: 1 }], edges: [] });
    const res = await request(app).get('/agent/graph');
    expect(res.status).toBe(200);
    expect(res.body.nodes).toHaveLength(1);
  });

  it('GET /agent/graph 는 실패 시 502를 반환한다', async () => {
    (readWholeGraph as any).mockRejectedValue(new Error('neo4j down'));
    const res = await request(app).get('/agent/graph');
    expect(res.status).toBe(502);
  });
});
