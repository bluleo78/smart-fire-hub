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

  // --- Dataset deletion ---
  describe('deleteDataset', () => {
    it('calls DELETE /datasets/:id', async () => {
      nock(BASE_URL).delete('/datasets/42').reply(204);

      const result = await client.deleteDataset(42);
      expect(result).toEqual({ success: true });
    });
  });

  // --- Dataset column add/drop ---
  describe('addDatasetColumn / dropDatasetColumn', () => {
    it('POSTs /datasets/:id/columns', async () => {
      nock(BASE_URL)
        .post('/datasets/42/columns')
        .reply(201, {
          id: 99,
          columnName: 'lat',
          displayName: '위도',
          dataType: 'DECIMAL',
          isNullable: true,
          isIndexed: false,
          columnOrder: 5,
          isPrimaryKey: false,
        });
      const result = await client.addDatasetColumn(42, {
        columnName: 'lat',
        displayName: '위도',
        dataType: 'DECIMAL',
        isNullable: true,
      });
      expect(result.id).toBe(99);
      expect(result.columnName).toBe('lat');
    });

    it('DELETEs /datasets/:id/columns/:columnId', async () => {
      nock(BASE_URL).delete('/datasets/42/columns/99').reply(204);
      await expect(client.dropDatasetColumn(42, 99)).resolves.toEqual({ success: true });
    });
  });

  // --- Dataset references ---
  describe('getDatasetReferences', () => {
    it('getDatasetReferences calls GET /datasets/{id}/references', async () => {
      nock(BASE_URL)
        .get('/datasets/42/references')
        .reply(200, {
          datasetId: 42,
          pipelines: [{ id: 1, name: 'daily_import' }],
          dashboards: [],
          proactiveJobs: [],
          totalCount: 1,
        });

      const result = await client.getDatasetReferences(42);
      expect(result.totalCount).toBe(1);
      expect(result.pipelines[0].name).toBe('daily_import');
    });
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
      .post(
        '/pipelines/1/triggers',
        (reqBody: Record<string, unknown>) => reqBody.name === body.name,
      )
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
      baseUrl: 'https://api.test.com',
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

  it('should create API connection with baseUrl and healthCheckPath', async () => {
    const body = {
      name: 'Make.com API',
      authType: 'BEARER',
      authConfig: { token: 'tok123' },
      baseUrl: 'https://api.make.com/v2',
      healthCheckPath: '/health',
    };
    const mockResp = { id: 2, ...body };

    nock(BASE_URL)
      .post('/api-connections', (reqBody: Record<string, unknown>) =>
        reqBody.baseUrl === body.baseUrl && reqBody.healthCheckPath === body.healthCheckPath,
      )
      .reply(201, mockResp);

    const result = await client.createApiConnection(body);
    expect(result).toEqual(mockResp);
  });

  // --- testApiConnection ---
  it('should test API connection via POST /api-connections/:id/test', async () => {
    const mockResp = { ok: true, status: 200, latencyMs: 142, errorMessage: null };

    nock(BASE_URL).post('/api-connections/3/test').reply(200, mockResp);

    const result = await client.testApiConnection(3);
    expect(result).toEqual(mockResp);
  });

  it('should handle DOWN status in testApiConnection', async () => {
    const mockResp = { ok: false, status: 401, latencyMs: 50, errorMessage: 'Unauthorized' };

    nock(BASE_URL).post('/api-connections/5/test').reply(200, mockResp);

    const result = await client.testApiConnection(5);
    expect(result.ok).toBe(false);
    expect(result.errorMessage).toBe('Unauthorized');
  });

  // --- listSelectableConnections ---
  it('should list selectable connections via GET /api-connections/selectable', async () => {
    const mockResp = [
      { id: 1, name: 'Make.com API', authType: 'BEARER', baseUrl: 'https://api.make.com/v2' },
      { id: 2, name: '공공데이터포털', authType: 'API_KEY', baseUrl: 'https://api.odcloud.kr/api' },
    ];

    nock(BASE_URL).get('/api-connections/selectable').reply(200, mockResp);

    const result = await client.listSelectableConnections();
    expect(result).toEqual(mockResp);
    expect(result[0].baseUrl).toBe('https://api.make.com/v2');
  });

  // --- Preview API Call ---
  it('should preview API call via POST /pipelines/api-call/preview', async () => {
    const body = {
      customUrl: 'https://api.example.com/data',
      method: 'GET',
      dataPath: '$.items',
    };
    const mockResp = { preview: [{ name: 'item1' }] };

    nock(BASE_URL)
      .post(
        '/pipelines/api-call/preview',
        (reqBody: Record<string, unknown>) => reqBody.customUrl === body.customUrl,
      )
      .reply(200, mockResp);

    const result = await client.previewApiCall(body);
    expect(result).toEqual(mockResp);
  });

  // --- Error handling ---
  it('should throw Error on 4xx/5xx responses', async () => {
    nock(BASE_URL).get('/dataset-categories').reply(404, { message: 'Not found' });

    await expect(client.listCategories()).rejects.toThrow('API 오류 (404)');
  });

  // --- executeQuery ---
  it('should execute SQL query via POST /datasets/:id/query', async () => {
    const mockResp = { columns: ['id'], rows: [[1]], rowCount: 1 };
    nock(BASE_URL)
      .post(
        '/datasets/1/query',
        (body: Record<string, unknown>) =>
          body.sql === 'SELECT * FROM data."test"' && body.maxRows === 100,
      )
      .reply(200, mockResp);

    const result = await client.executeQuery(1, 'SELECT * FROM data."test"', 100);
    expect(result).toEqual(mockResp);
  });

  // --- addRowsBatch ---
  it('should add rows batch via POST /datasets/:id/data/rows/batch', async () => {
    const rows = [{ name: 'a' }, { name: 'b' }];
    const mockResp = { insertedCount: 2 };
    nock(BASE_URL)
      .post(
        '/datasets/1/data/rows/batch',
        (body: Record<string, unknown>) => Array.isArray(body.rows) && body.rows.length === 2,
      )
      .reply(201, mockResp);

    const result = await client.addRowsBatch(1, rows);
    expect(result).toEqual(mockResp);
  });

  // --- replaceDatasetData ---
  it('should replace dataset data via POST /datasets/:id/data/replace', async () => {
    const rows = [{ col1: 'val1' }];
    const mockResp = { insertedCount: 1 };
    nock(BASE_URL)
      .post(
        '/datasets/1/data/replace',
        (body: Record<string, unknown>) => Array.isArray(body.rows) && body.rows.length === 1,
      )
      .reply(201, mockResp);

    const result = await client.replaceDatasetData(1, rows);
    expect(result).toEqual(mockResp);
  });

  // --- truncateDataset ---
  it('should truncate dataset via POST /datasets/:id/data/truncate', async () => {
    const mockResp = { deletedCount: 50 };
    nock(BASE_URL).post('/datasets/1/data/truncate').reply(200, mockResp);

    const result = await client.truncateDataset(1);
    expect(result).toEqual(mockResp);
  });

  // --- Data import (multipart) ---
  describe('data import multipart endpoints', () => {
    // 공통: fileId -> /files/{id} 정보 + /files/{id}/content 다운로드 모킹
    function mockFileDownload(fileId: number, originalName: string, content = 'a,b\n1,2') {
      nock(BASE_URL)
        .get(`/files/${fileId}`)
        .reply(200, {
          id: fileId,
          originalName,
          mimeType: 'text/csv',
          fileSize: content.length,
          fileCategory: 'CSV',
        });
      nock(BASE_URL).get(`/files/${fileId}/content`).reply(200, Buffer.from(content));
    }

    it('previewImport POSTs multipart to /datasets/:id/imports/preview', async () => {
      mockFileDownload(11, 'sales.csv');

      const mockResp = {
        fileHeaders: ['a', 'b'],
        sampleRows: [{ a: '1', b: '2' }],
        suggestedMappings: [
          { fileColumn: 'a', datasetColumn: 'a', matchType: 'EXACT', confidence: 1.0 },
        ],
        totalRows: 1,
      };
      nock(BASE_URL)
        .post('/datasets/7/imports/preview', (body) => {
          // multipart body 는 문자열로 들어오며, file 파트와 원본 파일명이 포함되어야 한다
          return typeof body === 'string' && body.includes('name="file"') && body.includes('sales.csv');
        })
        .reply(200, mockResp);

      const result = await client.previewImport(7, 11, { delimiter: ',', hasHeader: true });
      expect(result).toEqual(mockResp);
    });

    it('validateImport POSTs multipart with mappings JSON to /datasets/:id/imports/validate', async () => {
      mockFileDownload(12, 'data.csv');

      const mockResp = { totalRows: 3, validRows: 2, errorRows: 1, errors: [] };
      nock(BASE_URL)
        .post('/datasets/7/imports/validate', (body) => {
          return (
            typeof body === 'string' &&
            body.includes('name="mappings"') &&
            body.includes('fileColumn') &&
            body.includes('datasetColumn')
          );
        })
        .reply(200, mockResp);

      const result = await client.validateImport(7, 12, [
        { fileColumn: 'a', datasetColumn: 'col_a' },
      ]);
      expect(result).toEqual(mockResp);
    });

    it('startImport POSTs multipart with importMode to /datasets/:id/imports', async () => {
      mockFileDownload(13, 'load.csv');

      const mockResp = { jobId: 'job-abc', status: 'QUEUED' };
      nock(BASE_URL)
        .post('/datasets/7/imports', (body) => {
          return (
            typeof body === 'string' &&
            body.includes('name="importMode"') &&
            body.includes('REPLACE')
          );
        })
        .reply(201, mockResp);

      const result = await client.startImport(7, 13, {
        mappings: [{ fileColumn: 'a', datasetColumn: 'col_a' }],
        importMode: 'REPLACE',
      });
      expect(result).toEqual(mockResp);
    });

    it('getImportStatus calls GET /datasets/:id/imports/:importId', async () => {
      const mockResp = {
        id: 99,
        datasetId: 7,
        fileName: 'load.csv',
        fileSize: 1024,
        fileType: 'CSV',
        status: 'SUCCESS',
        totalRows: 10,
        successRows: 10,
        errorRows: 0,
        errorDetails: null,
        errorMessage: null,
        importedBy: 'alice',
        startedAt: '2026-04-11T10:00:00',
        completedAt: '2026-04-11T10:00:05',
        createdAt: '2026-04-11T10:00:00',
      };
      nock(BASE_URL).get('/datasets/7/imports/99').reply(200, mockResp);

      const result = await client.getImportStatus(7, 99);
      expect(result).toEqual(mockResp);
    });
  });

  // --- Session permissions (Task 9) ---
  describe('getSessionPermissions', () => {
    it('should GET /auth/me/permissions with Internal auth headers and return codes', async () => {
      const codes = ['dataset:read', 'dataset:delete'];
      const scope = nock(BASE_URL, {
        reqheaders: {
          authorization: `Internal ${TOKEN}`,
          'x-on-behalf-of': String(USER_ID),
        },
      })
        .get('/auth/me/permissions')
        .reply(200, codes);

      const result = await client.getSessionPermissions();
      expect(result).toEqual(codes);
      expect(scope.isDone()).toBe(true);
    });

    it('should propagate error on 500 so caller can fail-closed to []', async () => {
      nock(BASE_URL).get('/auth/me/permissions').reply(500, { message: 'boom' });

      await expect(client.getSessionPermissions()).rejects.toThrow(/API 오류 \(500\)/);
    });
  });

  // --- Error message format ---
  it('should format error message as "API 오류 (status): message"', async () => {
    nock(BASE_URL).get('/datasets').reply(500, { message: 'Internal Server Error' });

    await expect(client.listDatasets()).rejects.toThrow(/API 오류 \(500\): Internal Server Error/);
  });
});
