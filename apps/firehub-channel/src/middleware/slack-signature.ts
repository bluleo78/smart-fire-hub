import { createHmac, timingSafeEqual } from 'node:crypto';
import type { Request, Response, NextFunction } from 'express';

// Slack 요청의 시간 차이 허용 범위 (5분 = 300초)
const SKEW_TOLERANCE_SECONDS = 300;

/**
 * Slack HMAC-SHA256 서명 검증 미들웨어
 * - x-slack-request-timestamp: 요청 시각 (Unix 초)
 * - x-slack-signature: v0=<hex-hmac> 형식의 서명
 * - 재전송 공격 방지를 위해 5분 이상 지난 요청 거부
 */
export function slackSignature(req: Request, res: Response, next: NextFunction): void {
  const ts = req.headers['x-slack-request-timestamp'] as string | undefined;
  const sig = req.headers['x-slack-signature'] as string | undefined;
  const secret = process.env.SLACK_SIGNING_SECRET;

  if (!ts || !sig || !secret) {
    res.status(401).json({ ok: false, error: 'missing_signature_headers' });
    return;
  }

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(ts)) > SKEW_TOLERANCE_SECONDS) {
    res.status(401).json({ ok: false, error: 'timestamp_expired' });
    return;
  }

  const rawBody = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
  const baseString = `v0:${ts}:${rawBody}`;
  const expected = `v0=${createHmac('sha256', secret).update(baseString).digest('hex')}`;

  const expectedBuf = Buffer.from(expected, 'utf8');
  const sigBuf = Buffer.from(sig, 'utf8');

  // 타이밍 공격 방지를 위해 timingSafeEqual 사용
  if (expectedBuf.length !== sigBuf.length || !timingSafeEqual(expectedBuf, sigBuf)) {
    res.status(401).json({ ok: false, error: 'invalid_signature' });
    return;
  }

  next();
}
