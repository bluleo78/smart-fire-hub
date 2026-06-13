import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';
import type { DocumentSearchHit } from './document-api.js';

/**
 * document-api.ts 커버리지 테스트.
 * FireHubApiClient 위임 계층을 통해 document-api 메서드를 nock으로 검증한다.
 * 특히 datasetIds/topK 의 undefined → null 변환을 요청 본문 매칭으로 확인한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('documentApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('searchDocuments(query) sends datasetIds/topK as null and returns hits', async () => {
    const mock: DocumentSearchHit[] = [
      {
        chunkId: 10,
        documentFileId: 3,
        datasetId: 1,
        fileName: '소방안전.pdf',
        chunkIndex: 0,
        content: '소화기 점검 주기는 1년이다.',
        score: 0.92,
      },
    ];
    // undefined 인자는 백엔드에 null 로 전달되어야 한다.
    nock(BASE_URL)
      .post('/documents/search', { query: '질의', datasetIds: null, topK: null })
      .reply(200, mock);
    const result = await client.searchDocuments('질의', undefined, undefined);
    expect(result).toEqual(mock);
  });

  it('searchDocuments(query, datasetIds, topK) passes datasetIds/topK unchanged', async () => {
    const mock: DocumentSearchHit[] = [];
    nock(BASE_URL)
      .post('/documents/search', { query: '질의', datasetIds: [1, 2], topK: 5 })
      .reply(200, mock);
    const result = await client.searchDocuments('질의', [1, 2], 5);
    expect(result).toEqual(mock);
  });
});
