import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('miscApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listImports calls GET /datasets/:id/imports', async () => {
    const mock = [{ id: 1, status: 'SUCCESS' }];
    nock(BASE_URL).get('/datasets/3/imports').reply(200, mock);
    const result = await client.listImports(3);
    expect(result).toEqual(mock);
  });

  it('getDashboard calls GET /dashboard/stats', async () => {
    const mock = { totalDatasets: 10, totalPipelines: 5 };
    nock(BASE_URL).get('/dashboard/stats').reply(200, mock);
    const result = await client.getDashboard();
    expect(result).toEqual(mock);
  });
});
