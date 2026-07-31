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

// 검수 인박스 소비측(목록·근거·승인·거부) — 기존에는 firehub-web 전용이라 계약 테스트가 없었다.
describe('review-api — 검수 인박스 소비', () => {
  const api = () => createReviewApi(axios.create({ baseURL: 'http://api.test' }));

  it('listReviewItems 는 status/itemType 을 쿼리로 전달한다', async () => {
    nock('http://api.test')
      .get('/graphrag/review-items')
      .query({ status: 'pending', itemType: 'entity' })
      .reply(200, [{ id: 1, itemType: 'entity', status: 'pending' }]);
    const items = await api().listReviewItems('pending', 'entity');
    expect(items).toHaveLength(1);
  });

  it('listReviewItems 는 필터 없이도 호출된다(서버 기본 pending)', async () => {
    nock('http://api.test').get('/graphrag/review-items').reply(200, []);
    expect(await api().listReviewItems()).toEqual([]);
  });

  it('getReviewItemEvidence 는 GET /{id}/evidence 를 호출한다', async () => {
    nock('http://api.test')
      .get('/graphrag/review-items/8/evidence')
      .reply(200, [{ chunkId: 3, content: '원문' }]);
    expect(await api().getReviewItemEvidence(8)).toEqual([{ chunkId: 3, content: '원문' }]);
  });

  it('approveReviewItem 은 correctedValue 를 본문으로 전달한다', async () => {
    const scope = nock('http://api.test')
      .post('/graphrag/review-items/8/approve', { correctedValue: '120000000' })
      .reply(200, { id: 8, status: 'approved' });
    const out = await api().approveReviewItem(8, '120000000');
    expect(scope.isDone()).toBe(true);
    expect(out.status).toBe('approved');
  });

  it('rejectReviewItem 은 본문 없이 POST /{id}/reject 를 호출한다', async () => {
    nock('http://api.test').post('/graphrag/review-items/8/reject').reply(200, { id: 8, status: 'rejected' });
    expect((await api().rejectReviewItem(8)).status).toBe('rejected');
  });

  // 이미 처리된 항목 승인은 서버가 409/500 계열로 막는다 — 삼키지 말고 전파해야 한다.
  it('이미 처리된 항목 승인 실패를 전파한다', async () => {
    nock('http://api.test').post('/graphrag/review-items/8/approve').reply(400, { message: '이미 처리된 항목입니다' });
    await expect(api().approveReviewItem(8)).rejects.toThrow();
  });
});
