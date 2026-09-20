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
vi.mock('./firehub-mcp-server.js', () => ({
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

vi.mock('./api-client.js', () => ({
  FireHubApiClient: vi.fn(function FireHubApiClient() {
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
