import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import fs from 'fs';
import fsp from 'fs/promises';
import path from 'path';
import { fileURLToPath } from 'url';
import { TIBERO_SQL_GUIDE } from '../agent/tibero-sql-guide.js';
import { findPgOnlySyntax, hasTiberoCoreRules } from '../agent/tibero-prompt-lint.fixture.js';
import proactiveRouter, {
  buildSectionPrompt,
  parseSections,
  detectAgentFailure,
  buildProactiveSystemPrompt,
  htmlToText,
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

  // WD-765: 리포트 파일 없이 rawText 를 리포트로 파싱하는 건 위임이 없는 opencode 뿐이다(sdk·cli 는 파일이 없으면 저장
  // 실패 502). 그래서 rawText 파싱을 다루는 시험(TC3·TC4·TC7·줄바꿈 묶음)은 opencode 로 보낸다.
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
        agentType: 'opencode',
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

  // WD-765: 도구 호출 사이로 끊긴 text 블록이 줄바꿈 없이 붙으면 「앞 설명## 요약」이 되어 헤더 정규식(^)에 안 잡힌다.
  describe('rawText 블록 사이 줄바꿈 (WD-765)', () => {
    const TEMPLATE = {
      sections: [{ key: 'summary', label: '요약', required: true }],
      output_format: 'structured',
    };
    const post = (events: Array<Record<string, unknown>>, template?: unknown) => {
      mockExecute.mockReturnValue(
        (async function* () {
          for (const e of events) yield e;
          yield { type: 'done', inputTokens: 1, outputTokens: 1 };
        })(),
      );
      return makeRequest(
        createApp(),
        'POST',
        '/agent/proactive',
        { prompt: 'p', tenantId: 1, agentType: 'opencode', context: {}, template },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
    };
    type Body = { sections: Array<{ key: string; content: string }>; rawText: string };

    it('text → tool_use → text 사이에 빈 줄을 넣어 「## 요약」 섹션이 잡힌다', async () => {
      const res = await post(
        [
          { type: 'text', content: '앞 설명' },
          { type: 'tool_use', toolName: 'x', input: {} },
          { type: 'text', content: '## 요약\n내용' },
        ],
        TEMPLATE,
      );
      const body = res.body as Body;
      expect(body.rawText).toBe('앞 설명\n\n## 요약\n내용');
      expect(body.sections.map((s) => s.key)).toEqual(['summary']);
      expect(body.sections[0].content).toBe('내용');
    });

    it('같은 블록의 델타 text 는 사이에 다른 이벤트가 없으면 그대로 붙는다', async () => {
      const res = await post([
        { type: 'text', content: '안녕' },
        { type: 'text', content: '하세요' },
      ]);
      expect((res.body as Body).rawText).toBe('안녕하세요');
    });

    it('앞 text 가 이미 줄바꿈으로 끝나면 더 넣지 않고, rawText 가 비었으면 앞에 넣지 않는다', async () => {
      const res = await post([
        { type: 'tool_use', toolName: 'a', input: {} },
        { type: 'text', content: '첫 줄\n' },
        { type: 'tool_result', content: 'ok' },
        { type: 'text', content: '둘째 줄' },
      ]);
      expect((res.body as Body).rawText).toBe('첫 줄\n둘째 줄');
    });
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

    // WD-744: 「행 검색 가능」 플래그를 provider 로 넘긴다(없으면 undefined — 방언 기준).
    it('바디의 rowSearch 를 provider.execute 의 rowSearch 로 넘긴다 (WD-744)', async () => {
      expect(((await run({ dbDialect: 'TIBERO', rowSearch: true })) as { rowSearch?: boolean }).rowSearch).toBe(true);
    });

    it('rowSearch 가 없으면 undefined (WD-744)', async () => {
      expect(((await run({})) as { rowSearch?: boolean }).rowSearch).toBeUndefined();
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

    // WD-766: 라우트는 api 가 실은 model 을 바꾸지 않고 provider 설정과 실행 옵션 양쪽에 그대로 넘긴다.
    it.each(['sdk', 'cli', 'cli-api', 'opencode'])(
      'WD-766: %s 에서 바디의 model 을 ProviderConfig·execute 로 그대로 넘긴다',
      async (agentType) => {
        const { ProviderFactory } = await import('../providers/index.js');
        const model = agentType === 'opencode' ? 'openai/gpt-4o' : 'claude-opus-4-1';
        const opts = (await run({ agentType, oauthToken: 't', model })) as { model?: string };
        expect(opts.model).toBe(model);
        expect(vi.mocked(ProviderFactory.createChatProvider).mock.calls[0][0].model).toBe(model);
      },
    );

    // WD-766: model 이 없으면 haiku 로 채우지 않고 비워 둔다 — 채팅처럼 공급자 기본 모델이 정한다.
    it('WD-766: 바디에 model 이 없으면 model 을 비워 공급자 기본값에 맡긴다', async () => {
      const { ProviderFactory } = await import('../providers/index.js');
      expect(((await run({})) as { model?: string }).model).toBeUndefined();
      expect(vi.mocked(ProviderFactory.createChatProvider).mock.calls[0][0].model).toBeUndefined();
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
        agentType: 'opencode',
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

  // WD-765: 위임 경로(sdk·cli)의 같은 상황은 아래 「리포트 파일 저장 실패 안내」에서 AGENT_REPORT_NOT_SAVED 로 본다 —
  // 이 TC 는 위임이 없는 opencode 경로에서 기존 코드가 그대로인지 지킨다.
  it('TC6: 리포트 파일도 없고 출력 텍스트도 비면 502로 실패 처리한다 (#350, opencode)', async () => {
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
      { prompt: '일간 KPI 리포트', tenantId: 1, agentType: 'opencode', apiKey: 'k', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(502);
    expect((res.body as { code: string }).code).toBe('AGENT_EMPTY_OUTPUT');
  });

  // WD-765: 위임 경로(sdk·cli)에서 report-writer 가 파일을 저장하지 못하면 본문 글(rawText)과 상관없이 502
  // AGENT_REPORT_NOT_SAVED 로 실패한다 — 그 글은 리포트가 아니라 메인 에이전트의 설명(저장 실패 문장 등)이라 CHAT/EMAIL
  // 로 나가면 안 된다. 인증·한도 신호는 언제나 우선이고, 위임이 없는 opencode 는 rawText 로 대신 응답한다.
  describe('리포트 파일 저장 실패 안내 (WD-765)', () => {
    type Events = Array<Record<string, unknown>>;
    /** events 를 흘리는 실행. writeFiles 가 있으면 실행 중에 reportDir 에 그 파일들을 저장한다(report-writer 흉내). */
    const post = (agentType: string, events: Events, writeFiles?: Record<string, string>) => {
      mockExecute.mockImplementation((opts: { reportDir: string }) =>
        (async function* () {
          if (writeFiles) {
            await fs.promises.mkdir(opts.reportDir, { recursive: true });
            for (const [name, content] of Object.entries(writeFiles)) {
              await fs.promises.writeFile(path.join(opts.reportDir, name), content, 'utf-8');
            }
          }
          for (const e of events) yield e;
          yield { type: 'done', inputTokens: 1, outputTokens: 1 };
        })(),
      );
      return makeRequest(
        createApp(),
        'POST',
        '/agent/proactive',
        { prompt: 'p', tenantId: 1, agentType, apiKey: 'k', oauthToken: 'o', context: {} },
        { Authorization: `Internal ${VALID_TOKEN}` },
      );
    };
    type Body = { code?: string; rawText?: string };

    it.each(['sdk', 'cli'])('(a) %s: 파일도 rawText 도 없으면 502 + AGENT_REPORT_NOT_SAVED', async (agentType) => {
      const res = await post(agentType, []);
      expect(res.status).toBe(502);
      expect((res.body as Body).code).toBe('AGENT_REPORT_NOT_SAVED');
    });

    it.each(['sdk', 'cli'])('(b) %s: 파일이 없어도 rawText 가 인증 실패 신호면 AGENT_AUTH_OR_QUOTA_FAILURE 가 우선', async (agentType) => {
      const res = await post(agentType, [{ type: 'text', content: 'Failed to authenticate. API Error: 401 invalid' }]);
      expect(res.status).toBe(502);
      expect((res.body as Body).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
    });

    it.each(['sdk', 'cli'])('(c) %s: 파일이 없으면 rawText 가 있어도 502 + AGENT_REPORT_NOT_SAVED (본문 글을 리포트로 내보내지 않는다)', async (agentType) => {
      const res = await post(agentType, [
        { type: 'text', content: '리포트 저장에 실패했습니다. report-writer 가 파일을 저장하지 못했습니다.' },
      ]);
      expect(res.status).toBe(502);
      const body = res.body as Body;
      expect(body.code).toBe('AGENT_REPORT_NOT_SAVED');
      // 메인 에이전트가 쓴 글은 응답에 싣지 않는다
      expect(body.rawText).toBeUndefined();
    });

    it('(d) sdk: 리포트 파일이 있으면 200 이고 파일 내용으로 응답한다', async () => {
      const res = await post('sdk', [{ type: 'text', content: '생성 완료' }], {
        'report.html': '<h1>리포트</h1>',
        'report.md': '## 요약\n내용',
        'summary.md': '요약',
      });
      expect(res.status).toBe(200);
      expect((res.body as Body).rawText).toBe('<h1>리포트</h1>');
    });

    it('(d2) sdk: report.md 만 있어도(report.html 없음) 저장된 것으로 보고 200 이다', async () => {
      const res = await post('sdk', [{ type: 'text', content: '생성 완료' }], { 'report.md': '## 요약\n내용' });
      expect(res.status).toBe(200);
    });

    it('(e) opencode: 파일이 없고 rawText 가 있으면 200 으로 rawText 를 그대로 응답한다', async () => {
      const res = await post('opencode', [{ type: 'text', content: '분석 결과 본문' }]);
      expect(res.status).toBe(200);
      expect((res.body as Body).rawText).toBe('분석 결과 본문');
    });

    it('(f) opencode: 파일이 없고 rawText 가 인증 실패 신호면 AGENT_AUTH_OR_QUOTA_FAILURE 다', async () => {
      const res = await post('opencode', [{ type: 'text', content: 'Failed to authenticate. API Error: 401 invalid' }]);
      expect(res.status).toBe(502);
      expect((res.body as Body).code).toBe('AGENT_AUTH_OR_QUOTA_FAILURE');
    });
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
      { prompt: '운영 리포트', tenantId: 1, agentType: 'opencode', context: { value: 'test' } },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect((res.body as { rawText: string }).rawText).toBe(report);
  });

  // WD-766: firehub-api 가 제한 시간(15분)에 연결을 끊으면 api 는 이미 FAILED 로 기록했다 — ai-agent 가 계속 돌면
  // 아무도 받지 않을 리포트에 토큰·MCP 호출을 쓴다. 응답 전에 연결이 닫히면 실행을 abort 하고, 이벤트 루프를
  // 빠져나와 이후 이벤트·응답을 쓰지 않아야 한다.
  it('WD-766: 응답 전에 연결이 닫히면 provider 실행을 abort 하고 이후 이벤트를 소비하지 않는다', async () => {
    let receivedSignal: AbortSignal | undefined;
    let eventsAfterAbort = 0;
    let generatorFinished = false;
    mockExecute.mockImplementation((opts: { abortSignal?: AbortSignal }) => {
      receivedSignal = opts.abortSignal;
      return (async function* () {
        try {
          yield { type: 'text', content: '진행 중' };
          // 실제 provider 처럼 abort 될 때까지(또는 오래) 일한다 — 신호가 없으면 시험 제한 시간까지 끝나지 않는다.
          await new Promise<void>((resolve) => {
            if (opts.abortSignal?.aborted) resolve();
            opts.abortSignal?.addEventListener('abort', () => resolve(), { once: true });
          });
          for (let i = 0; i < 3; i++) {
            eventsAfterAbort++;
            yield { type: 'text', content: `abort 뒤 ${i}` };
          }
          yield { type: 'done', inputTokens: 1, outputTokens: 1 };
        } finally {
          generatorFinished = true;
        }
      })();
    });

    const app = createApp();
    const server = app.listen(0);
    try {
      await new Promise<void>((resolve) => server.once('listening', () => resolve()));
      const addr = server.address() as { port: number };
      const client = new AbortController();
      const pending = fetch(`http://localhost:${addr.port}/agent/proactive`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Internal ${VALID_TOKEN}` },
        body: JSON.stringify({ prompt: 'p', tenantId: 1, agentType: 'sdk', apiKey: 'k', context: {} }),
        signal: client.signal,
      }).catch(() => undefined);

      // 실행이 시작된 뒤에 api 쪽(클라이언트)이 끊는다 — 15분 제한에 걸린 상황.
      await vi.waitFor(() => expect(mockExecute).toHaveBeenCalled());
      expect(receivedSignal).toBeDefined();
      expect(receivedSignal?.aborted).toBe(false);
      client.abort();
      await pending;

      await vi.waitFor(() => expect(receivedSignal?.aborted).toBe(true));
      await vi.waitFor(() => expect(generatorFinished).toBe(true));
      // 루프는 abort 뒤 첫 이벤트에서 빠져나온다 — 나머지 이벤트를 끝까지 소비하지 않는다.
      expect(eventsAfterAbort).toBeLessThanOrEqual(1);
    } finally {
      server.close();
    }
  });

  // WD-766: 정상 응답이 끝난 뒤의 close(응답 완료 후 소켓 정리)는 abort 가 아니다.
  it('WD-766: 응답을 다 보낸 뒤의 연결 종료는 실행을 abort 하지 않는다', async () => {
    let receivedSignal: AbortSignal | undefined;
    mockExecute.mockImplementation((opts: { abortSignal?: AbortSignal }) => {
      receivedSignal = opts.abortSignal;
      return (async function* () {
        yield { type: 'text', content: '결과' };
        yield { type: 'done', inputTokens: 1, outputTokens: 1 };
      })();
    });

    const res = await makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: 'p', tenantId: 1, agentType: 'sdk', apiKey: 'k', context: {} },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect(receivedSignal).toBeDefined();
    expect(receivedSignal?.aborted).toBe(false);
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
  // 프롬프트는 리포트 경로를 받지 않는다(WD-765) — 저장 위치는 write_report_file 도구가 정한다. 그래도 리포트 폴더
  // 이름이 문구에 새어 들어가지 않는지(모델이 경로를 인자로 넣으려 하지 않게) 이 패턴으로 확인한다.
  const REPORT_PATH = /proactive-report\//;
  const TEMPLATE = {
    sections: [
      { key: 'summary', label: '요약', type: 'text' },
      { key: 'kpi', label: '주요 지표', type: 'cards' },
    ],
    output_format: 'html',
  };

  it('sdk/cli 는 report-writer 위임을 지시하되 전체 경로 대신 파일 이름(fileName)으로 안내한다 (WD-765)', () => {
    for (const agentType of ['sdk', 'cli', 'cli-api'] as const) {
      const p = buildProactiveSystemPrompt(TEMPLATE, agentType);
      expect(p).toContain('report-writer');
      // 하위 에이전트는 write_report_file(fileName·content)로만 저장한다 — 경로를 보여 주면 경로를 인자로 넣으려 한다.
      expect(p).not.toMatch(REPORT_PATH);
      for (const name of ['report.html', 'report.md', 'summary.md']) expect(p).toContain(`fileName=${name}`);
    }
  });

  it('sdk/cli 위임 프롬프트는 저장 실패를 생성 완료라고 보고하지 말라고 지시한다 (WD-765)', () => {
    for (const agentType of ['sdk', 'cli', 'cli-api'] as const) {
      const p = buildProactiveSystemPrompt(TEMPLATE, agentType);
      expect(p).toContain('저장 실패');
      expect(p).toContain('생성 완료라고 쓰지');
    }
  });

  it('opencode 는 위임·Agent 도구를 언급하지 않고 write_report_file 로 세 파일을 저장하게 한다', () => {
    const p = buildProactiveSystemPrompt(TEMPLATE, 'opencode');
    // 막힌 위임을 언급하면 약한 모델이 `<call:Agent ...>` 를 텍스트로 흉내 낸다(2026-06-24 인시던트).
    expect(p).not.toMatch(/report-writer|위임|Agent 도구|WebSearch/);
    expect(p).toContain('firehub_write_report_file');
    for (const name of ['report.html', 'report.md', 'summary.md']) expect(p).toContain(name);
    // 도구는 파일명만 받는다 — 절대경로를 보여 주면 모델이 경로를 인자로 넣으려 한다.
    expect(p).not.toMatch(REPORT_PATH);
  });

  it('opencode 프롬프트에 report-writer 의 핵심 작성 규칙을 요약해 담는다', () => {
    const p = buildProactiveSystemPrompt(TEMPLATE, 'opencode');
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
    const p = buildProactiveSystemPrompt(undefined, 'opencode');
    expect(p).toContain('자유 형식');
    expect(p).not.toMatch(/위임/);
  });
});

/**
 * 방언별 Proactive 프롬프트(WD-79).
 *
 * PG 는 픽스처(`src/agent/__fixtures__/pg-prompts/proactive-*.txt`)와 바이트 동일해야 한다 — 픽스처는 아래
 * RICH_TEMPLATE 로 만든 기대 출력이다(정적·구분선·그룹·스타일까지 거치도록). 프롬프트 문구를 의도해 바꾸면 픽스처도 함께 고친다.
 * Tibero 는 PG 프롬프트 끝(footer 뒤)에 TIBERO_SQL_GUIDE 를 붙인다.
 */
describe('buildProactiveSystemPrompt — 방언별 (WD-79)', () => {
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
    expect(buildProactiveSystemPrompt(template, agentType)).toBe(fixture(file));
    expect(buildProactiveSystemPrompt(template, agentType, 'POSTGRESQL')).toBe(fixture(file));
  });

  it.each(CASES)('TIBERO(%s) — PG 프롬프트 뒤에 TIBERO_SQL_GUIDE 를 붙인다 (%#)', (agentType, template, file) => {
    const p = buildProactiveSystemPrompt(template, agentType, 'TIBERO');
    expect(TIBERO_SQL_GUIDE.length).toBeGreaterThan(0);
    expect(p.startsWith(fixture(file))).toBe(true);
    expect(p.endsWith(TIBERO_SQL_GUIDE)).toBe(true);
    expect(findPgOnlySyntax(p)).toEqual([]);
    expect(hasTiberoCoreRules(p)).toBe(true);
  });

  it('cli 경로도 sdk 와 같은 Tibero 규칙을 받는다', () => {
    expect(buildProactiveSystemPrompt(undefined, 'cli', 'TIBERO')).toBe(
      buildProactiveSystemPrompt(undefined, 'sdk', 'TIBERO'),
    );
  });
});

/**
 * WD-767: 리포트 수치 출처 대조 — 데이터 조회 도구 결과와 리포트 본문 숫자를 맞춰 응답에 싣는다.
 * 모킹한 공급자가 reportDir 에 실제 리포트 파일을 쓰게 해 라우트의 파일 읽기 경로까지 거친다.
 */
describe('POST /agent/proactive — numberCheck (WD-767)', () => {
  const VALID_TOKEN = 'test-internal-token';

  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  /** events 를 내고, md 가 있으면 호출 시점에 받은 reportDir 에 report.md 를 쓰는 공급자 목. */
  function mockRun(events: Record<string, unknown>[], md?: string) {
    mockExecute.mockImplementation((opts: { reportDir: string }) =>
      (async function* () {
        for (const e of events) yield e;
        if (md !== undefined) {
          await fsp.mkdir(opts.reportDir, { recursive: true });
          await fsp.writeFile(`${opts.reportDir}/report.md`, md);
          await fsp.writeFile(`${opts.reportDir}/summary.md`, '요약');
        }
        yield { type: 'done', inputTokens: 1, outputTokens: 1 };
      })(),
    );
  }

  // agentType 기본은 sdk. 파일 없이 rawText 가 리포트가 되는 경로는 위임이 없는 opencode 뿐이라(WD-765) 그 시험만 opencode 로 보낸다.
  async function run(agentType: 'sdk' | 'opencode' = 'sdk') {
    return makeRequest(
      createApp(),
      'POST',
      '/agent/proactive',
      { prompt: '분석', context: { data: 'test' }, tenantId: 1, agentType },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
  }

  it('데이터 조회 결과에 없는 수치만 numberCheck.unverified 에 싣는다', async () => {
    mockRun(
      [
        { type: 'tool_use', toolName: 'mcp__firehub__execute_analytics_query', toolUseId: 't1', input: {} },
        { type: 'tool_result', toolName: 't1', result: '[{"amt":350000000}]' },
      ],
      '매출은 3.5억 원이고 목표는 4.5억 원입니다.',
    );
    const res = await run();
    expect(res.status).toBe(200);
    const nc = (res.body as { numberCheck: { checked: boolean; unverified: { text: string }[] } }).numberCheck;
    expect(nc.checked).toBe(true);
    expect(nc.unverified.map((u) => u.text)).toEqual(['4.5억']);
  });

  it('데이터 조회 결과가 없으면 checked=false', async () => {
    mockRun([], '매출은 3.5억입니다.');
    const res = await run();
    const nc = (res.body as { numberCheck: { checked: boolean; unverifiedTotal: number } }).numberCheck;
    expect(nc.checked).toBe(false);
    expect(nc.unverifiedTotal).toBe(0);
  });

  it('비데이터 도구(Write) 결과의 숫자는 출처로 치지 않는다', async () => {
    mockRun(
      [
        { type: 'tool_use', toolName: 'Write', toolUseId: 'w1', input: {} },
        { type: 'tool_result', toolName: 'w1', result: '350000000 written' },
      ],
      '매출은 3.5억입니다.',
    );
    const res = await run();
    expect((res.body as { numberCheck: { checked: boolean } }).numberCheck.checked).toBe(false);
  });

  it('리포트 파일 없이 rawText 가 리포트가 되면(opencode) rawText 를 대조한다', async () => {
    mockRun([
      { type: 'tool_use', toolName: 'mcp__firehub__execute_analytics_query', toolUseId: 't1', input: {} },
      { type: 'tool_result', toolName: 't1', result: '[{"amt":350000000}]' },
      { type: 'text', content: '매출은 3.5억 원이고 목표는 4.5억 원입니다.' },
    ]);
    const res = await run('opencode');
    expect(res.status).toBe(200);
    const nc = (res.body as { numberCheck: { checked: boolean; unverified: { text: string }[] } }).numberCheck;
    expect(nc.checked).toBe(true);
    expect(nc.unverified.map((u) => u.text)).toEqual(['4.5억']);
  });

  it('id 로 짝지은 결과는 직전 tool_use 가 아니라 실제 호출 도구로 판정한다 (CLI 병렬 호출)', async () => {
    // 데이터 조회와 Write 를 한 배치로 발행 — 결과 순서가 tool_use 순서와 달라도 id 로 짝짓는다
    mockRun(
      [
        { type: 'tool_use', toolName: 'mcp__firehub__execute_analytics_query', toolUseId: 'q1', input: {} },
        { type: 'tool_use', toolName: 'Write', toolUseId: 'w1', input: {} },
        { type: 'tool_result', toolName: 'q1', result: '[{"amt":350000000}]', isError: false },
        { type: 'tool_result', toolName: 'w1', result: '450000000 written', isError: false },
      ],
      '매출은 3.5억 원이고 목표는 4.5억 원입니다.',
    );
    const res = await run();
    const nc = (res.body as { numberCheck: { checked: boolean; unverified: { text: string }[] } }).numberCheck;
    expect(nc.checked).toBe(true);
    expect(nc.unverified.map((u) => u.text)).toEqual(['4.5억']);
  });

  it('isError 인 데이터 조회 결과는 출처로 쓰지 않는다', async () => {
    mockRun(
      [
        { type: 'tool_use', toolName: 'mcp__firehub__execute_sql_query', toolUseId: 'e1', input: {} },
        { type: 'tool_result', toolName: 'e1', result: 'error at line 350000000', isError: true },
      ],
      '매출은 3.5억입니다.',
    );
    const res = await run();
    expect((res.body as { numberCheck: { checked: boolean } }).numberCheck.checked).toBe(false);
  });

  it('위임 프롬프트가 원본 데이터를 JSON 그대로 붙이게 지시한다', () => {
    const p = buildProactiveSystemPrompt(undefined, 'sdk');
    expect(p).toContain('원본 데이터');
    expect(p).toContain('JSON 그대로');
  });
});

describe('htmlToText (WD-767)', () => {
  it('script·style·태그와 HTML 엔티티(숫자 엔티티 포함)를 제거해 엔티티 숫자가 리포트 수치로 읽히지 않는다', () => {
    const t = htmlToText('<style>a{width:99px}</style><p>매출 3.5억&nbsp;원 &amp; 비용&#123;&#x1F;</p>');
    expect(t).not.toMatch(/99|123|1F|&/);
    expect(t).toContain('3.5억');
  });

  it('svg 블록(축 눈금·좌표 텍스트)을 통째로 지운다', () => {
    const t = htmlToText('<p>매출 3.5억</p><svg viewBox="0 0 400 300"><text x="40">1200</text><g><text>800</text></g></svg><p>끝</p>');
    expect(t).not.toMatch(/1200|800|400/);
    expect(t).toContain('3.5억');
    expect(t).toContain('끝');
  });
});
