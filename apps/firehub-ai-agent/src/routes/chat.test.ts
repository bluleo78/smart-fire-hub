import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import chatRouter from './chat.js';

// Mock ProviderFactory and transcript-reader to avoid actual Claude API calls
const mockExecute = vi.fn();
const mockProvider = { name: 'mock', execute: mockExecute };

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createChatProvider: vi.fn(() => mockProvider),
  },
}));

vi.mock('../agent/transcript-reader.js', () => ({
  readSessionTranscript: vi.fn().mockResolvedValue([]),
}));

// child_process.execFile 모킹 — 실제 claude CLI 호출 방지
const mockExecFile = vi.fn();
vi.mock('child_process', () => ({
  execFile: vi.fn((...args: unknown[]) => {
    // promisify가 기대하는 callback 형태로 래핑
    const callback = args[args.length - 1] as (
      err: Error | null,
      result: { stdout: string; stderr: string },
    ) => void;
    mockExecFile(...args.slice(0, -1));
    // 기본값: 유효한 응답 반환 (개별 테스트에서 mockImplementation으로 재정의)
    callback(null, { stdout: JSON.stringify({ is_error: false }), stderr: '' });
  }),
}));

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', chatRouter);
  return app;
}

// Simple HTTP test helper using Node's built-in http
async function makeRequest(
  app: express.Express,
  method: 'GET' | 'POST',
  path: string,
  body?: Record<string, unknown>,
  headers?: Record<string, string>,
): Promise<{ status: number; body: unknown }> {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error('Failed to get server address'));
        return;
      }
      const port = addr.port;
      const url = `http://localhost:${port}${path}`;
      const options: RequestInit = {
        method,
        headers: {
          'Content-Type': 'application/json',
          ...headers,
        },
      };
      if (body) {
        options.body = JSON.stringify(body);
      }
      fetch(url, options)
        .then(async (res) => {
          const text = await res.text();
          let parsed: unknown;
          try {
            parsed = JSON.parse(text);
          } catch {
            parsed = text;
          }
          server.close();
          resolve({ status: res.status, body: parsed });
        })
        .catch((err) => {
          server.close();
          reject(err);
        });
    });
  });
}

describe('Chat routes — integration tests', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  it('GET /agent/health should return 200 with status ok', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'GET', '/agent/health');

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('status', 'ok');
  });

  it('POST /agent/chat without auth should return 401', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'POST', '/agent/chat', {
      message: 'Hello',
      userId: 1,
    });

    expect(res.status).toBe(401);
  });

  it('POST /agent/chat with auth but missing message should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { userId: 1 },
      {
        Authorization: `Internal ${VALID_TOKEN}`,
      },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('POST /agent/chat with auth but missing userId should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello' },
      {
        Authorization: `Internal ${VALID_TOKEN}`,
      },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  // CR-01: apiKey from request body is forwarded to ProviderFactory
  it('CR-01: passes apiKey from request body to ProviderFactory.createChatProvider', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);

    async function* fakeStream() {
      yield { type: 'done' as const };
    }
    mockExecute.mockReturnValue(fakeStream());

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello', userId: 42, apiKey: 'sk-from-client' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockCreateChatProvider).toHaveBeenCalledOnce();
    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(calledWith.apiKey).toBe('sk-from-client');
  });

  // CR-02: provider.execute() is called with correct message and userId
  it('CR-02: provider.execute() is called with message and userId', async () => {
    async function* fakeStream() {
      yield { type: 'done' as const };
    }
    mockExecute.mockReturnValue(fakeStream());

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello world', userId: 99, apiKey: 'sk-test' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecute).toHaveBeenCalledOnce();
    const calledWith = mockExecute.mock.calls[0][0];
    expect(calledWith.message).toBe('Hello world');
    expect(calledWith.userId).toBe(99);
  });
});

describe('API 키 / CLI OAuth 검증 엔드포인트 — 명령어 인젝션 방어 테스트', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  // SEC-01: /api-key/verify — 셸을 경유하지 않고 claude를 직접 실행하는지 확인
  it('SEC-01: /api-key/verify는 sh -c 없이 claude를 직접 execFile로 호출한다', async () => {
    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/api-key/verify',
      { apiKey: 'sk-ant-test-key' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecFile).toHaveBeenCalledOnce();
    const [cmd, args] = mockExecFile.mock.calls[0] as [string, string[]];
    // 셸('sh') 경유 금지 — 직접 'claude' 실행해야 함
    expect(cmd).toBe('claude');
    expect(args).not.toContain('-c');
    expect(args).not.toContain('sh');
  });

  // SEC-02: /api-key/verify — API 키는 환경변수로 전달되고 args에 포함되지 않음
  it('SEC-02: /api-key/verify는 API 키를 환경변수(ANTHROPIC_API_KEY)로 안전하게 전달한다', async () => {
    const testApiKey = 'sk-ant-injected\'; rm -rf /; echo \'';
    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/api-key/verify',
      { apiKey: testApiKey },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecFile).toHaveBeenCalledOnce();
    const [, args, opts] = mockExecFile.mock.calls[0] as [string, string[], { env?: NodeJS.ProcessEnv }];
    // API 키가 args(명령줄 인수)에 포함되면 안 됨
    expect(args.join(' ')).not.toContain(testApiKey);
    // API 키가 환경변수로 전달되어야 함
    expect(opts?.env?.ANTHROPIC_API_KEY).toBe(testApiKey);
  });

  // SEC-03: /api-key/verify — apiKey 없으면 { valid: false } 반환
  it('SEC-03: /api-key/verify는 apiKey가 없으면 { valid: false }를 반환한다', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/api-key/verify',
      {},
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ valid: false });
    // execFile이 호출되지 않아야 함
    expect(mockExecFile).not.toHaveBeenCalled();
  });

  // SEC-04: /cli-auth/verify — 셸을 경유하지 않고 claude를 직접 실행하는지 확인
  it('SEC-04: /cli-auth/verify는 sh -c 없이 claude를 직접 execFile로 호출한다', async () => {
    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/cli-auth/verify',
      { token: 'oauth-test-token' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecFile).toHaveBeenCalledOnce();
    const [cmd, args] = mockExecFile.mock.calls[0] as [string, string[]];
    expect(cmd).toBe('claude');
    expect(args).not.toContain('-c');
    expect(args).not.toContain('sh');
  });

  // SEC-05: /cli-auth/verify — OAuth 토큰은 환경변수로 전달되고 args에 포함되지 않음
  it('SEC-05: /cli-auth/verify는 OAuth 토큰을 환경변수(CLAUDE_CODE_OAUTH_TOKEN)로 안전하게 전달한다', async () => {
    const testToken = "oauth'; DROP TABLE users; --";
    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/cli-auth/verify',
      { token: testToken },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecFile).toHaveBeenCalledOnce();
    const [, args, opts] = mockExecFile.mock.calls[0] as [string, string[], { env?: NodeJS.ProcessEnv }];
    expect(args.join(' ')).not.toContain(testToken);
    expect(opts?.env?.CLAUDE_CODE_OAUTH_TOKEN).toBe(testToken);
  });

  // SEC-06: /cli-auth/verify — token 없으면 { valid: false } 반환
  it('SEC-06: /cli-auth/verify는 token이 없으면 { valid: false }를 반환한다', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/cli-auth/verify',
      {},
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ valid: false });
    expect(mockExecFile).not.toHaveBeenCalled();
  });

  // SEC-07: /api-key/verify — claude CLI 오류 시 { valid: false } 반환
  it('SEC-07: /api-key/verify는 claude CLI 오류 시 { valid: false }를 반환한다', async () => {
    const { execFile } = await import('child_process');
    const mockExecFileFn = vi.mocked(execFile);
    mockExecFileFn.mockImplementationOnce((...args: unknown[]) => {
      const callback = args[args.length - 1] as (err: Error) => void;
      callback(new Error('claude not found'));
      return {} as ReturnType<typeof execFile>;
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/api-key/verify',
      { apiKey: 'sk-invalid' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ valid: false });
  });
});
