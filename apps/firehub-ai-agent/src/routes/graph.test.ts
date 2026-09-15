// graph 라우터 테스트 — /graph(기존)와 /graph/merge-entities(신규) 엔드포인트를 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';

// merge-entities/add-entity/add-relation 핸들러가 entityType→typeId 변환용 온톨로지를 fetch한다 —
// 실제 HTTP 호출 없이 고정 온톨로지로 대체. datasetId 는 이제 필수 필드라(#678) "기본 온톨로지" 폴백은 없다.
// vi.mock 팩토리는 호이스팅되므로, 팩토리 안에서 참조하는 값도 vi.hoisted로 함께 끌어올려야 한다.
const { mergeEntitiesMock, setEntityPropertyMock, addEntityMock, boundOntology } = vi.hoisted(() => ({
  mergeEntitiesMock: vi.fn(), setEntityPropertyMock: vi.fn(), addEntityMock: vi.fn(),
  boundOntology: { domain: 'bound', schemaVersion: 2, entities: [], relations: [] },
}));
vi.mock('../graphrag/synonym-merge.js', () => ({ mergeEntities: mergeEntitiesMock }));
vi.mock('../graphrag/property-mutation.js', () => ({ setEntityProperty: setEntityPropertyMock }));
vi.mock('../graphrag/entity-add.js', () => ({ addEntity: addEntityMock }));
vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn() }));
vi.mock('../graphrag/ontology-source.js', () => ({
  resolveDatasetOntology: vi.fn().mockResolvedValue({ ontology: boundOntology, ontologyId: 42 }),
}));

process.env.INTERNAL_SERVICE_TOKEN = 'test-internal-token';

import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { resolveDatasetOntology } from '../graphrag/ontology-source.js';
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
      .send({ entityType: 'Cause', nameA: '전기적 요인', nameB: '분전반의 누전', datasetId: 900 });

    expect(res.status).toBe(204);
    expect(mergeEntitiesMock).toHaveBeenCalledWith(
      boundOntology,
      'Cause', '전기적 요인', '분전반의 누전',
    );
  });

  it('내부 토큰 없이 호출하면 401', async () => {
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 });
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
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 });
    expect(res.status).toBe(502);
  });

  // 5-6: 엔티티 타입 리네임이 entity_type_id 보존 기반의 순수 DB 연산이 되어 Neo4j 마이그레이션
  // 라우트(5-5의 POST /agent/graph/rename-type)가 불필요해져 제거했다 — 관련 테스트도 함께 제거.

  // #1(review): 기본이 아닌 온톨로지에 바인딩된 데이터셋의 검수 승인은 datasetId 없이는 typeId 가
  // 어긋난다 — datasetId 를 받으면 그 데이터셋의 바인딩 온톨로지로 변환해야 한다.
  it('datasetId를 resolveDatasetOntology로 바인딩된 온톨로지 변환에 쓴다', async () => {
    mergeEntitiesMock.mockResolvedValue(undefined);
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set(authHeader)
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 });

    expect(res.status).toBe(204);
    expect(resolveDatasetOntology).toHaveBeenCalledWith(expect.anything(), 900);
    expect(mergeEntitiesMock).toHaveBeenCalledWith(boundOntology, 'Cause', 'a', 'b');
  });

  // #678: datasetId 미바인딩 시 "기본 온톨로지"로 조용히 폴백하던 동작 제거 — 이제 datasetId 는
  // 필수 zod 필드라 빠지면 다른 필수 필드 누락과 마찬가지로 400을 반환한다(폴백 경로 자체가 없다).
  it('datasetId가 없으면 400을 반환한다(폴백 없음)', async () => {
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set(authHeader)
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b' });

    expect(res.status).toBe(400);
    expect(resolveDatasetOntology).not.toHaveBeenCalled();
    expect(mergeEntitiesMock).not.toHaveBeenCalled();
  });
});

describe('POST /agent/graph/add-entity', () => {
  beforeEach(() => vi.clearAllMocks());

  // merge-entities와 동일한 이유(#1) — datasetId 로 바인딩된 온톨로지를 해소한다.
  it('datasetId를 resolveDatasetOntology로 바인딩된 온톨로지 변환에 쓴다', async () => {
    addEntityMock.mockResolvedValue(undefined);
    const res = await request(app)
      .post('/agent/graph/add-entity')
      .set(authHeader)
      .send({ entityType: 'Inspection', name: '2026년 정기점검', datasetId: 900 });

    expect(res.status).toBe(204);
    expect(resolveDatasetOntology).toHaveBeenCalledWith(expect.anything(), 900);
    expect(addEntityMock).toHaveBeenCalledWith(
      boundOntology,
      expect.objectContaining({ entityType: 'Inspection', name: '2026년 정기점검' }),
    );
  });

  // #678: datasetId 필수화 — 없으면 폴백하지 않고 400.
  it('datasetId가 없으면 400을 반환한다(폴백 없음)', async () => {
    const res = await request(app)
      .post('/agent/graph/add-entity')
      .set(authHeader)
      .send({ entityType: 'Inspection', name: '2026년 정기점검' });

    expect(res.status).toBe(400);
    expect(resolveDatasetOntology).not.toHaveBeenCalled();
    expect(addEntityMock).not.toHaveBeenCalled();
  });
});

describe('POST /agent/graph/set-property', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POST /agent/graph/set-property는 setEntityProperty를 호출하고 204를 반환한다', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityKey: '3:화재', propertyName: '피해액', dataType: 'number', value: '30000000' });
    expect(res.status).toBe(204);
    expect(setEntityPropertyMock).toHaveBeenCalledWith('3:화재', '피해액', 'number', '30000000');
  });

  it('POST /agent/graph/set-property는 잘못된 body에 400', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityKey: '', propertyName: '피해액', dataType: 'number', value: '1' });
    expect(res.status).toBe(400);
  });
});
