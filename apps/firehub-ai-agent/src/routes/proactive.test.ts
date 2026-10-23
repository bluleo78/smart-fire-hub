import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { TIBERO_SQL_GUIDE } from '../agent/tibero-sql-guide.js';
import { findPgOnlySyntax, hasTiberoCoreRules } from '../agent/tibero-prompt-lint.fixture.js';
import proactiveRouter, {
  buildSectionPrompt,
  parseSections,
  detectAgentFailure,
  buildProactiveSystemPrompt,
} from './proactive.js';

const mockExecute = vi.hoisted(() => vi.fn());

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createChatProvider: vi.fn(() => ({
      execute: mockExecute,
    })),
  },
}));

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', proactiveRouter);
  return app;
}

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

describe('Proactive routes — integration tests', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    process.env.ANTHROPIC_API_KEY = 'test-api-key';
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
    delete process.env.ANTHROPIC_API_KEY;
  });

  it('TC1: POST /agent/proactive without auth should return 401', async () => {
    const app = createApp();
    const res = await makeRequest(app, 'POST', '/agent/proactive', {
      prompt: 'Analyze this',
      tenantId: 1,
      context: { data: 'test' },
    });

    expect(res.status).toBe(401);
  });

  it('TC2: POST /agent/proactive without prompt should return 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { context: { data: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  // TC2b: 테넌트가 없거나 정수 양수가 아니면 400 (코드리뷰 지적). 느슨한 가드면 -1·1.5 가
  // 통과해 tenantSegment 에서 터지고 400 대신 500 이 나간다.
  it.each([undefined, -1, 1.5, 0])(
    'TC2b: POST /agent/proactive rejects tenantId=%s with 400',
    async (tenantId) => {
      const app = createApp();
      const res = await makeRequest(
        app,
        'POST',
        '/agent/proactive',
        { prompt: '분석', context: { data: 'test' }, tenantId },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );

      expect(res.status).toBe(400);
    },
  );

  // TC-AT (Task 8): agentType 이 없거나 알려진 값이 아니면 400 — 조용히 'sdk' 로 취급하지 않는다.
  it('TC-AT01: agentType 이 없으면 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1 },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
  });

  it('TC-AT02: agentType 이 알려진 네 값 중 하나가 아니면 400', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1, agentType: 'not-a-real-type' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
  });

  // TC-OC (Ruling #17): opencode 의 두 결함 — ambient apiKey 폴백, opencode 필드 누락.
  it('TC-OC01: opencode 이고 apiKey 가 없으면 ambient ANTHROPIC_API_KEY 를 쓰지 않는다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '분석',
        context: { data: 'test' },
        tenantId: 1,
        agentType: 'opencode',
        baseUrl: 'https://x/v1',
        providerId: 'openai',
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    // beforeEach 가 process.env.ANTHROPIC_API_KEY = 'test-api-key' 를 심어 둔다 — opencode 는
    // 그 값을 apiKey 로 물려받으면 안 된다(그 키는 Anthropic 용이지 OpenAI 호환 호스트용이 아니다).
    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(calledWith.apiKey).toBeUndefined();
  });

  it('TC-OC02: baseUrl/providerId/reasoningEffort 를 ProviderConfig 로 그대로 전달한다 (opencode)', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '분석',
        context: { data: 'test' },
        tenantId: 1,
        agentType: 'opencode',
        apiKey: 'openai-key',
        baseUrl: 'https://x/v1',
        providerId: 'openai',
        reasoningEffort: 'medium',
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(calledWith.apiKey).toBe('openai-key');
    expect(calledWith.baseUrl).toBe('https://x/v1');
    expect(calledWith.providerId).toBe('openai');
    expect(calledWith.reasoningEffort).toBe('medium');
  });

  // TC-SDK01 (#708): ambient ANTHROPIC_API_KEY(beforeEach 가 'test-api-key' 로 설정)가 있어도 요청에
  // apiKey 가 없으면 빈 값 그대로 팩토리에 넘긴다 — 팩토리가 "자격증명 없음"으로 크게 실패해야 한다.
  it('TC-SDK01: sdk 는 apiKey 가 없으면 ambient ANTHROPIC_API_KEY 로 메우지 않는다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1, agentType: 'sdk' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(process.env.ANTHROPIC_API_KEY).toBe('test-api-key'); // ambient 값이 실제로 존재하는 상황
    expect(calledWith.apiKey).toBeUndefined();
  });

  // TC-BOUNDARY01 (#708): 예전엔 sdk/cli-api 만 ambient 폴백을 유지했다(Ruling #31). 이제 모든 유형이
  // 요청 자격증명만 쓴다 — cli-api 도 ambient 로 메우지 않는다는 것을 고정한다.
  it('TC-BOUNDARY01: cli-api 도 apiKey 가 없으면 ambient ANTHROPIC_API_KEY 로 메우지 않는다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1, agentType: 'cli-api' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    const calledWith = mockCreateChatProvider.mock.calls[0][0];
    expect(process.env.ANTHROPIC_API_KEY).toBe('test-api-key'); // ambient 값이 실제로 존재하는 상황
    expect(calledWith.apiKey).toBeUndefined();
  });

  // TC-SYNC01: createChatProvider 가 동기적으로 throw 해도(예: opencode 인데 apiKey 없이 sdk 로
  // 잘못 설정된 경우, 또는 실제 팩토리의 "API key or OAuth token required") 요청이 응답 없이
  // 멈추지 않고 500 으로 끝나야 한다. 예전엔 ambient 폴백이 apiKey 를 항상 채워 이 동기 throw 가
  // 실제로 발생하지 않았다 — opencode 에서 그 폴백을 걷어낸 지금은 밟을 수 있는 경로다. provider
  // 생성을 try 밖에 두면 async 핸들러 안의 동기 throw 가 처리되지 않은 Promise 거부가 되어
  // (express 4 는 async 핸들러의 예외를 자동으로 잡지 않는다) 응답이 영영 나가지 않는다.
  it('TC-SYNC01: createChatProvider 가 동기적으로 throw 하면 응답 없이 멈추지 않고 500 을 반환한다', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const mockCreateChatProvider = vi.mocked(ProviderFactory.createChatProvider);
    mockCreateChatProvider.mockImplementationOnce(() => {
      throw new Error('API key or OAuth token required for SDK mode');
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1, agentType: 'sdk' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error');
  });

  it('TC3: POST /agent/proactive with template returns structured 3-section response with cards', async () => {
    const cardsData = [
      { title: '카드1', value: '100', description: '설명1' },
      { title: '카드2', value: '200', description: '설명2' },
    ];
    const rawText =
      '## 요약\n요약 내용입니다.\n\n## 통계\n통계 내용입니다.\n\n## 주요 지표\n카드 설명입니다.\n```json\n' +
      JSON.stringify(cardsData) +
      '\n```\n';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: rawText };
        yield { type: 'done', inputTokens: 100, outputTokens: 200 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '데이터를 분석해주세요',
        tenantId: 1,
        agentType: 'sdk',
        context: { metric: 42 },
        template: {
          sections: [
            { key: 'summary', label: '요약', required: true },
            { key: 'stats', label: '통계', required: true },
            { key: 'cards', label: '주요 지표', required: true, type: 'cards' },
          ],
          output_format: 'structured',
        },
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as {
      sections: Array<{ key: string; label: string; content: string; data?: unknown }>;
      rawText: string;
      usage: { inputTokens: number; outputTokens: number };
    };
    expect(body.sections).toHaveLength(3);
    expect(body.sections[0].key).toBe('summary');
    expect(body.sections[1].key).toBe('stats');
    expect(body.sections[2].key).toBe('cards');
    expect(body.sections[2].data).toEqual(cardsData);
    expect(body.rawText).toBe(rawText);
    expect(body.usage.inputTokens).toBe(100);
    expect(body.usage.outputTokens).toBe(200);
  });

  // S3: api(ProactiveAiClient)가 실은 aiPurpose 'share' 를 실행 옵션으로 넘겨 MCP 호출이 SHARE 판정을 받게 한다.
  describe('aiPurpose 전달(S3)', () => {
    const run = async (extra: Record<string, unknown>) => {
      mockExecute.mockReturnValue(
        (async function* () {
          yield { type: 'text', content: '결과' };
          yield { type: 'done', inputTokens: 1, outputTokens: 1 };
        })(),
      );
      await makeRequest(
        createApp(),
        'POST',
        '/agent/proactive',
        { prompt: 'p', tenantId: 1, agentType: 'sdk', apiKey: 'k', context: { v: 1 }, ...extra },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
      return mockExecute.mock.calls[0][0] as { aiPurpose?: string };
    };

    it("바디의 aiPurpose 'share' 를 provider.execute 로 넘긴다", async () => {
      expect((await run({ aiPurpose: 'share' })).aiPurpose).toBe('share');
    });

    it('aiPurpose 가 없으면 넘기지 않는다', async () => {
      expect((await run({})).aiPurpose).toBeUndefined();
    });

    // WD-79: 테넌트 DB 방언 — 'TIBERO' 만 TIBERO, 나머지는 POSTGRESQL.
    it("바디의 dbDialect 'TIBERO' 를 provider.execute 의 dbDialect 로 넘긴다", async () => {
      expect(((await run({ dbDialect: 'TIBERO' })) as { dbDialect?: string }).dbDialect).toBe('TIBERO');
    });

    it("dbDialect 가 없으면 'POSTGRESQL'", async () => {
      expect(((await run({})) as { dbDialect?: string }).dbDialect).toBe('POSTGRESQL');
    });

    // WD-79: Proactive 는 overrideSystemPrompt 라 메인 프롬프트가 안 들어간다 — 리포트 프롬프트에 직접 싣는다.
    it("dbDialect 'TIBERO' 면 리포트 시스템 프롬프트에 TIBERO_SQL_GUIDE 를 싣고, 없으면 싣지 않는다", async () => {
      const tibero = (await run({ dbDialect: 'TIBERO' })) as { systemPrompt?: string };
      expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(0);
      expect(tibero.systemPrompt).toContain(TIBERO_SQL_GUIDE);
      mockExecute.mockClear();
      const pg = (await run({})) as { systemPrompt?: string };
      expect(pg.systemPrompt).not.toContain('Tibero');
    });

    it("'none' 등 다른 값은 버린다 — LLM 실행에서 AI 판정을 끄면 안 된다", async () => {
      expect((await run({ aiPurpose: 'none' })).aiPurpose).toBeUndefined();
    });

    // WD-198: api 가 실은 실행 id 를 실행 옵션으로 넘긴다 — MCP 호출이 X-Proactive-Execution-Id 를 싣는다.
    it('바디의 proactiveExecutionId 를 넘기고, 양의 정수가 아니면 버린다', async () => {
      const ok = (await run({ proactiveExecutionId: 31 })) as { proactiveExecutionId?: number };
      expect(ok.proactiveExecutionId).toBe(31);
      mockExecute.mockClear();
      const bad = (await run({ proactiveExecutionId: 'x' })) as { proactiveExecutionId?: number };
      expect(bad.proactiveExecutionId).toBeUndefined();
    });
  });

  // WD-62: opencode 는 하위 에이전트·호스트 Write 가 없어 리포트 파일을 MCP 도구로 쓴다 — 그 도구가 쓸
  // 디렉터리(reportDir)를 실행 옵션으로 넘겨야 opencode 실행기가 MCP 프로세스 env 에 주입할 수 있다.
  it('WD-62: 실행 옵션으로 reportDir 을 넘긴다(테넌트 세그먼트가 담긴 proactive-report 경로)', async () => {
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'text', content: '결과' };
        yield { type: 'done', inputTokens: 1, outputTokens: 1 };
      })(),
    );
    await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: 'p', tenantId: 3, userId: 7, agentType: 'opencode', apiKey: 'k', context: { v: 1 } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    const opts = mockExecute.mock.calls[0][0] as { reportDir?: string; systemPrompt: string };
    expect(opts.reportDir).toMatch(/proactive-report[\\/]t3[\\/]7-\d+$/);
    // opencode 실행이면 프롬프트도 도구 저장 지침을 쓴다(아래 buildProactiveSystemPrompt 테스트 참고).
    expect(opts.systemPrompt).toContain('firehub_write_report_file');
  });

  it('TC4: POST /agent/proactive without template returns free-form response', async () => {
    const freeText = '자유 형식의 분석 결과입니다. 데이터를 바탕으로 인사이트를 제공합니다.';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: freeText };
        yield { type: 'done', inputTokens: 50, outputTokens: 80 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      {
        prompt: '간단히 분석해주세요',
        tenantId: 1,
        agentType: 'sdk',
        context: { value: 'test' },
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as {
      sections: Array<{ key: string; label: string; content: string }>;
      rawText: string;
      usage: { inputTokens: number; outputTokens: number };
    };
    expect(body.sections).toHaveLength(1);
    expect(body.sections[0].key).toBe('content');
    expect(body.sections[0].content).toBe(freeText);
    expect(body.rawText).toBe(freeText);
    expect(body.usage.inputTokens).toBe(50);
    expect(body.usage.outputTokens).toBe(80);
  });

  // 이슈 #350: 에이전트가 인증 실패를 일반 텍스트로 흘리고 정상 종료(done)하는 실제 시퀀스.
  // 이 경우 200으로 응답하면 백엔드가 COMPLETED로 기록하고 오류 원문을 리포트 본문 삼아
  // CHAT/EMAIL로 발송한다. 리포트 파일도 없으므로 502로 실패 처리되어야 한다.
  it('TC5: 인증 실패 텍스트가 done으로 종료되면 502로 실패 처리한다 (#350)', async () => {
    const authError =
      'Failed to authenticate. API Error: 401 {"type":"error","error":{"type":"authentication_error","message":"Invalid bearer token"},"request_id":"req_011CdYN4tsktnijbWhnP2ZCV"}';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: authError };
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    const body = res.body as { error: string; code: string };
    expect(body.code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
    // 오류 원문·request_id가 응답에 실려 나가면 안 된다 (로그에만 남긴다)
    expect(JSON.stringify(res.body)).not.toContain('request_id');
    expect(JSON.stringify(res.body)).not.toContain('Invalid bearer token');
  });

  // TC5-SDK (#711): SDK 경로는 이제 인증 실패를 텍스트가 아니라 error 이벤트(+code)로 알린다. 실제
  // processMessage 가 만든 이벤트를 그대로 흘려, firehub-api 가 의존하는 502 + AGENT_AUTH_OR_QUOTA_FAILURE
  // 계약이 유지되는지 본다(예전 텍스트 경로는 detectAgentFailure 가 이 코드를 붙였다).
  it('TC5-SDK: SDK 인증 실패 error 이벤트는 502 + AGENT_AUTH_OR_QUOTA_FAILURE 로 응답한다 (#711)', async () => {
    const { processMessage } = await import('../agent/process-message.js');
    const sdkEvents = processMessage(
      {
        type: 'assistant',
        parent_tool_use_id: null,
        error: 'authentication_failed',
        message: { content: [{ type: 'text', text: 'Failed to authenticate. API Error: 401 request_id req_x' }] },
      } as never,
      () => '[t]',
      false,
    );
    expect(sdkEvents.map((e) => e.type)).toEqual(['error']);
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield* sdkEvents;
      })(),
    );

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', apiKey: 'sk-bad', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
    expect(JSON.stringify(res.body)).not.toContain('request_id');
  });

  // TC5-CLI (#711): CLI 경로(prod agent_type=cli)의 인증 실패 이벤트도 같은 코드를 싣는다.
  it('TC5-CLI: CLI 인증 실패 error 이벤트(code 포함)도 502 + AGENT_AUTH_OR_QUOTA_FAILURE 로 응답한다 (#711)', async () => {
    const { AUTH_FAILURE_KOREAN_MESSAGE, AGENT_AUTH_OR_QUOTA_FAILURE } = await import('../agent/ai-auth-failure.js');
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'error', message: AUTH_FAILURE_KOREAN_MESSAGE, code: AGENT_AUTH_OR_QUOTA_FAILURE };
      })(),
    );

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'cli', oauthToken: 'oat', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
  });

  // TC5-MISSING (#708): createChatProvider 가 자격증명 없음으로 동기적으로 던지면 502 + 코드다 —
  // firehub-api 가 이 코드로 "AI 인증 정보 확인" 안내를 고른다(예전엔 코드 없는 500).
  it('TC5-MISSING: 자격증명 없음(MissingAiCredentialError)은 502 + AGENT_AUTH_OR_QUOTA_FAILURE', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    const { MissingAiCredentialError } = await import('../agent/ai-auth-failure.js');
    vi.mocked(ProviderFactory.createChatProvider).mockImplementationOnce(() => {
      throw new MissingAiCredentialError();
    });

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
  });

  // TC5-CODE-PASS: 이벤트의 코드는 특정 상수만 매칭하지 않고 그대로 전달한다.
  it('TC5-CODE-PASS: error 이벤트의 코드를 그대로 502 응답에 싣는다', async () => {
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'error', message: '무언가', code: 'SOME_FUTURE_CODE' };
      })(),
    );

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', apiKey: 'sk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('SOME_FUTURE_CODE');
  });

  // TC5-GENERIC (#711): 코드가 없는 일반 error 이벤트는 기존대로 500 이다(코드 없는 502 로 뭉개지 않는다).
  it('TC5-GENERIC: 코드 없는 일반 error 이벤트는 500 으로 응답한다', async () => {
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'error', message: 'max_turns_exceeded' };
      })(),
    );

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', apiKey: 'sk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(500);
    expect((res.body as { code?: string }).code).toBeUndefined();
  });

  it('TC6: 리포트 파일도 없고 출력 텍스트도 비면 502로 실패 처리한다 (#350)', async () => {
    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'done', inputTokens: 0, outputTokens: 0 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'sdk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_EMPTY_OUTPUT');
  });

  // 회귀 방지(negative control): 장애를 *서술*하는 정상 리포트는 본문에 오류 문구가 들어 있어도
  // 성공으로 처리되어야 한다. 실패 판정을 본문 선두 200자로 한정한 이유가 이것이다.
  it('TC7: 본문 중간에 오류 문구를 인용한 정상 리포트는 200을 유지한다 (#350 회귀 방지)', async () => {
    const report =
      '## 일간 운영 리포트\n어제 수집된 데이터를 분석한 결과입니다.\n\n' +
      '## 장애 내역\n외부 API 연결에서 반복 실패가 관측되었습니다. ' +
      '수집 로그에 API Error: 401 authentication_error 가 기록되어 연결 자격 증명 갱신이 필요합니다.\n';

    mockExecute.mockReturnValue(
      (async function* () {
        yield { type: 'init', sessionId: 'test-session' };
        yield { type: 'text', content: report };
        yield { type: 'done', inputTokens: 120, outputTokens: 300 };
      })(),
    );

    const app = createApp();
    const res = await makeRequest(
      app,
      'POST',
      '/agent/proactive',
      { prompt: '운영 리포트', tenantId: 1, agentType: 'sdk', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect((res.body as { rawText: string }).rawText).toBe(report);
  });
});

describe('detectAgentFailure', () => {
  it('선두의 인증 실패 문구를 탐지한다', () => {
    expect(
      detectAgentFailure('Failed to authenticate. API Error: 401 {"type":"error"}'),
    ).toBe('failed to authenticate');
    expect(detectAgentFailure('Credit balance is too low')).toBe('credit balance is too low');
    // 라이브 검증에서 실측된 SDK 문구 (잘못된 API 키)
    expect(detectAgentFailure('Invalid API key · Fix external API key')).toBe('invalid api key');
  });

  it('선두가 아닌 위치의 오류 문구는 탐지하지 않는다 (장애를 서술하는 정상 리포트 보호)', () => {
    expect(
      detectAgentFailure('## 장애 내역\n수집 로그에 API Error: 401 이 기록되었습니다.'),
    ).toBeNull();
    expect(detectAgentFailure('요약: 인증 오류로 Failed to authenticate 로그 발생')).toBeNull();
  });

  it('빈 문자열은 null을 반환한다', () => {
    expect(detectAgentFailure('   ')).toBeNull();
  });
});

describe('buildSectionPrompt', () => {
  it('should include instruction in section prompt', () => {
    const sections = [
      { key: 'summary', label: '요약', type: 'text', instruction: '핵심 지표를 요약하세요.' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('지시: 핵심 지표를 요약하세요.');
    expect(result).toContain('## 요약');
  });

  it('should skip static sections with note', () => {
    const sections = [
      { key: 'disclaimer', label: '면책조항', type: 'text', static: true, content: '고정 텍스트' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('정적 섹션');
    expect(result).not.toContain('고정 텍스트');
  });

  it('should handle nested group sections with correct header depth', () => {
    const sections = [
      {
        key: 'ops', label: '운영 현황', type: 'group',
        instruction: '시스템 운영 상태를 분석하세요.',
        children: [
          { key: 'kpi', label: '핵심 지표', type: 'cards', instruction: 'KPI 카드를 표시하세요.' },
        ],
      },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('## 운영 현황');
    expect(result).toContain('### 핵심 지표');
    expect(result).toContain('지시: 시스템 운영 상태를 분석하세요.');
    expect(result).toContain('지시: KPI 카드를 표시하세요.');
  });

  it('should skip divider sections entirely', () => {
    const sections = [
      { key: 'div1', label: '구분선', type: 'divider' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toBe('');
  });

  it('should include type guide for non-group sections', () => {
    const sections = [
      { key: 'cards1', label: '지표', type: 'cards' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('카드 형식으로 출력합니다');
  });
});

describe('parseSections', () => {
  it('should parse flat sections from AI response', () => {
    const text = '## 요약\n내용입니다.\n\n## 상세\n상세 내용.';
    const template = {
      sections: [
        { key: 'summary', label: '요약', type: 'text' },
        { key: 'detail', label: '상세', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(2);
    expect(result[0].key).toBe('summary');
    expect(result[0].content).toContain('내용입니다');
  });

  it('should skip static sections in parsing', () => {
    const text = '## 요약\n내용입니다.';
    const template = {
      sections: [
        { key: 'disclaimer', label: '면책조항', type: 'text', static: true },
        { key: 'summary', label: '요약', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('summary');
  });

  it('should flatten group children in output', () => {
    const text = '## 운영 현황\n\n### 핵심 지표\nKPI 내용';
    const template = {
      sections: [
        {
          key: 'ops', label: '운영 현황', type: 'group',
          children: [
            { key: 'kpi', label: '핵심 지표', type: 'text' },
          ],
        },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('kpi');
    expect(result[0].content).toContain('KPI 내용');
  });

  it('should return single section when no template', () => {
    const result = parseSections('some text');
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('content');
  });

  it('should extract cards JSON data', () => {
    const text = '## 지표\n설명\n```json\n[{"title":"A","value":"1","description":"d"}]\n```';
    const template = {
      sections: [{ key: 'stats', label: '지표', type: 'cards' }],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as Parameters<typeof parseSections>[1]);
    expect(result[0].data).toEqual([{ title: 'A', value: '1', description: 'd' }]);
  });
});

// WD-62: opencode 경로는 report-writer 위임이 불가능(task deny·Write 비활성)하므로 프롬프트가 갈린다.
describe('buildProactiveSystemPrompt', () => {
  const DIR = '/tmp/proactive-report/t3/7-1';
  const TEMPLATE = {
    sections: [
      { key: 'summary', label: '요약', type: 'text' },
      { key: 'kpi', label: '주요 지표', type: 'cards' },
    ],
    output_format: 'html',
  };

  it('sdk/cli 는 기존대로 report-writer 위임과 저장 디렉터리를 지시한다(동작 불변)', () => {
    for (const agentType of ['sdk', 'cli', 'cli-api'] as const) {
      const p = buildProactiveSystemPrompt(TEMPLATE, DIR, agentType);
      expect(p).toContain('report-writer');
      expect(p).toContain(`${DIR}/report.html`);
      expect(p).not.toContain('write_report_file');
    }
  });

  it('opencode 는 위임·Agent 도구를 언급하지 않고 write_report_file 로 세 파일을 저장하게 한다', () => {
    const p = buildProactiveSystemPrompt(TEMPLATE, DIR, 'opencode');
    // 막힌 위임을 언급하면 약한 모델이 `<call:Agent ...>` 를 텍스트로 흉내 낸다(2026-06-24 인시던트).
    expect(p).not.toMatch(/report-writer|위임|Agent 도구|WebSearch/);
    expect(p).toContain('firehub_write_report_file');
    for (const name of ['report.html', 'report.md', 'summary.md']) expect(p).toContain(name);
    // 도구는 파일명만 받는다 — 절대경로를 보여 주면 모델이 경로를 인자로 넣으려 한다.
    expect(p).not.toContain(DIR);
  });

  it('opencode 프롬프트에 report-writer 의 핵심 작성 규칙을 요약해 담는다', () => {
    const p = buildProactiveSystemPrompt(TEMPLATE, DIR, 'opencode');
    expect(p).toContain('<!DOCTYPE html>');
    expect(p).toMatch(/CSS 2\.1/);
    expect(p).toMatch(/flex/i);
    expect(p).toMatch(/SVG/);
    expect(p).toMatch(/외부 리소스/);
    expect(p).toContain('<section>');
    expect(p).toMatch(/3~5줄/);
    expect(p).toContain('```json');
    // 집계 수치는 집계 SQL 도구로(WD-66 과 같은 규칙) — 오버라이드로 OPENCODE_SYSTEM_PROMPT 가 빠지므로 여기 있어야 한다.
    expect(p).toContain('firehub_execute_analytics_query');
    // 파일 저장이 실패하면 라우트가 최종 텍스트로 폴백한다 — "저장했습니다" 한 줄이 리포트 본문으로 발송되지 않게
    // 최종 응답을 요약 자체로 지시한다(#350 과 같은 거짓 성공 방지).
    expect(p).toMatch(/최종 응답으로 summary\.md 와 같은 요약/);
    // 양식 섹션 구조는 그대로 실린다.
    expect(p).toContain('- 요약 (key: summary, type: text)');
    expect(p).toContain('- 주요 지표 (key: kpi, type: cards)');
  });

  it('opencode 에서 양식이 없으면 자유 형식 지시를 위임 문구 없이 쓴다', () => {
    const p = buildProactiveSystemPrompt(undefined, DIR, 'opencode');
    expect(p).toContain('자유 형식');
    expect(p).not.toMatch(/위임/);
  });
});

/**
 * 방언별 Proactive 프롬프트(WD-79).
 *
 * PG 는 변경 전과 바이트 동일해야 한다 — 픽스처(`src/agent/__fixtures__/pg-prompts/proactive-*.txt`)는 변경 전
 * 코드에서 아래 RICH_TEMPLATE·DIR 로 덤프했다(정적·구분선·그룹·스타일까지 거치도록).
 * Tibero 는 PG 프롬프트 끝(footer 뒤)에 TIBERO_SQL_GUIDE 를 붙인다.
 */
describe('buildProactiveSystemPrompt — 방언별 (WD-79)', () => {
  const DIR = '/tmp/proactive-report/t3/7-1';
  const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'agent', '__fixtures__', 'pg-prompts');
  const fixture = (name: string) => fs.readFileSync(path.join(FIXTURES, name), 'utf-8');
  const RICH_TEMPLATE = {
    style: '간결한 보고체',
    output_format: 'html',
    sections: [
      { key: 'title', label: '제목', type: 'text', static: true, content: '고정' },
      { key: 'summary', label: '요약', type: 'text', instruction: '핵심을 먼저' },
      { key: 'div', label: '-', type: 'divider' },
      {
        key: 'grp',
        label: '지표 묶음',
        type: 'group',
        instruction: '지표를 모은다',
        children: [
          { key: 'kpi', label: '주요 지표', type: 'cards', instruction: '전월 대비' },
          { key: 'tbl', label: '표', type: 'table' },
        ],
      },
      { key: 'rec', label: '권고', type: 'recommendation' },
    ],
  };
  const CASES = [
    ['sdk', undefined, 'proactive-sdk-no-template.txt'],
    ['sdk', RICH_TEMPLATE, 'proactive-sdk-template.txt'],
    ['opencode', undefined, 'proactive-opencode-no-template.txt'],
    ['opencode', RICH_TEMPLATE, 'proactive-opencode-template.txt'],
  ] as const;

  it.each(CASES)('PG(%s) — 방언 생략·POSTGRESQL 모두 변경 전과 바이트 동일 (%#)', (agentType, template, file) => {
    expect(buildProactiveSystemPrompt(template, DIR, agentType)).toBe(fixture(file));
    expect(buildProactiveSystemPrompt(template, DIR, agentType, 'POSTGRESQL')).toBe(fixture(file));
  });

  it.each(CASES)('TIBERO(%s) — PG 프롬프트 뒤에 TIBERO_SQL_GUIDE 를 붙인다 (%#)', (agentType, template, file) => {
    const p = buildProactiveSystemPrompt(template, DIR, agentType, 'TIBERO');
    expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(0);
    expect(p.startsWith(fixture(file))).toBe(true);
    expect(p.endsWith(TIBERO_SQL_GUIDE)).toBe(true);
    expect(findPgOnlySyntax(p)).toEqual([]);
    expect(hasTiberoCoreRules(p)).toBe(true);
  });

  it('cli 경로도 sdk 와 같은 Tibero 규칙을 받는다', () => {
    expect(buildProactiveSystemPrompt(undefined, DIR, 'cli', 'TIBERO')).toBe(
      buildProactiveSystemPrompt(undefined, DIR, 'sdk', 'TIBERO'),
    );
  });
});
