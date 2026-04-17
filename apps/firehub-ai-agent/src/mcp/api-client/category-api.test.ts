import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('categoryApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('createCategory calls POST /dataset-categories', async () => {
    const body = { name: '화재 데이터', description: '화재 관련' };
    const mock = { id: 1, ...body };
    nock(BASE_URL).post('/dataset-categories', body).reply(201, mock);
    const result = await client.createCategory(body);
    expect(result).toEqual(mock);
  });

  it('updateCategory calls PUT /dataset-categories/:id', async () => {
    const body = { name: '수정된 카테고리', description: '수정됨' };
    const mock = { id: 3, ...body };
    nock(BASE_URL).put('/dataset-categories/3', body).reply(200, mock);
    const result = await client.updateCategory(3, body);
    expect(result).toEqual(mock);
  });
});
