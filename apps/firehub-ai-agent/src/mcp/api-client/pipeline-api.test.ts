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

  // previewApiCall: 도구 인자(path/customUrl)를 서버가 읽는 url 하나로 바꿔 보내는지 본문 전체로 확인한다.
  describe('previewApiCall', () => {
    const base = { method: 'GET', dataPath: '$.items' };
    const mock = { rows: [], totalCount: 0 };

    it.each([
      {
        name: '직접 URL',
        input: { customUrl: 'https://api.example.com/data' },
        expected: { url: 'https://api.example.com/data' },
      },
      {
        name: '연결+경로',
        input: { apiConnectionId: 1, path: '/v1/users' },
        expected: { apiConnectionId: 1, url: '/v1/users' },
      },
      {
        name: '연결+루트',
        input: { apiConnectionId: 1, path: '/' },
        expected: { apiConnectionId: 1, url: '/' },
      },
      {
        name: '연결이면 경로 우선',
        input: {
          apiConnectionId: 1,
          path: '/v1/users',
          customUrl: 'https://other.example.com/x',
        },
        expected: { apiConnectionId: 1, url: '/v1/users' },
      },
      {
        name: '연결 없으면 customUrl 우선',
        input: { path: '/v1/users', customUrl: 'https://api.example.com/data' },
        expected: { url: 'https://api.example.com/data' },
      },
      {
        name: '옛 url 이 섞여도 customUrl',
        input: { url: 'https://evil.example.com/x', customUrl: 'https://api.example.com/data' },
        expected: { url: 'https://api.example.com/data' },
      },
      {
        name: '소문자 get',
        input: { method: 'get', customUrl: 'https://api.example.com/data' },
        expected: { method: 'get', url: 'https://api.example.com/data' },
      },
    ])('$name: url 로 보낸다', async ({ input, expected }) => {
      const scope = nock(BASE_URL)
        .post('/pipelines/api-call/preview', { ...base, ...expected })
        .reply(200, mock);
      const result = await client.previewApiCall({ ...base, ...input });
      expect(result).toEqual(mock);
      expect(scope.isDone()).toBe(true);
    });

    it('전체 필드는 url 외에 그대로 남는다', async () => {
      const extra = {
        headers: { 'X-Test': 'a' },
        queryParams: { q: '1' },
        body: '{"a":1}',
        fieldMappings: [{ sourceField: 'id', targetColumn: 'id', dataType: 'INTEGER' }],
        inlineAuth: { token: 't' },
        timeoutMs: 5000,
      };
      const scope = nock(BASE_URL)
        .post('/pipelines/api-call/preview', {
          ...base,
          ...extra,
          apiConnectionId: 1,
          url: '/v1/users',
        })
        .reply(200, mock);
      const result = await client.previewApiCall({
        ...base,
        ...extra,
        apiConnectionId: 1,
        path: '/v1/users',
      });
      expect(result).toEqual(mock);
      expect(scope.isDone()).toBe(true);
    });

    it.each(['POST', 'PUT', 'DELETE', 'post'])('%s 는 요청 없이 거절한다', async (method) => {
      const scope = nock(BASE_URL).post('/pipelines/api-call/preview').reply(200, mock);
      await expect(
        client.previewApiCall({
          method,
          dataPath: '$.items',
          customUrl: 'https://api.example.com/data',
        }),
      ).rejects.toThrow(
        `API_CALL: 에이전트 미리보기는 GET 만 보냅니다 — ${method.toUpperCase()} 요청은 실제 데이터를 바꿀 수 있어 사용자가 파이프라인 편집기의 「테스트 호출」로 확인해야 합니다`,
      );
      expect(scope.isDone()).toBe(false);
    });

    const CONN_MSG = 'API_CALL: apiConnectionId 설정 시 path가 필수입니다(연결의 기본 주소 그대로 부르려면 \'/\')';
    const DIRECT_MSG = 'API_CALL: apiConnectionId 없이 호출하려면 customUrl이 필수입니다';

    it.each([
      { name: '연결만', input: { apiConnectionId: 1 }, message: CONN_MSG },
      {
        name: '연결+customUrl 만',
        input: { apiConnectionId: 1, customUrl: 'https://other.example.com/x' },
        message: CONN_MSG,
      },
      { name: '연결+빈 경로', input: { apiConnectionId: 1, path: '' }, message: CONN_MSG },
      { name: '연결+공백 경로', input: { apiConnectionId: 1, path: '  ' }, message: CONN_MSG },
      { name: '주소 없음', input: {}, message: DIRECT_MSG },
      { name: '경로만', input: { path: '/v1/users' }, message: DIRECT_MSG },
      { name: '빈 URL', input: { customUrl: '' }, message: DIRECT_MSG },
      { name: '공백 URL', input: { customUrl: '  ' }, message: DIRECT_MSG },
    ])('$name: 요청 없이 거절한다', async ({ input, message }) => {
      const scope = nock(BASE_URL).post('/pipelines/api-call/preview').reply(200, mock);
      await expect(client.previewApiCall({ ...base, ...input })).rejects.toThrow(message);
      expect(scope.isDone()).toBe(false);
    });
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
