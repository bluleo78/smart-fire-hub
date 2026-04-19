import { Router } from 'express';
import { internalAuth } from '../middleware/internal-auth.js';
import { sendSlackMessage } from '../channels/slack.js';
import { sendKakaoMessage } from '../channels/kakao.js';
import { sendEmail } from '../channels/email.js';

export const sendRouter: Router = Router();

/**
 * POST /send — 채널별 메시지 발송 엔드포인트
 * internalAuth 미들웨어로 내부 서비스만 접근 허용
 */
sendRouter.post('/', internalAuth, async (req, res) => {
  const { channel, recipient, message, threadTs } = req.body as {
    channel: string;
    recipient: Record<string, unknown>;
    message: { text?: string; html?: string; blocks?: unknown[] };
    threadTs?: string;
  };

  try {
    if (channel === 'SLACK') {
      await sendSlackMessage({
        botToken: recipient.slackBotToken as string,
        channel: recipient.slackChannelId as string,
        text: message.text ?? '',
        threadTs,
        blocks: message.blocks,
      });
    } else if (channel === 'KAKAO') {
      await sendKakaoMessage({
        accessToken: recipient.kakaoAccessToken as string,
        text: message.text ?? '',
      });
    } else if (channel === 'EMAIL') {
      await sendEmail({
        smtpConfig: recipient.smtpConfig as { host: string; port: number; secure: boolean; user: string; pass: string },
        to: recipient.emailAddress as string,
        subject: message.text ?? '',
        html: message.html ?? message.text ?? '',
      });
    } else {
      res.status(400).json({ ok: false, error: 'unknown_channel' });
      return;
    }
    res.json({ ok: true });
  } catch (err) {
    const msg = (err as Error).message;
    if (msg === 'auth_error') { res.status(401).json({ ok: false, error: msg }); return; }
    if (msg === 'upstream_error') { res.status(503).json({ ok: false, error: msg }); return; }
    res.status(503).json({ ok: false, error: 'upstream_error' });
  }
});
