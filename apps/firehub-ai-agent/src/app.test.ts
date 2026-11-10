/**
 * 앱 조립(createApp) 수준의 본문 파싱 오류 처리 테스트 (WD-550, WD-631)
 *
 * 인증·라우트 전용 파서·최종 에러 핸들러가 실제 마운트 순서대로 맞물렸을 때
 * - 토큰 없는 요청은 본문 크기·형식과 무관하게 파서를 거치지 않고 401 로 끝나는지(WD-631: 인증 → 파서 순서),
 * - 토큰 있는 요청의 깨진 JSON / 한도 초과 본문은 500 이 아니라 400/413(한국어 메시지)으로 나가는지 확인한다.
 * 토큰 있는 요청이 "파서를 통과했다"는 증거로는 라우트 본문의 필수 필드 검증 400(파서 오류와 다른 문구)을 쓴다 —
 * 라우트의 무거운 로직(에이전트 실행·PDF 렌더 등)까지 가지 않게 하기 위함이다.
 */
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import request from 'supertest';
import { createApp } from './app.js';
import { ON_BEHALF_OF_HEADER, ON_BEHALF_OF_TENANT_HEADER } from './constants.js';

/** 테스트용 내부 서비스 토큰 */
const TOKEN = 'app-test-internal-token';
/** 인증 헤더 값 */
const AUTH = `Internal ${TOKEN}`;
/** 파서가 깨진 JSON 에 주는 400 메시지 */
const PARSE_FAILED = { error: '요청 본문을 해석할 수 없습니다 — 올바른 JSON 이어야 합니다.' };

/** 지정한 바이트 수(정확히)의 JSON 본문을 만든다 — `{"message":"xxx…"}` 형태 */
function jsonOfBytes(totalBytes: number, field = 'message'): string {
  const overhead = Buffer.byteLength(`{"${field}":""}`);
  return `{"${field}":"${'a'.repeat(Math.floor(totalBytes) - overhead)}"}`;
}

/**
 * createApp 에 JSON POST 를 보내는 요청을 만든다.
 * @param auth true 면 올바른 내부 토큰, 문자열이면 그 Authorization 값, 없으면 인증 헤더 없음
 * @param headers 그 밖에 실을 헤더(대행 헤더·Content-Type 덮어쓰기 등)
 */
function post(path: string, opts: { auth?: boolean | string; headers?: Record<string, string> } = {}) {
  const req = request(createApp()).post(path).set('Content-Type', 'application/json');
  if (opts.auth) req.set('Authorization', opts.auth === true ? AUTH : opts.auth);
  for (const [name, value] of Object.entries(opts.headers ?? {})) req.set(name, value);
  return req;
}

/**
 * 과대 본문을 「본문 없이」 보내 인증 거절 응답을 받는다 (WD-696).
 *
 * 왜: supertest 로 6MB 본문을 끝까지 쓰면, 인증이 파서보다 먼저라 서버가 본문을 다 받기 전에 401 을 보내고 연결을 닫는다.
 * 서버 쪽에 읽지 않은 본문이 남은 채 닫히면 커널이 RST 를 보내, 클라이언트는 쓰기 중 ECONNRESET 을 받거나 이미 도착한
 * 401 까지 잃는다(macOS·Node 24 에서 매번 재현). 쓰기 오류를 삼키는 방식도 RST 가 응답을 지우면 소용없다.
 *
 * 그래서 `Expect: 100-continue` + Content-Length(과대 크기)만 보내고, 서버의 `checkContinue` 처리기는 100 Continue 를
 * 보내지 않고 바로 앱에 넘긴다. 클라이언트는 'continue' 를 받기 전에는 본문을 쓰지 않으므로
 * - 인증이 먼저면: 본문 한 바이트도 없이 401 이 온다 → 「파서가 본문을 버퍼링·파싱하지 않는다」를 그대로 보여 준다.
 * - 파서가 먼저로 퇴행하면: 파서는 Content-Length 한도 초과(413)여도 본문을 다 받아 버린 뒤에야 응답하므로,
 *   본문을 기다리다 아래 응답 대기 한도에 걸려 원인 메시지와 함께 실패한다(직접 확인함).
 * 응답 없이 연결이 끊기면(error 이벤트) 그대로 reject 해 테스트가 실패한다 — ECONNRESET 을 삼키지 않는다.
 *
 * @param path 요청 경로
 * @param bodyBytes Content-Length 로 알릴 본문 크기(실제로는 보내지 않음, 'continue' 가 오면 그때만 보냄)
 * @param auth Authorization 헤더 값(없으면 생략)
 */
async function postOversizedExpectContinue(
  path: string,
  bodyBytes: number,
  auth?: string,
): Promise<{ status: number; body: { error?: string }; bodyWritten: boolean }> {
  const app = createApp();
  const server = http.createServer(app);
  // 100 Continue 를 자동으로 보내지 않고 곧장 앱에 넘긴다 — 서버가 본문을 요구하지 않고 응답할 수 있는지 보기 위함
  server.on('checkContinue', (req, res) => app(req, res));
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;

  try {
    return await new Promise((resolve, reject) => {
      let bodyWritten = false;
      const headers: Record<string, string | number> = {
        'Content-Type': 'application/json',
        'Content-Length': bodyBytes,
        Expect: '100-continue',
      };
      if (auth) headers.Authorization = auth;
      const req = http.request({ host: '127.0.0.1', port, path, method: 'POST', headers });

      // 서버가 본문을 달라고 하면(=퇴행) 그때만 본문을 보낸다 — bodyWritten 으로 단언에서 드러낸다
      req.on('continue', () => {
        bodyWritten = true;
        req.end(jsonOfBytes(bodyBytes, 'html'));
      });
      req.on('response', (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let body: { error?: string } = {};
          try {
            body = JSON.parse(text) as { error?: string };
          } catch {
            body = { error: text };
          }
          // 본문을 보내지 않은 요청이므로 응답을 다 읽은 뒤 소켓을 정리한다
          req.destroy();
          resolve({ status: res.statusCode ?? 0, body, bodyWritten });
        });
        res.on('error', reject);
      });
      // 응답을 받기 전의 연결 오류는 그대로 실패시킨다(응답 resolve 뒤의 destroy 는 영향 없음)
      req.on('error', reject);
      // 퇴행 시 서버가 본문을 기다리며 멈추면 vitest 기본 한도 대신 원인이 보이는 메시지로 실패시킨다
      req.setTimeout(3000, () =>
        req.destroy(
          new Error(
            '본문 없이 응답이 오지 않았습니다 — 파서가 인증보다 먼저 본문을 기다리는 것으로 보입니다',
          ),
        ),
      );
      // 헤더만 먼저 보낸다(본문은 'continue' 를 받을 때만)
      req.flushHeaders();
    });
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

/** 테스트마다 내부 토큰 환경변수를 심고, 4xx/5xx 로그 출력을 막는다 */
function setupEnv(): void {
  let saved: string | undefined;
  beforeEach(() => {
    saved = process.env.INTERNAL_SERVICE_TOKEN;
    process.env.INTERNAL_SERVICE_TOKEN = TOKEN;
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => {
    if (saved === undefined) delete process.env.INTERNAL_SERVICE_TOKEN;
    else process.env.INTERNAL_SERVICE_TOKEN = saved;
    vi.restoreAllMocks();
  });
}

describe('createApp — 인증이 본문 파서보다 먼저 (WD-631)', () => {
  setupEnv();

  it('토큰 없는 html-to-pdf 6MB → 413 이 아니라 401 (파서가 본문을 버퍼링·파싱하지 않는다)', async () => {
    // 본문을 보내지 않고도 401 이 와야 한다 — 서버가 본문을 요구(100 Continue)하지 않았음을 함께 확인 (WD-696)
    const res = await postOversizedExpectContinue('/agent/html-to-pdf', 6 * 1024 * 1024);
    expect(res.status).toBe(401);
    expect(res.body.error).toContain('Unauthorized');
    expect(res.bodyWritten).toBe(false);
  });

  it('토큰 없는 chat 3MB → 413 이 아니라 401', async () => {
    const res = await post('/agent/chat').send(jsonOfBytes(3 * 1024 * 1024));
    expect(res.status).toBe(401);
  });

  it('틀린 토큰 + 과대 본문 → 401', async () => {
    // 틀린 토큰도 본문 없이 401 — 쓰기 중 ECONNRESET 경합을 피한다 (WD-696)
    const res = await postOversizedExpectContinue(
      '/agent/classify',
      3 * 1024 * 1024,
      'Internal wrong-token-value-xx',
    );
    expect(res.status).toBe(401);
    expect(res.bodyWritten).toBe(false);
  });

  it('토큰 있고 delegation 헤더 없는 graph 변경 + 깨진 JSON → 파서보다 먼저 400 delegation 안내', async () => {
    const res = await post('/agent/graph/add-entity', { auth: true }).send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('missing delegation headers');
  });
});

describe('createApp — 토큰 있는 요청의 본문 파싱 오류 (WD-550)', () => {
  setupEnv();

  it('깨진 JSON → 400 한국어 메시지', async () => {
    const res = await post('/agent/chat', { auth: true }).send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body).toEqual(PARSE_FAILED);
  });

  it('top-level null → 400 (strict 모드)', async () => {
    const res = await post('/agent/classify', { auth: true }).send('null');
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('JSON');
  });

  it('top-level 문자열 → 400 (strict 모드)', async () => {
    const res = await post('/agent/chat', { auth: true }).send('"str"');
    expect(res.status).toBe(400);
    expect(res.body).toEqual(PARSE_FAILED);
  });

  it('Content-Type json + 빈 본문 → 파서는 통과시키고 라우트 검증 400 으로 진행', async () => {
    const res = await post('/agent/chat', { auth: true }).send('');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'message or fileIds is required' });
  });

  it('JSON 이 아닌 Content-Type 은 파서가 건드리지 않는다', async () => {
    const res = await post('/agent/chat', { auth: true, headers: { 'Content-Type': 'text/plain' } }).send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'message or fileIds is required' });
  });

  it('chat 1.5MB → 채팅 전용 2MB 파서 통과 → 라우트 검증(userId) 400', async () => {
    const res = await post('/agent/chat', { auth: true }).send(jsonOfBytes(1.5 * 1024 * 1024));
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'userId is required and must be a number' });
  });

  it('chat 정확히 2MB → 한도 안이므로 파서 통과', async () => {
    const res = await post('/agent/chat', { auth: true }).send(jsonOfBytes(2 * 1024 * 1024));
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'userId is required and must be a number' });
  });

  it('chat 2MB 초과 → 413, 메시지에 최대 2MB', async () => {
    const res = await post('/agent/chat', { auth: true }).send(jsonOfBytes(2 * 1024 * 1024 + 1));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 2MB).' });
  });

  it('기본 1MB 라우트(proactive) 1MB 초과 → 413, 메시지에 최대 1MB', async () => {
    const res = await post('/agent/proactive', { auth: true }).send(jsonOfBytes(1024 * 1024 + 1, 'prompt'));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 1MB).' });
  });

  it('기본 1MB 라우트(proactive) 900KB → 파서 통과 → 라우트 검증 400', async () => {
    const res = await post('/agent/proactive', { auth: true }).send(jsonOfBytes(900 * 1024, 'prompt'));
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'prompt and context are required' });
  });

  it('html-to-pdf 2MB → 라우트 5MB 파서 통과 → 라우트 검증 400', async () => {
    const res = await post('/agent/html-to-pdf', { auth: true }).send(jsonOfBytes(2 * 1024 * 1024, 'other'));
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: 'html field is required' });
  });

  it('html-to-pdf 6MB → 413, 메시지에 최대 5MB', async () => {
    const res = await post('/agent/html-to-pdf', { auth: true }).send(jsonOfBytes(6 * 1024 * 1024, 'html'));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 5MB).' });
  });

  it('classify 1.5MB → 라우트 전용 2MB 파서 통과 → 스키마 검증 400', async () => {
    const res = await post('/agent/classify', { auth: true }).send(jsonOfBytes(1.5 * 1024 * 1024));
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Invalid request');
  });

  it('classify 2MB 초과 → 413, 메시지에 최대 2MB', async () => {
    const res = await post('/agent/classify', { auth: true }).send(jsonOfBytes(2.1 * 1024 * 1024));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 2MB).' });
  });
});

/**
 * 전역 JSON 파서가 없으므로 모든 POST 라우트가 각자 파서를 달아야 한다.
 * 알려진 POST 라우트 목록과 실제 앱에 등록된 POST 라우트가 같은지 확인하고(새 라우트가 생기면 이 목록에 추가하게 강제),
 * 각 라우트에 대해 (1) 토큰 없이 깨진 JSON → 401(인증이 파서보다 앞), (2) 토큰(+대행 헤더) 있는 깨진 JSON → 400 한국어
 * (파서 존재)를 확인한다.
 */
describe('createApp — 모든 POST 라우트의 인증·본문 파서 순서 (WD-550, WD-631)', () => {
  setupEnv();

  const KNOWN_POST_ROUTES = [
    '/agent/api-key/verify',
    '/agent/chart-render',
    '/agent/chat',
    '/agent/chat/:sessionId/cancel',
    '/agent/classify',
    '/agent/cli-auth/verify',
    '/agent/graph/add-entity',
    '/agent/graph/add-relation',
    '/agent/graph/merge-entities',
    '/agent/graph/set-property',
    '/agent/html-to-pdf',
    '/agent/proactive',
    '/agent/template-sections/generate',
  ];

  /** Express 4 내부 스택에서 '/agent' 하위 라우터의 POST 라우트 경로를 모은다 */
  function registeredPostRoutes(): string[] {
    type Layer = { name?: string; handle?: { stack?: Layer[] }; route?: { path: string; methods: Record<string, boolean> } };
    const app = createApp() as unknown as { _router: { stack: Layer[] } };
    const paths: string[] = [];
    for (const layer of app._router.stack) {
      if (layer.name !== 'router' || !layer.handle?.stack) continue;
      for (const inner of layer.handle.stack) {
        if (inner.route?.methods.post) paths.push(`/agent${inner.route.path}`);
      }
    }
    return paths.sort();
  }

  it('등록된 POST 라우트가 알려진 목록과 일치한다', () => {
    expect(registeredPostRoutes()).toEqual([...KNOWN_POST_ROUTES].sort());
  });

  it.each(KNOWN_POST_ROUTES)('%s — 토큰 없는 깨진 JSON → 401 (파서보다 인증이 먼저)', async (route) => {
    const res = await post(route.replace(':sessionId', 'sess-1')).send('{"a":');
    expect(res.status).toBe(401);
  });

  it.each(KNOWN_POST_ROUTES)('%s — 토큰 있는 깨진 JSON → 400 한국어', async (route) => {
    // graph 변경 라우트는 requireDelegation 이 파서보다 앞이므로 대행 헤더를 함께 싣는다(다른 라우트는 무시)
    const res = await post(route.replace(':sessionId', 'sess-1'), {
      auth: true,
      headers: { [ON_BEHALF_OF_HEADER]: '1', [ON_BEHALF_OF_TENANT_HEADER]: '1' },
    }).send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body).toEqual(PARSE_FAILED);
  });
});
