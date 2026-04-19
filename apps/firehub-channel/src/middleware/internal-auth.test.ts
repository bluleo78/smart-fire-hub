import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Request, Response, NextFunction } from 'express';
import { internalAuth } from './internal-auth.js';

function mockReq(authHeader?: string): Partial<Request> {
  return { headers: authHeader ? { authorization: authHeader } : {} };
}

function mockRes() {
  const res: Partial<Response> & { statusCode?: number; body?: unknown } = {};
  res.status = vi.fn((code: number) => { res.statusCode = code; return res as Response; });
  res.json = vi.fn((data: unknown) => { res.body = data; return res as Response; });
  return res;
}

describe('internalAuth', () => {
  const VALID_TOKEN = 'test-internal-token-123';

  beforeEach(() => {
    process.env.INTERNAL_TOKEN = VALID_TOKEN;
  });

  it('유효한 토큰 → next() 호출', () => {
    const next = vi.fn();
    internalAuth(mockReq(`Internal ${VALID_TOKEN}`) as Request, mockRes() as Response, next as NextFunction);
    expect(next).toHaveBeenCalledOnce();
  });

  it('토큰 없음 → 401', () => {
    const res = mockRes();
    internalAuth(mockReq() as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });

  it('잘못된 토큰 → 401', () => {
    const res = mockRes();
    internalAuth(mockReq('Internal wrong-token') as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });

  it('Bearer 형식 → 401 (Internal만 허용)', () => {
    const res = mockRes();
    internalAuth(mockReq(`Bearer ${VALID_TOKEN}`) as Request, res as Response, vi.fn() as NextFunction);
    expect(res.statusCode).toBe(401);
  });
});
