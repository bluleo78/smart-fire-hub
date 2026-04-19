import { describe, it, expect, vi, beforeEach } from 'vitest';
import request from 'supertest';
import express from 'express';
import { sendRouter } from './send.js';

vi.mock('../channels/slack.js', () => ({
  sendSlackMessage: vi.fn().mockResolvedValue({ ok: true, ts: '123' }),
}));
vi.mock('../channels/kakao.js', () => ({
  sendKakaoMessage: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('../channels/email.js', () => ({
  sendEmail: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('../middleware/internal-auth.js', () => ({
  internalAuth: (_req: unknown, _res: unknown, next: () => void) => next(),
}));

const app = express();
app.use(express.json());
app.use('/send', sendRouter);

describe('POST /send', () => {
  it('SLACK 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'SLACK',
      recipient: { slackBotToken: 'xoxb-test', slackChannelId: 'C123' },
      message: { text: '안녕' },
    });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ ok: true });
  });

  it('KAKAO 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'KAKAO',
      recipient: { kakaoAccessToken: 'token' },
      message: { text: '메시지' },
    });
    expect(res.status).toBe(200);
  });

  it('EMAIL 채널 발송 성공', async () => {
    const res = await request(app).post('/send').send({
      channel: 'EMAIL',
      recipient: { emailAddress: 'a@b.com', smtpConfig: { host: 'h', port: 587, secure: false, user: 'u', pass: 'p' } },
      message: { text: '제목', html: '<p>내용</p>' },
    });
    expect(res.status).toBe(200);
  });

  it('알 수 없는 channel → 400', async () => {
    const res = await request(app).post('/send').send({ channel: 'UNKNOWN', recipient: {}, message: { text: '' } });
    expect(res.status).toBe(400);
  });

  it('auth_error → 401', async () => {
    const { sendKakaoMessage } = await import('../channels/kakao.js');
    vi.mocked(sendKakaoMessage).mockRejectedValueOnce(new Error('auth_error'));

    const res = await request(app).post('/send').send({
      channel: 'KAKAO',
      recipient: { kakaoAccessToken: 'expired' },
      message: { text: '실패' },
    });
    expect(res.status).toBe(401);
  });

  it('upstream_error → 503', async () => {
    const { sendSlackMessage } = await import('../channels/slack.js');
    vi.mocked(sendSlackMessage).mockRejectedValueOnce(new Error('upstream_error'));

    const res = await request(app).post('/send').send({
      channel: 'SLACK',
      recipient: { slackBotToken: 'xoxb', slackChannelId: 'C1' },
      message: { text: '실패' },
    });
    expect(res.status).toBe(503);
  });
});
