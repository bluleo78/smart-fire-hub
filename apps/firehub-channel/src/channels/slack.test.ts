import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { sendSlackMessage, addSlackReaction, postSlackEphemeral } from './slack.js';

const BOT_TOKEN = 'xoxb-test';
const CHANNEL = 'C123';

beforeEach(() => nock.cleanAll());
afterEach(() => { if (!nock.isDone()) throw new Error('nock pending: ' + nock.pendingMocks()); });

describe('sendSlackMessage', () => {
  it('DM 전송 성공', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage')
      .reply(200, { ok: true, ts: '123.456' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '안녕' })).resolves.toMatchObject({ ok: true });
  });

  it('스레드 답글 전송', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage')
      .reply(200, { ok: true, ts: '123.789' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '답글', threadTs: '123.456' })).resolves.toMatchObject({ ok: true });
  });

  it('Slack API 오류 → 에러 throw', async () => {
    nock('https://slack.com')
      .post('/api/chat.postMessage')
      .reply(200, { ok: false, error: 'channel_not_found' });

    await expect(sendSlackMessage({ botToken: BOT_TOKEN, channel: CHANNEL, text: '실패' })).rejects.toThrow('channel_not_found');
  });
});

describe('addSlackReaction', () => {
  it('reaction 추가 성공', async () => {
    nock('https://slack.com')
      .post('/api/reactions.add')
      .reply(200, { ok: true });

    await expect(addSlackReaction({ botToken: BOT_TOKEN, channel: CHANNEL, timestamp: '123.456', name: 'eyes' })).resolves.toBeUndefined();
  });
});

describe('postSlackEphemeral', () => {
  it('ephemeral 전송 성공', async () => {
    nock('https://slack.com')
      .post('/api/chat.postEphemeral')
      .reply(200, { ok: true });

    await expect(postSlackEphemeral({ botToken: BOT_TOKEN, channel: CHANNEL, user: 'U123', text: '안내' })).resolves.toBeUndefined();
  });
});
