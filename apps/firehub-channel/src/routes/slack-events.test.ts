import { describe, it, expect, vi, beforeEach } from 'vitest';
import request from 'supertest';
import express from 'express';
import { createHmac } from 'node:crypto';
import { slackEventsRouter } from './slack-events.js';

vi.mock('../clients/firehub-api.js', () => ({
  forwardSlackInbound: vi.fn().mockResolvedValue(undefined),
}));

const SECRET = 'test-secret';
const app = express();
app.use(express.raw({ type: '*/*' }));
app.use('/slack', slackEventsRouter);

function signedHeaders(body: string): Record<string, string> {
  const ts = String(Math.floor(Date.now() / 1000));
  const sig = `v0=${createHmac('sha256', SECRET).update(`v0:${ts}:${body}`).digest('hex')}`;
  return { 'x-slack-request-timestamp': ts, 'x-slack-signature': sig, 'content-type': 'application/json' };
}

beforeEach(() => { process.env.SLACK_SIGNING_SECRET = SECRET; });

describe('POST /slack/events', () => {
  it('url_verification → challenge 응답', async () => {
    const body = JSON.stringify({ type: 'url_verification', challenge: 'abc123' });
    const res = await request(app).post('/slack/events').set(signedHeaders(body)).send(body);
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ challenge: 'abc123' });
  });

  it('event_callback → 200 ack + 비동기 포워딩', async () => {
    const body = JSON.stringify({ type: 'event_callback', team_id: 'T123', event: { type: 'message' } });
    const res = await request(app).post('/slack/events').set(signedHeaders(body)).send(body);
    expect(res.status).toBe(200);
  });

  it('서명 없음 → 401', async () => {
    const res = await request(app).post('/slack/events').send('{}');
    expect(res.status).toBe(401);
  });
});
