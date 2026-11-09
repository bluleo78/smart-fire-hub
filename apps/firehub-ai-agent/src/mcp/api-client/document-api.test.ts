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
    // undefined 인자는 백엔드에 null 로 전달되어야 한다(mode 포함, 서버 기본 HYBRID).
    nock(BASE_URL)
      .post('/documents/search', { query: '질의', datasetIds: null, topK: null, mode: null })
      .reply(200, mock);
    const result = await client.searchDocuments('질의', undefined, undefined);
    expect(result).toEqual(mock);
  });

  it('searchDocuments(query, datasetIds, topK) passes datasetIds/topK unchanged', async () => {
    const mock: DocumentSearchHit[] = [];
    nock(BASE_URL)
      .post('/documents/search', { query: '질의', datasetIds: [1, 2], topK: 5, mode: null })
      .reply(200, mock);
    const result = await client.searchDocuments('질의', [1, 2], 5);
    expect(result).toEqual(mock);
  });

  it('searchDocuments(query, ..., mode) passes mode through unchanged', async () => {
    const mock: DocumentSearchHit[] = [];
    // mode 지정 시 본문에 그대로 전달되어야 한다.
    nock(BASE_URL)
      .post('/documents/search', { query: '질의', datasetIds: null, topK: null, mode: 'KEYWORD' })
      .reply(200, mock);
    const result = await client.searchDocuments('질의', undefined, undefined, 'KEYWORD');
    expect(result).toEqual(mock);
  });

  it('searchDocuments 는 1,000자를 넘는 질의를 api 상한으로 잘라 보낸다 (WD-600)', async () => {
    // graphrag_query·search_documents 는 사용자 질문 전체를 넘긴다 — 400 으로 도구가 실패하지 않게 앞 1,000자만 보낸다.
    const long = '가'.repeat(1500);
    const scope = nock(BASE_URL)
      .post('/documents/search', (body) => body.query === '가'.repeat(1000))
      .reply(200, []);
    await expect(client.searchDocuments(long, undefined, undefined)).resolves.toEqual([]);
    expect(scope.isDone()).toBe(true);
  });
});
