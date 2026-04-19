import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createHmac } from 'node:crypto';
import type { Request, Response, NextFunction } from 'express';
import { slackSignature } from './slack-signature.js';

function makeSignature(secret: string, ts: string, body: string): string {
  const baseString = `v0:${ts}:${body}`;
  const hash = createHmac('sha256', secret).update(baseString).digest('hex');
  return `v0=${hash}`;
}

function mockReq(headers: Record<string, string>, rawBody: string): Partial<Request> {
  return { headers, body: rawBody } as unknown as Partial<Request>;
}

function mockRes() {
  const res: Partial<Response> & { statusCode?: number; body?: unknown } = {};
  res.status = vi.fn((code: number) => { res.statusCode = code; return res as Response; });
  res.json = vi.fn((data: unknown) => { res.body = data; return res as Response; });
  return res;
}

const SECRET = 'test-signing-secret';
const BODY = '{"type":"event_callback"}';

describe('slackSignature', () => {
  beforeEach(() => {
    process.env.SLACK_SIGNING_SECRET = SECRET;
  });

  it('유효한 서명 → next()', () => {
    const ts = String(Math.floor(Date.now() / 1000));
    const sig = makeSignature(SECRET, ts, BODY);
    const next = vi.fn();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': sig }, BODY) as Request,
      mockRes() as Response,
      next as NextFunction,
    );
    expect(next).toHaveBeenCalledOnce();
  });

  it('타임스탬프 5분 초과 → 401', () => {
    const ts = String(Math.floor(Date.now() / 1000) - 400);
    const sig = makeSignature(SECRET, ts, BODY);
    const res = mockRes();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': sig }, BODY) as Request,
      res as Response,
      vi.fn() as NextFunction,
    );
    expect(res.statusCode).toBe(401);
  });

  it('서명 조작 → 401', () => {
    const ts = String(Math.floor(Date.now() / 1000));
    const res = mockRes();
    slackSignature(
      mockReq({ 'x-slack-request-timestamp': ts, 'x-slack-signature': 'v0=fakehash' }, BODY) as Request,
      res as Response,
      vi.fn() as NextFunction,
    );
    expect(res.statusCode).toBe(401);
  });

  it('헤더 없음 → 401', () => {
    const res = mockRes();
    slackSignature(mockReq({}, BODY) as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });
});
