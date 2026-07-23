// graph 라우터 테스트 — /graph(기존)와 /graph/merge-entities(신규) 엔드포인트를 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';

const { mergeEntitiesMock } = vi.hoisted(() => ({ mergeEntitiesMock: vi.fn() }));
vi.mock('../graphrag/synonym-merge.js', () => ({ mergeEntities: mergeEntitiesMock }));
vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn() }));
// merge-entities 핸들러가 entityType→typeId 변환용 온톨로지를 fetch한다 — 실제 HTTP 호출 없이 고정 온톨로지로 대체.
vi.mock('../graphrag/ontology-source.js', () => ({ loadOntology: vi.fn().mockResolvedValue({ domain: 'test', schemaVersion: 1, entities: [], relations: [] }) }));

process.env.INTERNAL_SERVICE_TOKEN = 'test-internal-token';

import { readWholeGraph } from '../graphrag/neo4j-client.js';
import graphRouter from './graph.js';

const app = express();
app.use(express.json());
app.use('/agent', graphRouter);

const authHeader = { Authorization: 'Internal test-internal-token' };

describe('GET /agent/graph', () => {
  beforeEach(() => vi.clearAllMocks());

  it('전체 그래프를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockResolvedValue({ nodes: [{ key: 'a', type: 'Incident', name: 'x', sourceChunkCount: 1 }], edges: [] });
    const res = await request(app).get('/agent/graph').set(authHeader);
    expect(res.status).toBe(200);
    expect(res.body.nodes).toHaveLength(1);
  });

  it('읽기 실패 시 502를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockRejectedValue(new Error('neo4j down'));
    const res = await request(app).get('/agent/graph').set(authHeader);
    expect(res.status).toBe(502);
  });
});

describe('POST /agent/graph/merge-entities', () => {
  beforeEach(() => vi.clearAllMocks());

  it('유효한 body로 mergeEntities를 호출하고 204를 반환한다', async () => {
    mergeEntitiesMock.mockResolvedValue(undefined);
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityType: 'Cause', nameA: '전기적 요인', nameB: '분전반의 누전' });

    expect(res.status).toBe(204);
    expect(mergeEntitiesMock).toHaveBeenCalledWith(
      { domain: 'test', schemaVersion: 1, entities: [], relations: [] },
      'Cause', '전기적 요인', '분전반의 누전',
    );
  });

  it('내부 토큰 없이 호출하면 401', async () => {
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b' });
    expect(res.status).toBe(401);
    expect(mergeEntitiesMock).not.toHaveBeenCalled();
  });

  it('필수 필드 누락 시 400', async () => {
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityType: 'Cause' });
    expect(res.status).toBe(400);
    expect(mergeEntitiesMock).not.toHaveBeenCalled();
  });

  it('mergeEntities 실패 시 502', async () => {
    mergeEntitiesMock.mockRejectedValue(new Error('neo4j down'));
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b' });
    expect(res.status).toBe(502);
  });

  // 5-6: 엔티티 타입 리네임이 entity_type_id 보존 기반의 순수 DB 연산이 되어 Neo4j 마이그레이션
  // 라우트(5-5의 POST /agent/graph/rename-type)가 불필요해져 제거했다 — 관련 테스트도 함께 제거.
});
