import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from './api-client.js';

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 42;

describe('FireHubApiClient', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  // --- Header verification ---
  it('should send Authorization and X-On-Behalf-Of headers', async () => {
    const scope = nock(BASE_URL, {
      reqheaders: {
        authorization: `Internal ${TOKEN}`,
        'x-on-behalf-of': String(USER_ID),
      },
    })
      .get('/dataset-categories')
      .reply(200, []);

    await client.listCategories();
    expect(scope.isDone()).toBe(true);
  });

  // --- Categories ---
  it('should list categories via GET /dataset-categories', async () => {
    const mockData = [{ id: 1, name: 'Cat1' }];
    nock(BASE_URL).get('/dataset-categories').reply(200, mockData);

    const result = await client.listCategories();
    expect(result).toEqual(mockData);
  });

  // --- Datasets ---
  it('should create dataset via POST /datasets', async () => {
    const body = {
      name: 'Test Dataset',
      tableName: 'test_table',
      columns: [{ columnName: 'col1', displayName: 'Col 1', dataType: 'TEXT' }],
    };
    const mockResp = { id: 1, ...body };

    nock(BASE_URL)
      .post('/datasets', (reqBody: Record<string, unknown>) => {
        return reqBody.name === body.name && reqBody.tableName === body.tableName;
      })
      .reply(201, mockResp);

    const result = await client.createDataset(body);
    expect(result).toEqual(mockResp);
  });

  // --- Pipelines ---
  it('should create pipeline via POST /pipelines', async () => {
    const body = {
      name: 'Test Pipeline',
      steps: [{ name: 'step1', scriptType: 'SQL', scriptContent: 'SELECT 1' }],
    };
    const mockResp = { id: 1, ...body };

    nock(BASE_URL)
      .post('/pipelines', (reqBody: Record<string, unknown>) => reqBody.name === body.name)
      .reply(201, mockResp);

    const result = await client.createPipeline(body);
    expect(result).toEqual(mockResp);
  });

  it('should update pipeline via PUT /pipelines/:id', async () => {
    nock(BASE_URL)
      .put('/pipelines/5', (reqBody: Record<string, unknown>) => reqBody.name === 'Updated')
      .reply(200);

    const result = await client.updatePipeline(5, { name: 'Updated' });
    expect(result).toEqual({ success: true });
  });

  it('should delete pipeline via DELETE /pipelines/:id', async () => {
    nock(BASE_URL).delete('/pipelines/3').reply(204);

    const result = await client.deletePipeline(3);
    expect(result).toEqual({ success: true });
  });

  // --- Triggers ---
  it('should create trigger via POST /pipelines/:id/triggers', async () => {
    const body = {
      name: 'Daily Run',
      triggerType: 'SCHEDULE',
      config: { cronExpression: '0 0 9 * * ?' },
    };
    const mockResp = { id: 10, ...body };

    nock(BASE_URL)
      .post('/pipelines/1/triggers', (reqBody: Record<string, unknown>) => reqBody.name === body.name)
      .reply(201, mockResp);

    const result = await client.createTrigger(1, body);
    expect(result).toEqual(mockResp);
  });

  // --- API Connections ---
  it('should create API connection via POST /api-connections', async () => {
    const body = {
      name: 'Test API',
      authType: 'BEARER',
      authConfig: { token: 'abc123' },
    };
    const mockResp = { id: 1, ...body };

    nock(BASE_URL)
      .post('/api-connections', (reqBody: Record<string, unknown>) => reqBody.name === body.name)
      .reply(201, mockResp);

    const result = await client.createApiConnection(body);
    expect(result).toEqual(mockResp);
  });

  it('should delete API connection via DELETE /api-connections/:id', async () => {
    nock(BASE_URL).delete('/api-connections/7').reply(204);

    const result = await client.deleteApiConnection(7);
    expect(result).toEqual({ success: true });
  });

  // --- Preview API Call ---
  it('should preview API call via POST /pipelines/api-call/preview', async () => {
    const body = {
      url: 'https://api.example.com/data',
      method: 'GET',
      dataPath: '$.items',
    };
    const mockResp = { preview: [{ name: 'item1' }] };

    nock(BASE_URL)
      .post('/pipelines/api-call/preview', (reqBody: Record<string, unknown>) => reqBody.url === body.url)
      .reply(200, mockResp);

    const result = await client.previewApiCall(body);
    expect(result).toEqual(mockResp);
  });

  // --- Error handling ---
  it('should throw Error on 4xx/5xx responses', async () => {
    nock(BASE_URL).get('/dataset-categories').reply(404, { message: 'Not found' });

    await expect(client.listCategories()).rejects.toThrow('API 오류 (404)');
  });
});
