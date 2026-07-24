import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import axios from 'axios';
import { createReviewApi } from './review-api.js';

afterEach(() => nock.cleanAll());

describe('review-api', () => {
  it('lookupSynonymDecision은 approved/rejected 상태만 반환하고 그 외는 undefined다', async () => {
    nock('http://api.test')
      .get('/graphrag/review-items/synonym/lookup')
      .query({ entityType: 'PERSON', nameA: '홍길동', nameB: '홍 길동' })
      .reply(200, { status: 'approved' });
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createReviewApi(client);
    const status = await api.lookupSynonymDecision('PERSON', '홍길동', '홍 길동');
    expect(status).toBe('approved');
  });

  it('lookupSynonymDecision은 결정이 없으면(pending 등) undefined를 반환한다', async () => {
    nock('http://api.test')
      .get('/graphrag/review-items/synonym/lookup')
      .query({ entityType: 'PERSON', nameA: 'a', nameB: 'b' })
      .reply(200, { status: 'pending' });
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createReviewApi(client);
    const status = await api.lookupSynonymDecision('PERSON', 'a', 'b');
    expect(status).toBeUndefined();
  });

  it('recordPendingSynonym은 synonym/pending 엔드포인트로 POST한다', async () => {
    const scope = nock('http://api.test')
      .post('/graphrag/review-items/synonym/pending', {
        entityType: 'PERSON', nameA: 'a', nameB: 'b', similarity: 0.8, rationale: '유사 표기',
      })
      .reply(200);
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createReviewApi(client);
    await api.recordPendingSynonym('PERSON', 'a', 'b', 0.8, '유사 표기');
    expect(scope.isDone()).toBe(true);
  });

  it('recordPropertyReview는 property/pending 엔드포인트로 POST한다', async () => {
    const scope = nock('http://api.test')
      .post('/graphrag/review-items/property/pending', {
        datasetId: 7, chunkId: 3, entityKey: 'PERSON:홍길동', entityType: 'PERSON',
        propertyName: '생년월일', dataType: 'date', rawText: '1990년 5월',
      })
      .reply(200);
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createReviewApi(client);
    await api.recordPropertyReview(7, 3, 'PERSON:홍길동', 'PERSON', '생년월일', 'date', '1990년 5월');
    expect(scope.isDone()).toBe(true);
  });
});
