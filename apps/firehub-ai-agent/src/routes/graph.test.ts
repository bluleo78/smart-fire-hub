// graph 라우터 테스트 — /graph(기존)와 /graph/merge-entities(신규) 엔드포인트를 검증한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';
import nock from 'nock';
import type { FireHubApiClient } from '../mcp/api-client.js';

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
vi.mock('../graphrag/relation-add.js', () => ({ addRelation: vi.fn().mockResolvedValue(undefined) }));
vi.mock('../graphrag/neo4j-client.js', () => ({ readWholeGraph: vi.fn() }));
vi.mock('../graphrag/ontology-source.js', () => ({
  // 쓰기 라우트용 해소 — 테넌트 경계만, 그래프 읽기 판정 없음(WD-28).
  resolveDatasetOntology: vi.fn().mockResolvedValue({ ontology: boundOntology, ontologyId: 42 }),
  // GET /graph 는 읽기 지점 전용 해소(=RLS 경계 + 읽기 판정)를 거친다 — 읽기 전용이라고 예외를 두지 않는다.
  resolveReadableOntologyById: vi.fn().mockResolvedValue({ ontology: boundOntology, ontologyId: 42, readable: 42 }),
}));

process.env.INTERNAL_SERVICE_TOKEN = 'test-internal-token';

import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { resolveDatasetOntology, resolveReadableOntologyById } from '../graphrag/ontology-source.js';
import graphRouter from './graph.js';

const app = express();
app.use(express.json());
app.use('/agent', graphRouter);

// 유효한 내부 호출의 헤더 집합. firehub-api(GraphMutationClient)는 Internal 토큰과 함께 승인
// 요청을 낸 사용자·테넌트를 대행 헤더로 싣는다 — 라우트가 api 를 역호출할 때 쓸 주체다.
const authHeader = {
  Authorization: 'Internal test-internal-token',
  'X-On-Behalf-Of': '42',
  'X-On-Behalf-Of-Tenant': '7',
};

describe('GET /agent/graph', () => {
  beforeEach(() => vi.clearAllMocks());

  it('ontologyId 소유권을 되확인한 뒤 스코프된 그래프를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockResolvedValue({ nodes: [{ key: 'a', type: 'Incident', name: 'x', sourceChunkCount: 1 }], edges: [] });
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(200);
    expect(res.body.nodes).toHaveLength(1);
    // 클라이언트가 준 5 를 그대로 쓰지 않고, RLS 왕복이 돌려준 값을 쓴다.
    expect(resolveReadableOntologyById).toHaveBeenCalledWith(expect.anything(), 5);
    expect(readWholeGraph).toHaveBeenCalledWith(42);
  });

  // 내부 토큰은 만능 자격증명이라, 대행 주체가 없으면 어느 테넌트를 대신해 소유권을 확인할지
  // 알 수 없다 — 읽기도 변형 라우트와 똑같이 거절해야 한다(예전에는 이 라우트만 통과시켰다).
  it('대행 헤더가 없으면 400 이고 Neo4j 를 조회하지 않는다', async () => {
    const res = await request(app)
      .get('/agent/graph?ontologyId=5')
      .set('Authorization', 'Internal test-internal-token');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('missing delegation headers');
    expect(resolveReadableOntologyById).not.toHaveBeenCalled();
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  // 남의 온톨로지는 RLS 때문에 "없는 것"과 같아져 해소 단계에서 예외가 난다 — 그 전에 Neo4j 를
  // 건드리면 이미 남의 데이터를 읽은 것이다.
  it('온톨로지 해소가 실패하면 Neo4j 를 조회하지 않는다', async () => {
    vi.mocked(resolveReadableOntologyById).mockRejectedValueOnce(new Error('존재하지 않는 온톨로지입니다: 5'));
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(502);
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  // ontologyId 가 선택 인자면 무스코프 전체 조회 경로가 그대로 살아남는다 — 누락은 400 으로 막고,
  // readWholeGraph 를 아예 호출하지 않는다(호출 후 걸러내면 이미 남의 데이터를 읽은 것이다).
  it('ontologyId 가 없으면 400 이고 Neo4j 를 조회하지 않는다', async () => {
    const res = await request(app).get('/agent/graph').set(authHeader);
    expect(res.status).toBe(400);
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  it('ontologyId 가 숫자가 아니면 400 이다', async () => {
    const res = await request(app).get('/agent/graph?ontologyId=abc').set(authHeader);
    expect(res.status).toBe(400);
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  // WD-28: api 의 시각화 프록시는 403 본문에 GRAPH_READ_RESTRICTED 문자열이 있는지로 경합 상황을 판별해
  // web 에 같은 403 을 넘긴다 — 그래서 code 필드가 본문에 반드시 있어야 한다.
  it('읽기 판정이 restricted 면 403 GRAPH_READ_RESTRICTED 이고 Neo4j 를 조회하지 않는다', async () => {
    vi.mocked(resolveReadableOntologyById).mockResolvedValueOnce(
      { ontology: boundOntology, ontologyId: 42, readable: 'restricted' } as never,
    );
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(403);
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body.code).toBe('GRAPH_READ_RESTRICTED');
    expect(res.text).toContain('GRAPH_READ_RESTRICTED');
    expect(res.body.message).toBe('이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 조회할 수 없습니다.');
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  // 판정 조회 자체가 실패하면(네트워크·5xx·타임아웃·404) 권한 없음(403)이 아니라 502 GRAPH_READ_CHECK_FAILED 다 —
  // api 프록시가 이를 일반 장애로 전달해야 web 이 자물쇠 안내 대신 재시도 가능한 오류를 보여 준다.
  it('판정 조회 실패(unavailable)면 502 GRAPH_READ_CHECK_FAILED 이고 Neo4j 를 조회하지 않는다', async () => {
    vi.mocked(resolveReadableOntologyById).mockResolvedValueOnce(
      { ontology: boundOntology, ontologyId: 42, readable: 'unavailable' } as never,
    );
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(502);
    expect(res.body.code).toBe('GRAPH_READ_CHECK_FAILED');
    expect(res.text).not.toContain('GRAPH_READ_RESTRICTED');
    expect(res.body.message).toBe('지식그래프 열람 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.');
    expect(readWholeGraph).not.toHaveBeenCalled();
  });

  it('읽기 실패 시 502를 반환한다', async () => {
    vi.mocked(readWholeGraph).mockRejectedValue(new Error('neo4j down'));
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(502);
  });
});

describe('POST /agent/graph/merge-entities', () => {
  beforeEach(() => vi.clearAllMocks());

  it('유효한 body로 mergeEntities를 호출하고 204를 반환한다', async () => {
    mergeEntitiesMock.mockResolvedValue(undefined);
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set(authHeader)
      .send({ entityType: 'Cause', nameA: '전기적 요인', nameB: '분전반의 누전', datasetId: 900 });

    expect(res.status).toBe(204);
    expect(mergeEntitiesMock).toHaveBeenCalledWith(
      boundOntology, 42,
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
      .set(authHeader)
      .send({ entityType: 'Cause' });
    expect(res.status).toBe(400);
    expect(mergeEntitiesMock).not.toHaveBeenCalled();
  });

  it('mergeEntities 실패 시 502', async () => {
    mergeEntitiesMock.mockRejectedValue(new Error('neo4j down'));
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set(authHeader)
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
    // ontologyId 도 함께 넘겨야 한다 — mergeEntities 의 모든 MATCH/DETACH DELETE 가 이 값으로 스코프된다.
    expect(mergeEntitiesMock).toHaveBeenCalledWith(boundOntology, 42, 'Cause', 'a', 'b');
  });

  // 대행 헤더가 없으면 라우트는 api 역호출의 주체를 알 수 없다. 예전처럼 userId=1 로 폴백하면
  // 그 사용자가 요청 테넌트의 멤버가 아닐 때 역호출이 403 나고, 원인은 로그에만 남는다.
  it('대행 헤더가 없으면 400을 반환한다(userId 하드코딩 폴백 없음)', async () => {
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 });

    expect(res.status).toBe(400);
    expect(res.body.error).toBe('missing delegation headers');
    expect(mergeEntitiesMock).not.toHaveBeenCalled();
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
      42,
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

  it('datasetId로 해소한 온톨로지로 스코프해 setEntityProperty를 호출하고 204를 반환한다', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set(authHeader)
      .send({ entityKey: '3:화재', propertyName: '피해액', dataType: 'number', value: '30000000', datasetId: 900 });
    expect(res.status).toBe(204);
    expect(resolveDatasetOntology).toHaveBeenCalledWith(expect.anything(), 900);
    expect(setEntityPropertyMock).toHaveBeenCalledWith(42, '3:화재', '피해액', 'number', '30000000');
  });

  it('POST /agent/graph/set-property는 잘못된 body에 400', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set(authHeader)
      .send({ entityKey: '', propertyName: '피해액', dataType: 'number', value: '1', datasetId: 900 });
    expect(res.status).toBe(400);
  });

  // 이 라우트만 스코프 식별자가 없던 구멍의 회귀 가드 — datasetId 가 없으면 온톨로지를 해소할 수
  // 없으므로 write 자체가 일어나면 안 된다(예전에는 entityKey 만으로 그대로 write 했다).
  it('datasetId가 없으면 400이고 write하지 않는다', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set(authHeader)
      .send({ entityKey: '3:화재', propertyName: '피해액', dataType: 'number', value: '30000000' });
    expect(res.status).toBe(400);
    expect(resolveDatasetOntology).not.toHaveBeenCalled();
    expect(setEntityPropertyMock).not.toHaveBeenCalled();
  });

  // 대행 헤더가 없으면 api 역호출의 주체를 알 수 없어 온톨로지 소유권(RLS 경계)을 확인할 수 없다.
  it('대행 헤더가 없으면 400이고 write하지 않는다', async () => {
    const res = await request(app)
      .post('/agent/graph/set-property')
      .set('Authorization', 'Internal test-internal-token')
      .send({ entityKey: '3:화재', propertyName: '피해액', dataType: 'number', value: '30000000', datasetId: 900 });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('missing delegation headers');
    expect(setEntityPropertyMock).not.toHaveBeenCalled();
  });
});

// WD-28: 판정은 읽기 지점에서만 한다 — 쓰기 라우트(검수 반영)는 읽기 판정 해소를 타지 않아야 한다.
// 탔다면 판정 장애·읽기 제한이 승인 반영을 막고, 쓰지도 않을 왕복이 생긴다(스펙 §6).
describe('쓰기 라우트는 그래프 읽기 판정을 하지 않는다', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each([
    ['/agent/graph/merge-entities', { entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 }],
    ['/agent/graph/set-property', { entityKey: '3:화재', propertyName: '피해액', dataType: 'number', value: '1', datasetId: 900 }],
    ['/agent/graph/add-entity', { entityType: 'Inspection', name: '점검', datasetId: 900 }],
    ['/agent/graph/add-relation', { subjectKey: 'a', relType: 'R', objectKey: 'b', datasetId: 900 }],
  ])('%s 는 resolveDatasetOntology 만 쓰고 resolveReadableOntologyById 를 부르지 않는다', async (path, body) => {
    await request(app).post(path).set(authHeader).send(body);
    expect(resolveDatasetOntology).toHaveBeenCalledWith(expect.anything(), 900);
    expect(resolveReadableOntologyById).not.toHaveBeenCalled();
  });
});

// S3: 그래프 뷰어·HITL 승인은 사람이 직접 보는 경로라 api 를 purpose 'none' 으로 역호출해야 한다.
// 빠뜨리면 api 가 AI 경로로 판정해, 외부 호스팅 테넌트에서 민감 등급 데이터셋이 뷰어·승인에서 막힌다.
describe('그래프 라우트의 대행 목적(S3)', () => {
  beforeEach(() => vi.clearAllMocks());

  /**
   * 해소 함수가 받은 클라이언트로 api 를 한 번 실제로 부르게 하고, 그 요청이 X-AI-Purpose: none 을 싣는지 nock 으로 확인할 범위.
   * 내부 필드가 아니라 실제로 나가는 헤더를 본다. 기준 주소는 라우트(delegationClient)와 같은 규칙으로 정한다.
   */
  function expectNonePurposeCall(): nock.Scope {
    const base = new URL(process.env.API_BASE_URL || 'http://localhost:8080/api/v1');
    return nock(base.origin, { reqheaders: { 'x-ai-purpose': 'none' } })
      .get(`${base.pathname.replace(/\/$/, '')}/dataset-categories`)
      .reply(200, []);
  }

  it("GET /graph 는 purpose 'none' 클라이언트로 읽기 판정을 한다", async () => {
    vi.mocked(readWholeGraph).mockResolvedValue({ nodes: [], edges: [] });
    const scope = expectNonePurposeCall();
    vi.mocked(resolveReadableOntologyById).mockImplementationOnce(async (client) => {
      // 라우트가 넘긴 것은 실제 FireHubApiClient 다(해소 함수 시그니처는 좁은 인터페이스).
      await (client as unknown as FireHubApiClient).listCategories();
      return { ontology: boundOntology, ontologyId: 42, readable: 42 } as never;
    });
    const res = await request(app).get('/agent/graph?ontologyId=5').set(authHeader);
    expect(res.status).toBe(200);
    expect(scope.isDone()).toBe(true);
  });

  it("HITL 승인(merge-entities)도 purpose 'none' 클라이언트로 온톨로지를 해소한다", async () => {
    mergeEntitiesMock.mockResolvedValue(undefined);
    const scope = expectNonePurposeCall();
    vi.mocked(resolveDatasetOntology).mockImplementationOnce(async (client) => {
      // 라우트가 넘긴 것은 실제 FireHubApiClient 다(해소 함수 시그니처는 좁은 인터페이스).
      await (client as unknown as FireHubApiClient).listCategories();
      return { ontology: boundOntology, ontologyId: 42 } as never;
    });
    const res = await request(app)
      .post('/agent/graph/merge-entities')
      .set(authHeader)
      .send({ entityType: 'Cause', nameA: 'a', nameB: 'b', datasetId: 900 });
    expect(res.status).toBe(204);
    expect(scope.isDone()).toBe(true);
  });
});
