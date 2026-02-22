import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { Request, Response, NextFunction } from 'express';
import { internalAuth } from './auth.js';

function mockReq(authHeader?: string): Partial<Request> {
  return {
    headers: authHeader ? { authorization: authHeader } : {},
  };
}

function mockRes(): Partial<Response> & { statusCode?: number; body?: unknown } {
  const res: Partial<Response> & { statusCode?: number; body?: unknown } = {};
  res.status = vi.fn((code: number) => {
    res.statusCode = code;
    return res as Response;
  });
  res.json = vi.fn((data: unknown) => {
    res.body = data;
    return res as Response;
  });
  return res;
}

describe('internalAuth middleware', () => {
  const VALID_TOKEN = 'test-internal-token-12345';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  it('should call next() for valid Internal token', () => {
    const req = mockReq(`Internal ${VALID_TOKEN}`);
    const res = mockRes();
    const next = vi.fn() as NextFunction;

    internalAuth(req as Request, res as Response, next);

    expect(next).toHaveBeenCalledOnce();
    expect(res.status).not.toHaveBeenCalled();
  });

  it('should return 401 when Authorization header is missing', () => {
    const req = mockReq();
    const res = mockRes();
    const next = vi.fn() as NextFunction;

    internalAuth(req as Request, res as Response, next);

    expect(next).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(401);
  });

  it('should return 401 for invalid token', () => {
    const req = mockReq('Internal wrong-token');
    const res = mockRes();
    const next = vi.fn() as NextFunction;

    internalAuth(req as Request, res as Response, next);

    expect(next).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(401);
  });

  it('should return 401 for Bearer format instead of Internal', () => {
    const req = mockReq(`Bearer ${VALID_TOKEN}`);
    const res = mockRes();
    const next = vi.fn() as NextFunction;

    internalAuth(req as Request, res as Response, next);

    expect(next).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(401);
  });
});
