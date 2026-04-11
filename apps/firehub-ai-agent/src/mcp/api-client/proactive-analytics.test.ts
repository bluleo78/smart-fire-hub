import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * proactive-api.ts / analytics-api.ts 커버리지 보강용 테스트.
 * 각 모듈의 모든 exported 메서드에 대해 happy-path HTTP 호출을 nock 으로 검증한다.
 * FireHubApiClient 를 거쳐 위임되므로, 위임 계층과 실제 API 모듈을 동시에 커버한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 42;

describe('proactiveApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listSmartJobs calls GET /proactive/jobs', async () => {
    const mock = [{ id: 1, name: 'job1' }];
    nock(BASE_URL).get('/proactive/jobs').reply(200, mock);
    const result = await client.listSmartJobs();
    expect(result).toEqual(mock);
  });

  it('createSmartJob calls POST /proactive/jobs', async () => {
    const body = { name: 'j', prompt: 'p', cronExpression: '0 9 * * *' };
    const mock = { id: 1, ...body };
    nock(BASE_URL)
      .post('/proactive/jobs', (reqBody: Record<string, unknown>) => reqBody.name === 'j')
      .reply(201, mock);
    const result = await client.createSmartJob(body);
    expect(result).toEqual(mock);
  });

  it('updateSmartJob calls PUT /proactive/jobs/:id', async () => {
    const mock = { id: 5, name: 'updated' };
    nock(BASE_URL)
      .put('/proactive/jobs/5', (reqBody: Record<string, unknown>) => reqBody.name === 'updated')
      .reply(200, mock);
    const result = await client.updateSmartJob(5, { name: 'updated' });
    expect(result).toEqual(mock);
  });

  it('deleteSmartJob calls DELETE /proactive/jobs/:id', async () => {
    nock(BASE_URL).delete('/proactive/jobs/7').reply(204);
    const result = await client.deleteSmartJob(7);
    expect(result).toEqual({ success: true });
  });

  it('executeSmartJob calls POST /proactive/jobs/:id/execute', async () => {
    const mock = { executionId: 123, status: 'RUNNING' };
    nock(BASE_URL).post('/proactive/jobs/9/execute').reply(200, mock);
    const result = await client.executeSmartJob(9);
    expect(result).toEqual(mock);
  });

  it('listReportTemplates calls GET /proactive/templates', async () => {
    const mock = [{ id: 1, name: 'tpl' }];
    nock(BASE_URL).get('/proactive/templates').reply(200, mock);
    const result = await client.listReportTemplates();
    expect(result).toEqual(mock);
  });

  it('createReportTemplate calls POST /proactive/templates', async () => {
    const body = {
      name: 'tpl',
      structure: {
        sections: [{ key: 's1', label: 'Section 1' }],
        output_format: 'markdown',
      },
    };
    const mock = { id: 1, ...body };
    nock(BASE_URL)
      .post('/proactive/templates', (reqBody: Record<string, unknown>) => reqBody.name === 'tpl')
      .reply(201, mock);
    const result = await client.createReportTemplate(body);
    expect(result).toEqual(mock);
  });

  it('createSmartJobWithTemplate creates template then job', async () => {
    const template = { id: 77, name: 'auto-tpl' };
    const job = { id: 88, name: 'auto-job', templateId: 77 };
    nock(BASE_URL)
      .post('/proactive/templates', (reqBody: Record<string, unknown>) => reqBody.name === 'auto-tpl')
      .reply(201, template);
    nock(BASE_URL)
      .post('/proactive/jobs', (reqBody: Record<string, unknown>) => reqBody.templateId === 77)
      .reply(201, job);

    const result = (await client.createSmartJobWithTemplate({
      name: 'auto-job',
      prompt: 'do something',
      templateName: 'auto-tpl',
      templateStructure: {
        sections: [{ key: 's1', label: 'Section 1' }],
        output_format: 'markdown',
      },
    })) as { template: typeof template; job: typeof job };

    expect(result.template).toEqual(template);
    expect(result.job).toEqual(job);
  });

  it('createSmartJobWithTemplate with cronExpression keeps enabled unspecified', async () => {
    const template = { id: 1 };
    const job = { id: 2 };
    nock(BASE_URL).post('/proactive/templates').reply(201, template);
    // cronExpression 지정 시 enabled 필드는 payload 에 포함되지 않아야 한다
    nock(BASE_URL)
      .post('/proactive/jobs', (reqBody: Record<string, unknown>) => {
        return reqBody.cronExpression === '*/5 * * * *' && reqBody.enabled === undefined;
      })
      .reply(201, job);

    await client.createSmartJobWithTemplate({
      name: 'n',
      prompt: 'p',
      cronExpression: '*/5 * * * *',
      channels: ['EMAIL'],
      templateName: 't',
      templateStructure: {
        sections: [{ key: 'k', label: 'l' }],
        output_format: 'markdown',
      },
      templateStyle: 'concise',
    });
  });

  it('getReportTemplate calls GET /proactive/templates/:id', async () => {
    const mock = { id: 3, name: 'tpl' };
    nock(BASE_URL).get('/proactive/templates/3').reply(200, mock);
    const result = await client.getReportTemplate(3);
    expect(result).toEqual(mock);
  });

  it('updateReportTemplate calls PUT /proactive/templates/:id', async () => {
    const mock = { id: 3, name: 'renamed' };
    nock(BASE_URL)
      .put('/proactive/templates/3', (reqBody: Record<string, unknown>) => reqBody.name === 'renamed')
      .reply(200, mock);
    const result = await client.updateReportTemplate(3, { name: 'renamed' });
    expect(result).toEqual(mock);
  });

  it('deleteReportTemplate calls DELETE /proactive/templates/:id', async () => {
    nock(BASE_URL).delete('/proactive/templates/3').reply(204);
    const result = await client.deleteReportTemplate(3);
    expect(result).toEqual({ success: true });
  });

  it('listJobExecutions calls GET /proactive/jobs/:jobId/executions with params', async () => {
    const mock = [{ id: 1, status: 'SUCCESS' }];
    nock(BASE_URL)
      .get('/proactive/jobs/5/executions')
      .query({ limit: '10', offset: '0' })
      .reply(200, mock);
    const result = await client.listJobExecutions(5, { limit: 10, offset: 0 });
    expect(result).toEqual(mock);
  });

  it('getExecution calls GET /proactive/jobs/:jobId/executions/:executionId', async () => {
    const mock = { id: 42, status: 'SUCCESS' };
    nock(BASE_URL).get('/proactive/jobs/5/executions/42').reply(200, mock);
    const result = await client.getExecution(5, 42);
    expect(result).toEqual(mock);
  });
});

describe('analyticsApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('executeAnalyticsQuery calls POST /analytics/queries/execute', async () => {
    const mock = {
      queryType: 'SELECT',
      columns: ['id'],
      rows: [{ id: 1 }],
      affectedRows: 0,
      executionTimeMs: 5,
      totalRows: 1,
      truncated: false,
      error: null,
    };
    nock(BASE_URL)
      .post('/analytics/queries/execute', (body: Record<string, unknown>) => {
        return body.sql === 'SELECT 1' && body.maxRows === 100 && body.readOnly === true;
      })
      .reply(200, mock);
    const result = await client.executeAnalyticsQuery('SELECT 1', 100);
    expect(result).toEqual(mock);
  });

  it('createSavedQuery calls POST /analytics/queries', async () => {
    const body = { name: 'q', sqlText: 'SELECT 1' };
    const mock = { id: 1, ...body };
    nock(BASE_URL)
      .post('/analytics/queries', (reqBody: Record<string, unknown>) => reqBody.name === 'q')
      .reply(201, mock);
    const result = await client.createSavedQuery(body);
    expect(result).toMatchObject({ id: 1, name: 'q' });
  });

  it('listSavedQueries calls GET /analytics/queries with search params', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL)
      .get('/analytics/queries')
      .query({ search: 'foo', folder: 'bar' })
      .reply(200, mock);
    const result = await client.listSavedQueries({ search: 'foo', folder: 'bar' });
    expect(result).toEqual(mock);
  });

  it('executeSavedQuery calls POST /analytics/queries/:id/execute', async () => {
    const mock = {
      queryType: 'SELECT',
      columns: [],
      rows: [],
      affectedRows: 0,
      executionTimeMs: 1,
      totalRows: 0,
      truncated: false,
      error: null,
    };
    nock(BASE_URL)
      .post('/analytics/queries/5/execute', (body: Record<string, unknown>) => body.readOnly === true)
      .reply(200, mock);
    const result = await client.executeSavedQuery(5);
    expect(result).toEqual(mock);
  });

  it('getDataSchema calls GET /analytics/queries/schema', async () => {
    const mock = { tables: [] };
    nock(BASE_URL).get('/analytics/queries/schema').reply(200, mock);
    const result = await client.getDataSchema();
    expect(result).toEqual(mock);
  });

  it('createChart calls POST /analytics/charts', async () => {
    const body = {
      name: 'c',
      savedQueryId: 1,
      chartType: 'BAR' as const,
      config: { xAxis: 'x', yAxis: ['y'] },
    };
    const mock = { id: 1, ...body };
    nock(BASE_URL)
      .post('/analytics/charts', (reqBody: Record<string, unknown>) => reqBody.name === 'c')
      .reply(201, mock);
    const result = await client.createChart(body);
    expect(result).toMatchObject({ id: 1, name: 'c' });
  });

  it('listCharts calls GET /analytics/charts', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/analytics/charts').query({ search: 'foo' }).reply(200, mock);
    const result = await client.listCharts({ search: 'foo' });
    expect(result).toEqual(mock);
  });

  it('getChartData calls GET /analytics/charts/:id/data', async () => {
    const mock = {
      chart: { id: 1 },
      queryResult: { columns: [], rows: [] },
    };
    nock(BASE_URL).get('/analytics/charts/1/data').reply(200, mock);
    const result = await client.getChartData(1);
    expect(result).toEqual(mock);
  });

  it('createDashboard calls POST /analytics/dashboards', async () => {
    const body = { name: 'd' };
    const mock = { id: 1, ...body };
    nock(BASE_URL)
      .post('/analytics/dashboards', (reqBody: Record<string, unknown>) => reqBody.name === 'd')
      .reply(201, mock);
    const result = await client.createDashboard(body);
    expect(result).toMatchObject({ id: 1, name: 'd' });
  });

  it('listDashboards calls GET /analytics/dashboards', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/analytics/dashboards').reply(200, mock);
    const result = await client.listDashboards();
    expect(result).toEqual(mock);
  });

  it('addDashboardWidget calls POST /analytics/dashboards/:id/widgets', async () => {
    const body = { chartId: 1, positionX: 0, positionY: 0, width: 4, height: 3 };
    const mock = { id: 10, dashboardId: 2, ...body };
    nock(BASE_URL)
      .post(
        '/analytics/dashboards/2/widgets',
        (reqBody: Record<string, unknown>) => reqBody.chartId === 1,
      )
      .reply(201, mock);
    const result = await client.addDashboardWidget(2, body);
    expect(result).toMatchObject({ id: 10, chartId: 1 });
  });
});
