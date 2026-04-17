import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('pipelineApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listPipelines calls GET /pipelines', async () => {
    const mock = { content: [], totalElements: 0 };
    nock(BASE_URL).get('/pipelines').query(true).reply(200, mock);
    const result = await client.listPipelines({ page: 0, size: 10 });
    expect(result).toEqual(mock);
  });

  it('getPipeline calls GET /pipelines/:id', async () => {
    const mock = { id: 1, name: '테스트' };
    nock(BASE_URL).get('/pipelines/1').reply(200, mock);
    const result = await client.getPipeline(1);
    expect(result).toEqual(mock);
  });

  it('createPipeline calls POST /pipelines', async () => {
    const body = {
      name: '테스트 파이프라인',
      steps: [{ name: 'step1', scriptType: 'SQL', scriptContent: 'SELECT 1' }],
    };
    const mock = { id: 1, ...body };
    nock(BASE_URL).post('/pipelines', body).reply(201, mock);
    const result = await client.createPipeline(body);
    expect(result).toEqual(mock);
  });

  it('updatePipeline calls PUT /pipelines/:id', async () => {
    const body = { name: '수정된 파이프라인', isActive: false };
    nock(BASE_URL).put('/pipelines/2', body).reply(200);
    const result = await client.updatePipeline(2, body);
    expect(result).toEqual({ success: true });
  });

  it('deletePipeline calls DELETE /pipelines/:id', async () => {
    nock(BASE_URL).delete('/pipelines/3').reply(204);
    const result = await client.deletePipeline(3);
    expect(result).toEqual({ success: true });
  });

  it('previewApiCall calls POST /pipelines/api-call/preview', async () => {
    const body = { method: 'GET', dataPath: '$.items', customUrl: 'https://api.example.com/data' };
    const mock = { rows: [], totalCount: 0 };
    nock(BASE_URL).post('/pipelines/api-call/preview', body).reply(200, mock);
    const result = await client.previewApiCall(body);
    expect(result).toEqual(mock);
  });

  it('executePipeline calls POST /pipelines/:id/execute', async () => {
    const mock = { executionId: 42, status: 'RUNNING' };
    nock(BASE_URL).post('/pipelines/7/execute').reply(200, mock);
    const result = await client.executePipeline(7);
    expect(result).toEqual(mock);
  });

  it('getExecutionStatus calls GET /pipelines/:id/executions/:executionId', async () => {
    const mock = { id: 42, status: 'SUCCESS' };
    nock(BASE_URL).get('/pipelines/7/executions/42').reply(200, mock);
    const result = await client.getExecutionStatus(7, 42);
    expect(result).toEqual(mock);
  });
});
