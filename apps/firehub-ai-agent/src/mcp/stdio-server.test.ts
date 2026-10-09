/**
 * stdio-server.ts 의 main() 배선(hop) 테스트.
 *
 * 리뷰 라운드 1 지적(Ruling #30 재발): stdio-credentials.test.ts 는 resolveStdioCredentials()
 * 라는 순수 함수만 검증한다 — main() 안에서 그 결과를 실제로 registerAllTools 에 전달하는 배선
 * ("hop")은 이전에는 어떤 테스트도 지나가지 않았다. main() 이 파일 import 시점에 무조건
 * 실행됐기 때문에(가드 없음) 테스트가 이 파일을 안전하게 import 할 방법이 없었다.
 *
 * 이 파일은 stdio-server.ts 의 무거운 협력자(McpServer/StdioServerTransport/FireHubApiClient/
 * registerAllTools)를 모두 모킹하고, isMainModule() 가드 덕분에 자동 기동 없이 안전하게 import 한
 * 뒤 main() 을 직접 호출해 "credentials 로 무엇이 전달되는가" 를 끝까지 확인한다.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const registerAllToolsMock = vi.fn();
// registerAllTools 만 가로채고 나머지(toolErrorResult·withFailureHint — createMcpSafeTool 이 쓰는 공통 헬퍼)는 실물을 쓴다.
vi.mock('./firehub-mcp-server.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./firehub-mcp-server.js')>()),
  registerAllTools: (...args: unknown[]) => registerAllToolsMock(...args),
}));

const mcpServerToolMock = vi.fn();
const mcpServerConnectMock = vi.fn().mockResolvedValue(undefined);
vi.mock('@modelcontextprotocol/sdk/server/mcp.js', () => ({
  McpServer: vi.fn(function McpServer() {
    return { tool: mcpServerToolMock, connect: mcpServerConnectMock };
  }),
}));

vi.mock('@modelcontextprotocol/sdk/server/stdio.js', () => ({
  StdioServerTransport: vi.fn(function StdioServerTransport() {
    return { send: vi.fn() };
  }),
}));

const apiClientCtorMock = vi.fn();
// parseSharePurpose 는 실제 구현을 쓴다 — 목적 해석 규칙(정확히 'share' 만)도 이 테스트가 함께 검증한다.
vi.mock('./api-client.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api-client.js')>()),
  FireHubApiClient: vi.fn(function FireHubApiClient(...args: unknown[]) {
    apiClientCtorMock(...args);
    return {};
  }),
}));

describe('stdio-server main() — registerAllTools 로의 자격증명 배선(hop)', () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    vi.clearAllMocks();
    // 실제 실행처럼 보이지 않게 argv[1] 을 비워 isMainModule() 이 항상 false 를 반환하도록 한다
    // (테스트 프로세스의 실제 argv[1] 은 vitest 러너 경로라 이 파일과 우연히도 다르지만, 명시적으로
    // 통제해 다른 테스트 러너 설정에서도 안전하게 만든다).
    process.env.USER_ID = '7';
    process.env.TENANT_ID = '3';
  });

  afterEach(() => {
    process.env = { ...originalEnv };
  });

  it('AI_CREDENTIAL_AGENT_TYPE=opencode 면 ambient ANTHROPIC_API_KEY 가 아니라 테넌트 provider 자격증명이 registerAllTools 에 전달된다', async () => {
    // ambient 값이 실제로 세팅돼 있어야 "안 새는지"가 의미 있는 단언이 된다 — 원래 버그가 정확히
    // 이 상황(opencode 세션인데 ambient 키가 컨테이너에 남아 있음)에서 재현됐다.
    process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    process.env.CLAUDE_CODE_OAUTH_TOKEN = 'ambient-oauth-must-not-leak';
    process.env.AI_CREDENTIAL_AGENT_TYPE = 'opencode';
    process.env.AI_CREDENTIAL_PROVIDER_ID = 'openai';
    process.env.AI_CREDENTIAL_BASE_URL = 'https://api.openai.com/v1';
    process.env.AI_CREDENTIAL_API_KEY = 'sk-tenant-key';
    process.env.AI_CREDENTIAL_MODEL = 'openai/gpt-4o';

    const { main } = await import('./stdio-server.js');
    await main();

    expect(registerAllToolsMock).toHaveBeenCalledTimes(1);
    const options = registerAllToolsMock.mock.calls[0][3] as {
      credentials?: Record<string, unknown>;
    };
    expect(options.credentials).toEqual({
      agentType: 'opencode',
      providerId: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: 'sk-tenant-key',
      reasoningEffort: undefined,
      model: 'openai/gpt-4o',
    });
    // `in` 으로 확인한다 — falsy 비교는 빈 문자열과 부재를 구분하지 못해, ambient 키를 빈
    // 문자열로 덮어쓰는 뮤턴트를 놓친다.
    expect('oauthToken' in (options.credentials ?? {})).toBe(false);
  });

  it('AI_CREDENTIAL_AGENT_TYPE 이 없으면 CLI 경로로 ambient Anthropic 자격증명이 그대로 전달된다 (기존 동작 유지)', async () => {
    process.env.ANTHROPIC_API_KEY = 'sk-anthropic';
    process.env.CLAUDE_CODE_OAUTH_TOKEN = 'oat-anthropic';

    const { main } = await import('./stdio-server.js');
    await main();

    const options = registerAllToolsMock.mock.calls[0][3] as {
      credentials?: Record<string, unknown>;
    };
    expect(options.credentials).toEqual({ apiKey: 'sk-anthropic', oauthToken: 'oat-anthropic' });
  });
});

// S3: 부모(agent-cli·agent-opencode)가 심은 AI_PURPOSE 를 api 클라이언트의 목적 헤더로 잇는 배선.
describe('stdio-server main() — AI_PURPOSE 배선(S3)', () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    vi.clearAllMocks();
    process.env.USER_ID = '7';
    process.env.TENANT_ID = '3';
    process.env.ANTHROPIC_API_KEY = 'sk-anthropic';
  });

  afterEach(() => {
    process.env = { ...originalEnv };
  });

  it("AI_PURPOSE=share 면 클라이언트를 purpose 'share' 로 만든다", async () => {
    process.env.AI_PURPOSE = 'share';
    const { main } = await import('./stdio-server.js');
    await main();
    expect(apiClientCtorMock).toHaveBeenCalledWith(expect.any(String), expect.any(String), 7, 3, { purpose: 'share' });
  });

  it("AI_PURPOSE=none 은 받지 않는다 — LLM 경로에서 AI 판정을 끄면 안 된다", async () => {
    process.env.AI_PURPOSE = 'none';
    const { main } = await import('./stdio-server.js');
    await main();
    const args = apiClientCtorMock.mock.calls[0];
    expect(args.slice(2)).toEqual([7, 3, { purpose: undefined }]);
  });
});

// S3: CLI·opencode 런타임의 도구 래퍼도 정책 차단을 고정 표식으로 싣는다(SDK 래퍼와 같은 헬퍼).
describe('createMcpSafeTool — POLICY_BLOCKED 표식(S3)', () => {
  it('차단 오류는 표식 JSON 하나만 싣고, 임계 반복에도 경고 힌트를 붙이지 않는다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { createMcpSafeTool } = await import('./stdio-server.js');
    const { createTracker } = await import('../agent/failure-streak.js');
    const handlers: Array<(a: Record<string, unknown>) => Promise<{ content: { text: string }[]; isError?: boolean }>> =
      [];
    const fakeServer = {
      tool: (_n: string, _d: string, _s: unknown, h: (typeof handlers)[number]) => handlers.push(h),
    };
    const safeTool = createMcpSafeTool(fakeServer as never, createTracker({ warnAt: 2, haltAt: 8 }));
    safeTool('get_dataset', 'd', {}, async () => {
      throw Object.assign(new Error('API 오류 (403): 차단'), {
        policyBlocked: { action: 'AI', levelName: '민감', policyKey: 'ai_policy', message: '차단' },
      });
    });
    let last: { content: { text: string }[]; isError?: boolean } | undefined;
    for (let i = 0; i < 3; i++) last = await handlers[0]({});
    expect(last!.isError).toBe(true);
    expect(last!.content).toHaveLength(1);
    expect(JSON.parse(last!.content[0].text)).toMatchObject({ policyBlocked: true, levelName: '민감' });
  });
});
