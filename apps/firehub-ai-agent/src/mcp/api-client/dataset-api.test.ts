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
});
