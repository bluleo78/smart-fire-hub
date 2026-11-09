import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * dataset-search-api.ts 테스트(WD-600) — 질의 길이 상한 맞춤.
 * 데이터셋 검색 도구는 사용자 질문 전체를 질의로 넘길 수 있어, api 상한(1,000자)을 넘으면 잘라 보낸다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('datasetSearchApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('1,000자 이하 질의는 그대로 보낸다', async () => {
    const scope = nock(BASE_URL)
      .post('/datasets/search', (body) => body.query === '화재 출동' && body.mode === null)
      .reply(200, []);
    await expect(client.searchDatasets('화재 출동')).resolves.toEqual([]);
    expect(scope.isDone()).toBe(true);
  });

  it('1,000자를 넘는 질의는 앞 1,000자로 잘라 보낸다', async () => {
    const scope = nock(BASE_URL)
      .post('/datasets/search', (body) => body.query === 'q'.repeat(1000))
      .reply(200, []);
    await expect(client.searchDatasets('q'.repeat(1001), 'HYBRID')).resolves.toEqual([]);
    expect(scope.isDone()).toBe(true);
  });
});
