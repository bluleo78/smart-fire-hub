import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * admin-api.ts 커버리지 테스트.
 * FireHubApiClient 위임 계층을 통해 admin-api 메서드를 nock으로 검증한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('adminApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listUsers calls GET /users', async () => {
    const mock = {
      content: [{ id: 2, username: 'kim', email: 'kim@test.com', name: '김철수', isActive: true, createdAt: '2026-01-01' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    nock(BASE_URL).get('/users').reply(200, mock);
    const result = await client.listUsers();
    expect(result).toEqual(mock);
  });

  it('listUsers passes search query param', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/users').query({ search: '김' }).reply(200, mock);
    const result = await client.listUsers({ search: '김' });
    expect(result.totalElements).toBe(0);
  });

  it('getUser calls GET /users/:id', async () => {
    const mock = { id: 2, username: 'kim', email: 'kim@test.com', name: '김철수', isActive: true, createdAt: '2026-01-01', roles: [{ id: 2, name: 'USER', description: null, isSystem: true }] };
    nock(BASE_URL).get('/users/2').reply(200, mock);
    const result = await client.getUser(2);
    expect(result.roles).toHaveLength(1);
    expect(result.roles[0].name).toBe('USER');
  });

  it('setUserRoles calls PUT /users/:id/roles', async () => {
    nock(BASE_URL)
      .put('/users/2/roles', (body: Record<string, unknown>) => JSON.stringify(body.roleIds) === '[1]')
      .reply(200);
    await expect(client.setUserRoles(2, [1])).resolves.toBeUndefined();
  });

  it('setUserActive calls PUT /users/:id/active', async () => {
    nock(BASE_URL)
      .put('/users/3/active', (body: Record<string, unknown>) => body.active === false)
      .reply(200);
    await expect(client.setUserActive(3, false)).resolves.toBeUndefined();
  });

  it('listRoles calls GET /roles', async () => {
    const mock = [{ id: 1, name: 'ADMIN', description: null, isSystem: true }, { id: 2, name: 'USER', description: null, isSystem: true }];
    nock(BASE_URL).get('/roles').reply(200, mock);
    const result = await client.listRoles();
    expect(result).toHaveLength(2);
    expect(result[0].name).toBe('ADMIN');
  });

  it('listPermissions calls GET /permissions', async () => {
    const mock = [{ id: 1, code: 'user:read', description: '사용자 조회', category: 'user' }];
    nock(BASE_URL).get('/permissions').reply(200, mock);
    const result = await client.listPermissions();
    expect(result[0].code).toBe('user:read');
  });

  it('listPermissions passes category query param', async () => {
    const mock = [{ id: 1, code: 'user:read', description: '사용자 조회', category: 'user' }];
    nock(BASE_URL).get('/permissions').query({ category: 'user' }).reply(200, mock);
    const result = await client.listPermissions({ category: 'user' });
    expect(result[0].category).toBe('user');
  });
});
