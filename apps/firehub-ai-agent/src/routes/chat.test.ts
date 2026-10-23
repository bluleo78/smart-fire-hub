import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import chatRouter from './chat.js';
import { AGENT_RUN_SETTING_SUPPORT } from '../providers/types.js';

// Mock ProviderFactory and transcript-reader to avoid actual Claude API calls
const mockExecute = vi.fn();
const mockProvider = { name: 'mock', execute: mockExecute };

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createChatProvider: vi.fn(() => mockProvider),
  },
}));

const mockReadTranscript = vi.fn();
vi.mock('../agent/transcript-reader.js', () => ({
  readSessionTranscript: (...args: unknown[]) => mockReadTranscript(...args),
}));

// 세션 귀속 표식은 파일시스템을 읽으므로 목으로 막고 라우트 게이트만 검증한다.
// 표식 자체의 3상태 판정은 session-owner.test.ts 가 실제 파일로 커버한다.
const mockCheckOwnership = vi.fn();
vi.mock('../agent/session-owner.js', () => ({
  checkSessionOwnership: (...args: unknown[]) => mockCheckOwnership(...args),
}));

// WD-57: 세션 가드 대기 시간을 줄여 409 경로 테스트가 실제 5초를 기다리지 않게 한다(레지스트리 로직은 그대로).
vi.mock('../agent/session-runs.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../agent/session-runs.js')>()),
  SESSION_BUSY_WAIT_MS: 50,
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
    // 기본값: 표식 없음 = 레거시 세션 → 통과, 트랜스크립트는 빈 배열.
    mockCheckOwnership.mockResolvedValue('unknown');
    mockReadTranscript.mockResolvedValue([]);
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
      { message: 'Hello', tenantId: 1, userId: 42, apiKey: 'sk-from-client', agentType: 'sdk' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockCreateChatProvider).toHaveBeenCalledOnce();
    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(calledWith.apiKey).toBe('sk-from-client');
  });

  // CR-CRED (#708): 자격증명 없음은 관리자 조치 오류라 고정 문구로 뭉개지 않고 한국어 안내를 그대로 보여 준다.
  it('CR-CRED: createChatProvider 의 MissingAiCredentialError 안내가 채팅 응답에 그대로 실린다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const { MissingAiCredentialError } = await import('../agent/ai-auth-failure.js');
    vi.mocked(ProviderFactory.createChatProvider).mockImplementationOnce(() => {
      throw new MissingAiCredentialError();
    });

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/chat',
      { message: 'Hello', tenantId: 1, userId: 42, agentType: 'sdk' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(JSON.stringify(res.body)).toContain('설정 › AI 에이전트');
    expect(JSON.stringify(res.body)).not.toContain('Agent 처리 중 오류가 발생했습니다');
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
      { message: 'Hello world', tenantId: 1, userId: 99, apiKey: 'sk-test', agentType: 'sdk' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecute).toHaveBeenCalledOnce();
    const calledWith = mockExecute.mock.calls[0][0];
    expect(calledWith.message).toBe('Hello world');
    expect(calledWith.userId).toBe(99);
    // 테넌트가 provider 까지 흘러야 디스크 경로가 테넌트별로 갈린다.
    expect(calledWith.tenantId).toBe(1);
  });

  // WD-59: 공급자가 실제로 반영하지 못하는 실행 설정은 넘기지 않는다 — 지원 여부는 AGENT_RUN_SETTING_SUPPORT 가 단일 출처다.
  describe('AI 실행 설정 전달(WD-59)', () => {
    const run = async (agentType: string) => {
      async function* fakeStream() {
        yield { type: 'done' as const };
      }
      mockExecute.mockReturnValue(fakeStream());
      await makeRequest(
        createApp(),
        'POST',
        '/agent/chat',
        { message: 'hi', tenantId: 1, userId: 1, apiKey: 'sk', agentType, maxTurns: 4, temperature: 0.3, maxTokens: 2048 },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
      return mockExecute.mock.calls[0][0] as { maxTurns?: number; temperature?: number; maxTokens?: number };
    };

    it('opencode 는 최대 턴 수·temperature·최대 응답 토큰을 모두 넘긴다', async () => {
      expect(await run('opencode')).toMatchObject({ maxTurns: 4, temperature: 0.3, maxTokens: 2048 });
    });

    it.each(['sdk', 'cli', 'cli-api'])('%s 는 최대 턴 수만 넘기고 temperature·최대 응답 토큰은 넘기지 않는다', async (agentType) => {
      const opts = await run(agentType);
      expect(opts.maxTurns).toBe(4);
      expect(opts.temperature).toBeUndefined();
      expect(opts.maxTokens).toBeUndefined();
    });

    it('지원 매트릭스 — web(firehub-web src/lib/ai-credential.ts)의 AI_BEHAVIOR_SETTING_SUPPORT 와 같은 사실이어야 한다', () => {
      expect(AGENT_RUN_SETTING_SUPPORT).toEqual({
        sdk: { maxTurns: true, temperature: false, maxTokens: false },
        cli: { maxTurns: true, temperature: false, maxTokens: false },
        'cli-api': { maxTurns: true, temperature: false, maxTokens: false },
        opencode: { maxTurns: true, temperature: true, maxTokens: true },
      });
    });
  });

  // S3: api(AiChatRequestBuilder)가 Slack 인바운드 요청에 실은 aiPurpose 'share' 를 실행 옵션으로 넘겨 MCP 호출이
  // X-AI-Purpose: share 를 싣게 한다(옵션→헤더 전달은 agent-sdk/agent-cli/agent-opencode·api-client·stdio-server 테스트 몫).
  describe('aiPurpose 전달(S3, Slack 발송 = SHARE)', () => {
    const run = async (extra: Record<string, unknown>) => {
      async function* fakeStream() {
        yield { type: 'done' as const };
      }
      mockExecute.mockReturnValue(fakeStream());
      await makeRequest(
        createApp(),
        'POST',
        '/agent/chat',
        { message: 'hi', tenantId: 1, userId: 1, apiKey: 'sk-test', agentType: 'sdk', ...extra },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
      return mockExecute.mock.calls[0][0] as { aiPurpose?: string };
    };

    it("바디의 aiPurpose 'share' 를 provider.execute 로 넘긴다", async () => {
      expect((await run({ aiPurpose: 'share' })).aiPurpose).toBe('share');
    });

    it('aiPurpose 가 없으면 넘기지 않는다(웹 채팅 — AI 판정만)', async () => {
      expect((await run({})).aiPurpose).toBeUndefined();
    });

    // WD-79: 테넌트 DB 방언 — 'TIBERO' 만 TIBERO, 나머지는 POSTGRESQL.
    it("바디의 dbDialect 'TIBERO' 를 provider.execute 의 dbDialect 로 넘긴다", async () => {
      const opts = (await run({ dbDialect: 'TIBERO' })) as { dbDialect?: string };
      expect(opts.dbDialect).toBe('TIBERO');
    });

    it("dbDialect 가 없거나 모르는 값이면 'POSTGRESQL'", async () => {
      expect(((await run({})) as { dbDialect?: string }).dbDialect).toBe('POSTGRESQL');
      mockExecute.mockClear();
      expect(((await run({ dbDialect: 'tibero' })) as { dbDialect?: string }).dbDialect).toBe('POSTGRESQL');
    });

    it("'none' 등 다른 값은 버린다 — LLM 실행에서 AI 판정을 끄면 안 된다", async () => {
      expect((await run({ aiPurpose: 'none' })).aiPurpose).toBeUndefined();
      mockExecute.mockClear();
      expect((await run({ aiPurpose: 'SHARE' })).aiPurpose).toBeUndefined();
    });
  });

  // CR-AT01 (Task 8): agentType 이 없으면 400 — 조용히 'sdk' 로 취급하지 않는다.
  // 6b1c6383 과금 회귀가 opencode 자격증명이 Claude SDK 경로로 새며 일어났으므로, agentType
  // 판별자 자체가 누락된 요청은 방어적으로 이 경계에서 걸러야 한다.
  it('CR-AT01: agentType 이 없으면 400 이고 provider 를 호출하지 않는다', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello', tenantId: 1, userId: 1, apiKey: 'sk-test' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(400);
    expect(mockExecute).not.toHaveBeenCalled();
  });

  it('CR-AT02: agentType 이 알려진 네 값 중 하나가 아니면 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello', tenantId: 1, userId: 1, apiKey: 'sk-test', agentType: 'not-a-real-type' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(400);
    expect(mockExecute).not.toHaveBeenCalled();
  });

  // CR-OC01 (Task 8): opencode 전용 필드(baseUrl/providerId/reasoningEffort)가 라우트를 거치며
  // 사라지지 않고 ProviderConfig 까지 도달하는지 검증한다(Ruling #2).
  it('CR-OC01: baseUrl/providerId/reasoningEffort 를 ProviderFactory.createChatProvider 로 그대로 전달한다', async () => {
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
      {
        message: 'Hello',
        tenantId: 1,
        userId: 42,
        apiKey: 'openai-key',
        agentType: 'opencode',
        baseUrl: 'https://x/v1',
        providerId: 'openai',
        reasoningEffort: 'medium',
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(calledWith.baseUrl).toBe('https://x/v1');
    expect(calledWith.providerId).toBe('openai');
    expect(calledWith.reasoningEffort).toBe('medium');
  });

  // CR-T01: 테넌트가 없으면 400 — ai-agent 는 이 값으로 디스크 경로를 가르므로 전역 경로
  // 폴백을 두지 않는다(fail-closed). provider 는 호출되지 않아야 한다.
  it('CR-T01: POST /agent/chat without tenantId returns 400 and never calls the provider', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/chat',
      { message: 'Hello', userId: 1 },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(400);
    expect(mockExecute).not.toHaveBeenCalled();
  });

  // CR-T01b: 정수 양수가 아닌 테넌트도 400 이어야 한다(코드리뷰 지적). 느슨한 가드로 통과시키면
  // SSE 헤더가 이미 나간 뒤 tenantSegment 에서 터져 클라이언트는 400 대신 error 이벤트를 본다.
  it.each([-1, 1.5, 0, Number.POSITIVE_INFINITY])(
    'CR-T01b: POST /agent/chat rejects tenantId=%s with 400',
    async (tenantId) => {
      const app = createApp();
      const res = await makeRequest(
        app,
        'POST',
        '/agent/chat',
        { message: 'Hello', tenantId, userId: 1 },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );

      expect(res.status).toBe(400);
      expect(mockExecute).not.toHaveBeenCalled();
    },
  );

  // CR-T02: history 도 테넌트가 필수다 — 어느 테넌트 디렉터리를 읽을지 정해지지 않는다.
  it('CR-T02: GET /agent/history without tenantId returns 400', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'GET', '/agent/history/sess-1', undefined, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(400);
    expect(mockReadTranscript).not.toHaveBeenCalled();
  });

  // CR-T03: 표식이 다른 테넌트를 가리키면 404. 존재를 알려 주지 않기 위해 403 이 아니다.
  it('CR-T03: GET /agent/history returns 404 when the session belongs to another tenant', async () => {
    mockCheckOwnership.mockResolvedValue('other-tenant');
    const app = createApp();
    const res = await makeRequest(app, 'GET', '/agent/history/sess-1?tenantId=5', undefined, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(404);
    // 거부했다면 트랜스크립트를 읽지 않았어야 한다 — 읽고 나서 버리면 게이트가 아니다.
    expect(mockReadTranscript).not.toHaveBeenCalled();
  });

  // CR-T04: 표식이 없는 레거시 세션은 통과시킨다. 여기서 막으면 세그먼트 도입 전 이력이
  // 전부 안 보이는 기능 회귀가 된다(1차 게이트는 firehub-api 의 소유권 검증이다).
  it('CR-T04: GET /agent/history passes through when no ownership marker exists', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'GET', '/agent/history/legacy-sess?tenantId=5', undefined, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(200);
    expect(mockReadTranscript).toHaveBeenCalledWith(5, 'legacy-sess');
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
    delete process.env.ANTHROPIC_API_KEY;
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

  // SEC-05b: /cli-auth/verify — 컨테이너에 ambient ANTHROPIC_API_KEY 가 있어도 spawn 환경에
  // 넘기지 않는다. agent-cli.ts:509 주석대로 claude CLI 는 API 키가 있으면 OAuth 토큰보다
  // 그 키를 우선한다 — 지우지 않으면 아무 토큰이나 valid:true 로 검증되고 그 호출은 컨테이너의
  // ambient 키(플랫폼 계정)로 과금된다(전체 브랜치 리뷰 I4, Ruling #33 번복).
  // "in" 연산자로 **키 자체의 부재**를 확인한다 — toBeUndefined()/falsy 검사는 빈 문자열
  // 대입으로도 통과하는 공허한 테스트가 된다(이 브랜치에서 반복된 실수).
  it('SEC-05b: /cli-auth/verify는 ambient ANTHROPIC_API_KEY를 spawn 환경에서 제거한다', async () => {
    process.env.ANTHROPIC_API_KEY = 'ambient-platform-key-should-not-leak';
    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/cli-auth/verify',
      { token: 'oauth-test-token' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockExecFile).toHaveBeenCalledOnce();
    const [, , opts] = mockExecFile.mock.calls[0] as [string, string[], { env?: NodeJS.ProcessEnv }];
    expect('ANTHROPIC_API_KEY' in (opts?.env ?? {})).toBe(false);
    expect(opts?.env?.CLAUDE_CODE_OAUTH_TOKEN).toBe('oauth-test-token');
  });

  // SEC-08 (#708/#711): 두 검증 라우트 모두 ambient 인증 경로(다른 자격증명·Bearer 토큰·BASE_URL·
  // Bedrock 전환·AWS 체인)를 자식 env 에서 걷어내고, 재시도를 0 으로 둔다 — 기본 10회 재시도는 401 도
  // 재시도해 30초 타임아웃까지 매달렸다. "in" 연산자로 키 자체의 부재를 확인한다.
  const AMBIENT_FOR_VERIFY: Record<string, string> = {
    ANTHROPIC_API_KEY: 'ambient-key',
    CLAUDE_CODE_OAUTH_TOKEN: 'ambient-oauth',
    ANTHROPIC_AUTH_TOKEN: 'ambient-bearer',
    ANTHROPIC_BASE_URL: 'https://ambient.example',
    CLAUDE_CODE_USE_BEDROCK: '1',
    AWS_ACCESS_KEY_ID: 'AKIA-ambient',
    CLAUDE_CODE_MAX_RETRIES: '10',
  };
  async function withVerifyAmbient(fn: () => Promise<void>) {
    const saved: Record<string, string | undefined> = {};
    for (const [k, v] of Object.entries(AMBIENT_FOR_VERIFY)) {
      saved[k] = process.env[k];
      process.env[k] = v;
    }
    try {
      await fn();
    } finally {
      for (const [k, v] of Object.entries(saved)) {
        if (v === undefined) delete process.env[k];
        else process.env[k] = v;
      }
    }
  }

  it('SEC-08a: /api-key/verify는 ambient 인증 경로를 걷어내고 CLAUDE_CODE_MAX_RETRIES=0 으로 실행한다', async () => {
    await withVerifyAmbient(async () => {
      await makeRequest(
        createApp(),
        'POST',
        '/agent/api-key/verify',
        { apiKey: 'sk-request' },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
    });
    const [, , opts] = mockExecFile.mock.calls[0] as [string, string[], { env?: NodeJS.ProcessEnv }];
    const env = opts?.env ?? {};
    expect(env.ANTHROPIC_API_KEY).toBe('sk-request');
    for (const name of ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_BASE_URL', 'CLAUDE_CODE_USE_BEDROCK', 'AWS_ACCESS_KEY_ID']) {
      expect(name in env, name).toBe(false);
    }
    expect(env.CLAUDE_CODE_MAX_RETRIES).toBe('0');
  });

  it('SEC-08b: /cli-auth/verify는 ambient 인증 경로를 걷어내고 CLAUDE_CODE_MAX_RETRIES=0 으로 실행한다', async () => {
    await withVerifyAmbient(async () => {
      await makeRequest(
        createApp(),
        'POST',
        '/agent/cli-auth/verify',
        { token: 'oat-request' },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
    });
    const [, , opts] = mockExecFile.mock.calls[0] as [string, string[], { env?: NodeJS.ProcessEnv }];
    const env = opts?.env ?? {};
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBe('oat-request');
    for (const name of ['ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_BASE_URL', 'CLAUDE_CODE_USE_BEDROCK', 'AWS_ACCESS_KEY_ID']) {
      expect(name in env, name).toBe(false);
    }
    expect(env.CLAUDE_CODE_MAX_RETRIES).toBe('0');
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

/**
 * WD-57 — 응답 중단 시 서버 실행 취소 + 같은 세션 동시 실행 가드.
 *
 * <p>여러 요청이 겹치는 상황을 재현해야 하므로 서버를 하나 띄워 두고 fetch 를 동시에 보낸다(makeRequest 는
 * 요청마다 서버를 새로 띄우고 본문 끝까지 기다려 겹침을 만들 수 없다).
 */
describe('채팅 취소·세션 가드 (WD-57)', () => {
  const VALID_TOKEN = 'test-internal-token';
  const auth = { Authorization: `Internal ${VALID_TOKEN}`, 'Content-Type': 'application/json' };
  let server: ReturnType<express.Express['listen']>;
  let base = '';

  beforeEach(async () => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
    mockCheckOwnership.mockResolvedValue('unknown');
    await new Promise<void>((resolve) => {
      server = createApp().listen(0, () => resolve());
    });
    const addr = server.address() as { port: number };
    base = `http://localhost:${addr.port}/agent`;
  });

  afterEach(async () => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  /** abort 될 때까지 멈춰 있는 실행 — 공급자가 abortSignal 로 자식을 kill 하는 상황을 흉내낸다. */
  function blockingRun(initSessionId: string) {
    return (opts: { abortSignal?: AbortSignal }) =>
      (async function* () {
        yield { type: 'init' as const, sessionId: initSessionId };
        yield { type: 'text' as const, content: '부분 응답' };
        await new Promise<void>((resolve) => opts.abortSignal?.addEventListener('abort', () => resolve()));
        // 공급자는 abort 뒤에도 정리(트랜스크립트 저장)를 마치고 종결 이벤트를 낼 수 있다
        yield { type: 'error' as const, message: 'terminated' };
      })();
  }

  function chat(body: Record<string, unknown>) {
    return fetch(`${base}/chat`, {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ message: 'hi', tenantId: 1, userId: 42, agentType: 'opencode', ...body }),
    });
  }

  function cancel(sessionId: string, body: Record<string, unknown> = { tenantId: 1, userId: 42 }) {
    return fetch(`${base}/chat/${sessionId}/cancel`, { method: 'POST', headers: auth, body: JSON.stringify(body) });
  }

  /** 첫 실행이 시작돼 init 을 내보낼 때까지 기다린다. */
  async function waitForExecute(times = 1) {
    await vi.waitFor(() => expect(mockExecute).toHaveBeenCalledTimes(times));
  }

  it('WD57-01: 진행 중 실행을 취소하면 공급자에 넘긴 abortSignal 이 abort 되고 202', async () => {
    mockExecute.mockImplementation(blockingRun('sess-a'));
    const first = chat({ sessionId: 'sess-a' });
    await waitForExecute();

    const res = await cancel('sess-a');
    expect(res.status).toBe(202);
    const signal = mockExecute.mock.calls[0][0].abortSignal as AbortSignal;
    expect(signal.aborted).toBe(true);

    // 취소된 실행의 응답은 끝까지 정리되고 닫힌다 — 취소 뒤 종결 이벤트는 중계하지 않는다
    const text = await (await first).text();
    expect(text).toContain('부분 응답');
    expect(text).not.toContain('terminated');
  });

  it('WD57-02: 다른 사용자·다른 테넌트의 취소는 404 이고 실행을 건드리지 않는다', async () => {
    mockExecute.mockImplementation(blockingRun('sess-b'));
    const first = chat({ sessionId: 'sess-b' });
    await waitForExecute();

    expect((await cancel('sess-b', { tenantId: 1, userId: 7 })).status).toBe(404);
    expect((await cancel('sess-b', { tenantId: 2, userId: 42 })).status).toBe(404);
    const signal = mockExecute.mock.calls[0][0].abortSignal as AbortSignal;
    expect(signal.aborted).toBe(false);

    await cancel('sess-b');
    await (await first).text();
  });

  it('WD57-03: 진행 중 실행이 없으면 취소는 404, 바디가 잘못되면 400, 내부 토큰 없으면 401', async () => {
    expect((await cancel('nothing-running')).status).toBe(404);
    expect((await cancel('sess-x', { userId: 42 })).status).toBe(400);
    const noAuth = await fetch(`${base}/chat/sess-x/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantId: 1, userId: 42 }),
    });
    expect(noAuth.status).toBe(401);
  });

  it('WD57-04: 같은 세션에 겹친 요청은 대기 후에도 이전 실행이 남아 있으면 SSE 없이 409 JSON', async () => {
    mockExecute.mockImplementation(blockingRun('sess-c'));
    const first = chat({ sessionId: 'sess-c' });
    await waitForExecute();

    const second = await chat({ sessionId: 'sess-c' });
    expect(second.status).toBe(409);
    expect(second.headers.get('content-type')).toContain('application/json');
    expect(await second.json()).toMatchObject({ code: 'session_busy' });
    // 겹친 요청은 공급자를 아예 부르지 않는다
    expect(mockExecute).toHaveBeenCalledTimes(1);

    await cancel('sess-c');
    await (await first).text();
  });

  it('WD57-05: 이전 실행이 대기 시간 안에 끝나면(취소 직후 전송) 409 없이 이어서 처리된다', async () => {
    mockExecute.mockImplementationOnce(blockingRun('sess-d')).mockImplementationOnce(async function* () {
      yield { type: 'init' as const, sessionId: 'sess-d' };
      yield { type: 'done' as const };
    });
    const first = chat({ sessionId: 'sess-d' });
    await waitForExecute();

    // 이슈 재현 순서: 중단(취소)과 다음 메시지가 거의 동시에 도착
    const secondPromise = chat({ sessionId: 'sess-d', message: 'A' });
    await cancel('sess-d');
    const second = await secondPromise;
    expect(second.status).toBe(200);
    expect(await second.text()).toContain('"type":"done"');
    await (await first).text();
  });

  it('WD57-06: 새 세션(sessionId 없음)은 가드를 거치지 않고, init 의 id 로 등록돼 첫 턴도 취소할 수 있다', async () => {
    mockExecute.mockImplementation(blockingRun('oc-new-1'));
    const first = chat({});
    await waitForExecute();

    // init 으로 받은 id 로 이어 보내면 가드에 걸린다(첫 턴이 아직 진행 중)
    const overlapped = await chat({ sessionId: 'oc-new-1' });
    expect(overlapped.status).toBe(409);

    expect((await cancel('oc-new-1')).status).toBe(202);
    await (await first).text();
  });

  it('WD57-07: 실행이 정상 종료·예외로 끝나도 슬롯을 놓아 다음 요청이 바로 들어간다', async () => {
    mockExecute
      .mockImplementationOnce(async function* () {
        yield { type: 'init' as const, sessionId: 'sess-e' };
        yield { type: 'done' as const };
      })
      .mockImplementationOnce(async function* () {
        yield { type: 'init' as const, sessionId: 'sess-e' };
        throw new Error('boom');
      })
      .mockImplementationOnce(async function* () {
        yield { type: 'done' as const };
      });

    expect((await chat({ sessionId: 'sess-e' })).status).toBe(200);
    expect(await (await chat({ sessionId: 'sess-e' })).text()).toContain('Agent 처리 중 오류가 발생했습니다');
    const third = await chat({ sessionId: 'sess-e' });
    expect(third.status).toBe(200);
    await third.text();
    expect(mockExecute).toHaveBeenCalledTimes(3);
  });

  it('WD57-08: 공급자 생성이 실패해도 슬롯을 놓는다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    vi.mocked(ProviderFactory.createChatProvider).mockImplementationOnce(() => {
      throw new Error('no provider');
    });
    await (await chat({ sessionId: 'sess-f' })).text();

    mockExecute.mockImplementationOnce(async function* () {
      yield { type: 'done' as const };
    });
    const next = await chat({ sessionId: 'sess-f' });
    expect(next.status).toBe(200);
    await next.text();
  });
});
