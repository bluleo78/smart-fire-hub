import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * audit-api.ts 커버리지 테스트.
 * FireHubApiClient 위임 계층을 통해 audit-api 메서드를 nock으로 검증한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('auditApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listAuditLogs calls GET /admin/audit-logs', async () => {
    const mock = {
      content: [
        {
          id: 1,
          userId: 2,
          username: 'hong',
          actionType: 'DELETE',
          resource: 'dataset',
          resourceId: '42',
          description: '데이터셋 삭제',
          actionTime: '2026-04-12T14:00:00',
          ipAddress: '127.0.0.1',
          userAgent: null,
          result: 'SUCCESS',
          errorMessage: null,
          metadata: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    nock(BASE_URL).get('/admin/audit-logs').reply(200, mock);
    const result = await client.listAuditLogs();
    expect(result).toEqual(mock);
  });

  it('listAuditLogs passes result=FAILURE filter', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/admin/audit-logs').query({ result: 'FAILURE' }).reply(200, mock);
    const result = await client.listAuditLogs({ result: 'FAILURE' });
    expect(result.totalElements).toBe(0);
  });

  it('listAuditLogs passes resource + actionType filters', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL)
      .get('/admin/audit-logs')
      .query({ resource: 'dataset', actionType: 'DELETE' })
      .reply(200, mock);
    const result = await client.listAuditLogs({ resource: 'dataset', actionType: 'DELETE' });
    expect(result.content).toHaveLength(0);
  });

  it('listAuditLogs passes search + page + size params', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 1, size: 50 };
    nock(BASE_URL)
      .get('/admin/audit-logs')
      .query({ search: '홍길동', page: '1', size: '50' })
      .reply(200, mock);
    const result = await client.listAuditLogs({ search: '홍길동', page: 1, size: 50 });
    expect(result.number).toBe(1);
    expect(result.size).toBe(50);
  });
});
