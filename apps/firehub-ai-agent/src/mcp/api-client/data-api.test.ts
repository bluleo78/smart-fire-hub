import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('dataApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('addRow calls POST /datasets/:id/data/rows', async () => {
    const data = { name: '홍길동', age: 30 };
    const mock = { id: 1, ...data };
    nock(BASE_URL).post('/datasets/5/data/rows', { data }).reply(201, mock);
    const result = await client.addRow(5, data);
    expect(result).toEqual(mock);
  });

  it('addRowsBatch calls POST /datasets/:id/data/rows/batch', async () => {
    const rows = [{ name: 'A' }, { name: 'B' }];
    const mock = { inserted: 2 };
    nock(BASE_URL).post('/datasets/5/data/rows/batch', { rows }).reply(201, mock);
    const result = await client.addRowsBatch(5, rows);
    expect(result).toEqual(mock);
  });

  it('updateRow calls PUT /datasets/:id/data/rows/:rowId', async () => {
    const data = { name: '수정값' };
    nock(BASE_URL).put('/datasets/5/data/rows/10', { data }).reply(200);
    const result = await client.updateRow(5, 10, data);
    expect(result).toEqual({ success: true });
  });

  it('deleteRows calls POST /datasets/:id/data/delete', async () => {
    const rowIds = [1, 2, 3];
    const mock = { deleted: 3 };
    nock(BASE_URL).post('/datasets/5/data/delete', { rowIds }).reply(200, mock);
    const result = await client.deleteRows(5, rowIds);
    expect(result).toEqual(mock);
  });

  it('truncateDataset calls POST /datasets/:id/data/truncate', async () => {
    const mock = { deleted: 100 };
    nock(BASE_URL).post('/datasets/5/data/truncate').reply(200, mock);
    const result = await client.truncateDataset(5);
    expect(result).toEqual(mock);
  });

  it('getRowCount calls GET /datasets/:id/data/count', async () => {
    const mock = { count: 42 };
    nock(BASE_URL).get('/datasets/5/data/count').reply(200, mock);
    const result = await client.getRowCount(5);
    expect(result).toEqual(mock);
  });

  it('replaceDatasetData calls POST /datasets/:id/data/replace', async () => {
    const rows = [{ col: 'val' }];
    const mock = { replaced: 1 };
    nock(BASE_URL).post('/datasets/5/data/replace', { rows }).reply(200, mock);
    const result = await client.replaceDatasetData(5, rows);
    expect(result).toEqual(mock);
  });
});
