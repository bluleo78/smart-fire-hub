/**
 * 앱 조립(createApp) 수준의 본문 파싱 오류 처리 테스트 (WD-550)
 *
 * 전역 JSON 파서·라우트 전용 파서·최종 에러 핸들러가 실제 마운트 순서대로 맞물렸을 때
 * 깨진 JSON / 한도 초과 본문이 500 이 아니라 400/413(한국어 메시지)으로 나가는지 확인한다.
 * 라우터 내부 로직까지 가지 않도록 토큰 없는 요청(→ internalAuth 401)을 "파서 통과"의 증거로 쓴다.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import request from 'supertest';
import { createApp } from './app.js';

/** 지정한 바이트 수(정확히)의 JSON 본문을 만든다 — `{"message":"xxx…"}` 형태 */
function jsonOfBytes(totalBytes: number, field = 'message'): string {
  const overhead = Buffer.byteLength(`{"${field}":""}`);
  return `{"${field}":"${'a'.repeat(totalBytes - overhead)}"}`;
}

describe('createApp — 본문 파싱 오류 (WD-550)', () => {
  beforeEach(() => {
    // 4xx 로그는 console.warn 으로 나가므로 테스트 출력이 지저분해지지 않게 막는다
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('깨진 JSON → 400 한국어 메시지', async () => {
    const res = await request(createApp())
      .post('/agent/chat')
      .set('Content-Type', 'application/json')
      .send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: '요청 본문을 해석할 수 없습니다 — 올바른 JSON 이어야 합니다.' });
  });

  it('top-level null → 400 (strict 모드)', async () => {
    const res = await request(createApp())
      .post('/agent/classify')
      .set('Content-Type', 'application/json')
      .send('null');
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('JSON');
  });

  it('top-level 문자열 → 400 (strict 모드)', async () => {
    const res = await request(createApp())
      .post('/agent/chat')
      .set('Content-Type', 'application/json')
      .send('"str"');
    expect(res.status).toBe(400);
  });

  it('Content-Type json + 빈 본문 → 파서는 통과시키고 라우트(인증)로 진행', async () => {
    const res = await request(createApp()).post('/agent/chat').set('Content-Type', 'application/json').send('');
    expect(res.status).toBe(401);
  });

  it('JSON 이 아닌 Content-Type 은 파서가 건드리지 않는다', async () => {
    const res = await request(createApp()).post('/agent/chat').set('Content-Type', 'text/plain').send('{"a":');
    expect(res.status).toBe(401);
  });

  it('1MB 초과 JSON → 413, 메시지에 최대 1MB', async () => {
    const res = await request(createApp())
      .post('/agent/chat')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(1024 * 1024 + 1));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 1MB).' });
  });

  it('정확히 1MB(1,048,576 바이트) JSON → 한도 안이므로 파서 통과', async () => {
    const res = await request(createApp())
      .post('/agent/chat')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(1024 * 1024));
    expect(res.status).toBe(401);
  });

  it('900KB JSON → 파서 통과(기존 100kb 기본 한도에 걸리지 않음)', async () => {
    const res = await request(createApp())
      .post('/agent/chat')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(900 * 1024));
    expect(res.status).toBe(401);
  });

  it('html-to-pdf 2MB → 전역 1MB 파서를 건너뛰고 라우트 5MB 파서 통과 → 인증 401', async () => {
    const res = await request(createApp())
      .post('/agent/html-to-pdf')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(2 * 1024 * 1024, 'html'));
    expect(res.status).toBe(401);
  });

  it('html-to-pdf 6MB(토큰 없음) → 413, 메시지에 최대 5MB — 본문 파서가 인증보다 먼저 돈다', async () => {
    const res = await request(createApp())
      .post('/agent/html-to-pdf')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(6 * 1024 * 1024, 'html'));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 5MB).' });
  });

  it('classify 1.5MB → 라우트 전용 2MB 파서가 적용돼 통과 → 인증 401', async () => {
    const res = await request(createApp())
      .post('/agent/classify')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(1.5 * 1024 * 1024));
    expect(res.status).toBe(401);
  });

  it('classify 2MB 초과 → 413, 메시지에 최대 2MB', async () => {
    const res = await request(createApp())
      .post('/agent/classify')
      .set('Content-Type', 'application/json')
      .send(jsonOfBytes(2.1 * 1024 * 1024));
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: '요청 본문이 너무 큽니다(최대 2MB).' });
  });
});

/**
 * 전역 JSON 파서가 없으므로 모든 POST 라우트가 각자 파서를 달아야 한다.
 * 알려진 POST 라우트 목록과 실제 앱에 등록된 POST 라우트가 같은지 확인하고(새 라우트가 생기면 이 목록에 추가하게 강제),
 * 각 라우트에 깨진 JSON 을 보내 400 한국어가 나오는지로 파서 존재를 증명한다.
 */
describe('createApp — 모든 POST 라우트의 본문 파서 (WD-550)', () => {
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

  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('등록된 POST 라우트가 알려진 목록과 일치한다', () => {
    expect(registeredPostRoutes()).toEqual([...KNOWN_POST_ROUTES].sort());
  });

  it.each(KNOWN_POST_ROUTES)('%s — 깨진 JSON → 400 한국어', async (route) => {
    const res = await request(createApp())
      .post(route.replace(':sessionId', 'sess-1'))
      .set('Content-Type', 'application/json')
      .send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: '요청 본문을 해석할 수 없습니다 — 올바른 JSON 이어야 합니다.' });
  });
});
