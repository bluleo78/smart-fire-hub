import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('datasetApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listDatasets calls GET /datasets with params', async () => {
    const mock = { content: [], totalElements: 0 };
    nock(BASE_URL).get('/datasets').query({ page: '0', size: '10' }).reply(200, mock);
    const result = await client.listDatasets({ page: 0, size: 10 });
    expect(result).toEqual(mock);
  });

  it('getDataset calls GET /datasets/:id', async () => {
    const mock = { id: 1, name: '테스트 데이터셋' };
    nock(BASE_URL).get('/datasets/1').reply(200, mock);
    const result = await client.getDataset(1);
    expect(result).toEqual(mock);
  });

  it('updateDataset calls PUT /datasets/:id', async () => {
    const body = { name: '수정된 데이터셋' };
    const mock = { id: 1, ...body };
    nock(BASE_URL).put('/datasets/1', body).reply(200, mock);
    const result = await client.updateDataset(1, body);
    expect(result).toEqual(mock);
  });

  it('queryDatasetData calls GET /datasets/:id/data with params', async () => {
    const mock = { content: [], totalElements: 0 };
    nock(BASE_URL).get('/datasets/1/data').query({ page: '0', size: '20' }).reply(200, mock);
    const result = await client.queryDatasetData(1, { page: 0, size: 20 });
    expect(result).toEqual(mock);
  });

  it('createDataset calls POST /datasets', async () => {
    const body = {
      name: '화재 데이터셋',
      tableName: 'fire_data',
      columns: [{ columnName: 'id', displayName: 'ID', dataType: 'INTEGER' }],
    };
    const mock = { id: 10, ...body };
    nock(BASE_URL).post('/datasets', body).reply(201, mock);
    const result = await client.createDataset(body);
    expect(result).toEqual(mock);
  });

  it('deleteDataset calls DELETE /datasets/:id', async () => {
    nock(BASE_URL).delete('/datasets/5').reply(204);
    const result = await client.deleteDataset(5);
    expect(result).toEqual({ success: true });
  });

  it('listDatasets calls GET /datasets without params', async () => {
    const mock = { content: [], totalElements: 0 };
    nock(BASE_URL).get('/datasets').reply(200, mock);
    const result = await client.listDatasets();
    expect(result).toEqual(mock);
  });

  // 지식그래프 구축 파이프라인 — 바인딩/활성화 경로가 없으면 infer_mapping·project_table 이
  // 서로를 막아 에이전트 단독으로 완결할 수 없다. 엔드포인트 계약을 고정한다.
  describe('지식그래프 구축 엔드포인트', () => {
    it('bindDatasetOntology calls PUT /datasets/:id/ontology with ontologyId body', async () => {
      const scope = nock(BASE_URL).put('/datasets/7/ontology', { ontologyId: 3 }).reply(204);
      await client.bindDatasetOntology(7, 3);
      expect(scope.isDone()).toBe(true);
    });

    it('activateDatasetMapping calls POST /datasets/:id/mapping/activate', async () => {
      nock(BASE_URL)
        .post('/datasets/7/mapping/activate')
        .reply(200, { datasetId: 7, ontologyId: 3, spec: { entities: [], relations: [] }, status: 'active' });
      const result = await client.activateDatasetMapping(7);
      expect(result).toEqual({ datasetId: 7, ontologyId: 3, status: 'active' });
    });

    it('activateDatasetMapping 은 conformance 위반(400)을 전파한다', async () => {
      nock(BASE_URL).post('/datasets/7/mapping/activate').reply(400, { message: 'conformance 위반' });
      await expect(client.activateDatasetMapping(7)).rejects.toThrow();
    });

    it('listOntologies calls GET /ontologies', async () => {
      const mock = [{ id: 1, domain: 'fire', schemaVersion: 3 }];
      nock(BASE_URL).get('/ontologies').reply(200, mock);
      const result = await client.listOntologies();
      expect(result).toEqual(mock);
    });

    it('listGraphIngests calls GET /datasets/:id/graph-ingests', async () => {
      const mock = [{ id: 1, datasetId: 7, ingestedAt: '2026-01-01T00:00:00', schemaVersionAtIngest: 2, chunkCount: 1, nodeCount: 2, edgeCount: 3, extractionFailures: 0, status: 'SUCCESS' }];
      nock(BASE_URL).get('/datasets/7/graph-ingests').reply(200, mock);
      expect(await client.listGraphIngests(7)).toEqual(mock);
    });

    it('listStaleGraphIngests calls GET /graph-ingests/stale', async () => {
      const mock = [{ datasetId: 7, latestIngestedAt: '2026-01-01T00:00:00', schemaVersionAtIngest: 2, currentSchemaVersion: 3 }];
      nock(BASE_URL).get('/graph-ingests/stale').reply(200, mock);
      expect(await client.listStaleGraphIngests()).toEqual(mock);
    });
  });
});
