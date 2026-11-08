/**
 * 최종 에러 핸들러 단위 테스트 (WD-550)
 *
 * body-parser 가 만드는 클라이언트 오류(4xx + 알려진 type)만 해당 상태로 내보내고,
 * 그 밖의 모든 오류는 내부 정보 없이 500 으로 숨기는지, 응답이 이미 나간 경우엔 Express 기본
 * 처리기로 위임하는지 확인한다.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import request from 'supertest';
import { errorHandler, formatByteLimit, JSON_BODY_LIMIT } from './error-handler.js';

/** 지정한 오류를 던지는 라우트 하나 + 에러 핸들러로 이뤄진 최소 앱 */
function appThrowing(err: unknown): express.Express {
  const app = express();
  app.get('/boom', () => {
    throw err;
  });
  app.use(errorHandler);
  return app;
}

/** body-parser(http-errors) 가 만드는 오류 객체 모양을 흉내낸다 */
function bodyParserError(status: number, type: string, extra: Record<string, unknown> = {}): Error {
  return Object.assign(new Error(`bp ${type}`), { status, statusCode: status, type, expose: true }, extra);
}

describe('errorHandler (WD-550)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('JSON_BODY_LIMIT 은 1mb 로 명시된다', () => {
    expect(JSON_BODY_LIMIT).toBe('1mb');
  });

  it('라우트가 던진 일반 Error → 500 Internal server error (내부 메시지 비노출), console.error', async () => {
    const res = await request(appThrowing(new Error('db password=secret'))).get('/boom');
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal server error' });
    expect(console.error).toHaveBeenCalled();
  });

  it('status 가 있어도 body-parser type 이 아니면 500', async () => {
    const err = Object.assign(new Error('x'), { status: 404 });
    const res = await request(appThrowing(err)).get('/boom');
    expect(res.status).toBe(500);
  });

  it('알려진 type 이라도 status 가 4xx 가 아니면 500 (stream.not.readable 등)', async () => {
    const res = await request(appThrowing(bodyParserError(500, 'stream.not.readable'))).get('/boom');
    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal server error' });
  });

  it('프로토타입 키(constructor·toString)를 type 으로 가진 오류는 body-parser 오류로 보지 않는다 → 500', async () => {
    for (const type of ['constructor', 'toString', '__proto__']) {
      const res = await request(appThrowing(bodyParserError(400, type))).get('/boom');
      expect(res.status).toBe(500);
      expect(res.body).toEqual({ error: 'Internal server error' });
    }
  });

  it('entity.parse.failed → 400 한국어, console.warn 만 사용', async () => {
    const res = await request(appThrowing(bodyParserError(400, 'entity.parse.failed'))).get('/boom');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: '요청 본문을 해석할 수 없습니다 — 올바른 JSON 이어야 합니다.' });
    expect(console.warn).toHaveBeenCalledTimes(1);
    expect(console.error).not.toHaveBeenCalled();
  });

  it('entity.too.large → 413, err.limit 바이트를 사람이 읽는 단위로', async () => {
    const res = await request(appThrowing(bodyParserError(413, 'entity.too.large', { limit: 5 * 1024 * 1024 }))).get(
      '/boom',
    );
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 5MB).' });
  });

  it('entity.too.large 인데 limit 정보가 없으면 괄호 없이', async () => {
    const res = await request(appThrowing(bodyParserError(413, 'entity.too.large'))).get('/boom');
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다.' });
  });

  it('charset.unsupported / encoding.unsupported → 415 한국어', async () => {
    const r1 = await request(appThrowing(bodyParserError(415, 'charset.unsupported'))).get('/boom');
    expect(r1.status).toBe(415);
    expect(r1.body.error).toContain('문자셋');
    const r2 = await request(appThrowing(bodyParserError(415, 'encoding.unsupported'))).get('/boom');
    expect(r2.status).toBe(415);
    expect(r2.body.error).toContain('Content-Encoding');
  });

  it('request.size.invalid → 400 한국어', async () => {
    const res = await request(appThrowing(bodyParserError(400, 'request.size.invalid'))).get('/boom');
    expect(res.status).toBe(400);
    expect(res.body.error).toMatch(/Content-Length/);
  });

  it('응답 헤더가 이미 나갔으면 next(err) 로 Express 기본 처리기에 위임', () => {
    const next = vi.fn();
    const res = { headersSent: true, status: vi.fn(), json: vi.fn() } as unknown as express.Response;
    const err = new Error('late');
    errorHandler(err, {} as express.Request, res, next);
    expect(next).toHaveBeenCalledWith(err);
    expect(res.status).not.toHaveBeenCalled();
  });
});

describe('formatByteLimit', () => {
  it('MB/KB/바이트 단위로 표기한다', () => {
    expect(formatByteLimit(1024 * 1024)).toBe('1MB');
    expect(formatByteLimit(5 * 1024 * 1024)).toBe('5MB');
    expect(formatByteLimit(100 * 1024)).toBe('100KB');
    expect(formatByteLimit(1500)).toBe('1500B');
  });
});
