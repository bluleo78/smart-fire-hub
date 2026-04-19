import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { forwardSlackInbound } from './firehub-api.js';

const API_BASE = 'http://api:8080';

beforeEach(() => {
  process.env.FIREHUB_API_BASE_URL = API_BASE;
  nock.cleanAll();
});
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending'); });

describe('forwardSlackInbound', () => {
  it('inbound 이벤트 포워딩 성공', async () => {
    nock(API_BASE)
      .post('/api/v1/channels/slack/inbound', { teamId: 'T123', event: { type: 'message' } })
      .reply(200);

    await expect(forwardSlackInbound('T123', { type: 'message' })).resolves.toBeUndefined();
  });

  it('firehub-api 오류 → 에러 throw', async () => {
    nock(API_BASE)
      .post('/api/v1/channels/slack/inbound')
      .reply(500);

    await expect(forwardSlackInbound('T123', { type: 'message' })).rejects.toThrow();
  });
});
