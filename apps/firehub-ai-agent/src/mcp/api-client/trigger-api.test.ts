import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('triggerApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listTriggers calls GET /pipelines/:id/triggers', async () => {
    const mock = [{ id: 1, name: '매일 실행', triggerType: 'SCHEDULE' }];
    nock(BASE_URL).get('/pipelines/5/triggers').reply(200, mock);
    const result = await client.listTriggers(5);
    expect(result).toEqual(mock);
  });

  it('createTrigger calls POST /pipelines/:id/triggers', async () => {
    const body = {
      name: '매일 실행',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 9 * * *' },
    };
    const mock = { id: 1, pipelineId: 5, ...body };
    nock(BASE_URL).post('/pipelines/5/triggers', body).reply(201, mock);
    const result = await client.createTrigger(5, body);
    expect(result).toEqual(mock);
  });

  it('updateTrigger calls PUT /pipelines/:id/triggers/:triggerId', async () => {
    const body = { isEnabled: false };
    nock(BASE_URL).put('/pipelines/5/triggers/10', body).reply(200);
    const result = await client.updateTrigger(5, 10, body);
    expect(result).toEqual({ success: true });
  });

  it('deleteTrigger calls DELETE /pipelines/:id/triggers/:triggerId', async () => {
    nock(BASE_URL).delete('/pipelines/5/triggers/10').reply(204);
    const result = await client.deleteTrigger(5, 10);
    expect(result).toEqual({ success: true });
  });
});
