import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('connectionApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listApiConnections calls GET /api-connections', async () => {
    const mock = [{ id: 1, name: 'Make.com' }];
    nock(BASE_URL).get('/api-connections').reply(200, mock);
    const result = await client.listApiConnections();
    expect(result).toEqual(mock);
  });

  it('getApiConnection calls GET /api-connections/:id', async () => {
    const mock = { id: 1, name: 'Make.com', authType: 'API_KEY' };
    nock(BASE_URL).get('/api-connections/1').reply(200, mock);
    const result = await client.getApiConnection(1);
    expect(result).toEqual(mock);
  });

  it('createApiConnection calls POST /api-connections', async () => {
    const body = {
      name: 'Make.com',
      authType: 'API_KEY',
      authConfig: { placement: 'header', headerName: 'X-Api-Key', apiKey: 'secret' },
      baseUrl: 'https://api.make.com/v2',
    };
    const mock = { id: 1, ...body };
    nock(BASE_URL).post('/api-connections', body).reply(201, mock);
    const result = await client.createApiConnection(body);
    expect(result).toEqual(mock);
  });

  it('updateApiConnection calls PUT /api-connections/:id', async () => {
    const body = { name: '수정된 연결' };
    const mock = { id: 2, name: '수정된 연결' };
    nock(BASE_URL).put('/api-connections/2', body).reply(200, mock);
    const result = await client.updateApiConnection(2, body);
    expect(result).toEqual(mock);
  });

  it('deleteApiConnection calls DELETE /api-connections/:id', async () => {
    nock(BASE_URL).delete('/api-connections/3').reply(204);
    const result = await client.deleteApiConnection(3);
    expect(result).toEqual({ success: true });
  });

  it('updateApiConnection calls PUT /api-connections/:id with full data', async () => {
    const body = { name: '업데이트 연결', authType: 'BEARER', authConfig: { token: 'new-token' }, baseUrl: 'https://updated.example.com' };
    const mock = { id: 5, ...body };
    nock(BASE_URL).put('/api-connections/5', body).reply(200, mock);
    const result = await client.updateApiConnection(5, body);
    expect(result).toEqual(mock);
  });

  it('testApiConnection calls POST /api-connections/:id/test', async () => {
    const mock = { ok: true, status: 200, latencyMs: 45, errorMessage: null };
    nock(BASE_URL).post('/api-connections/4/test').reply(200, mock);
    const result = await client.testApiConnection(4);
    expect(result).toEqual(mock);
  });
});
