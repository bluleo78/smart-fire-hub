/**
 * stdio-server.ts main() 의 세션 사용자 권한 게이팅 테스트 (WD-63).
 *
 * <p>왜 별도 파일인가: stdio-server.test.ts 는 registerAllTools 를 모킹한다 — 그러면 "권한 배열이
 * 인자로 넘어갔다"만 증명할 뿐, 실제로 McpServer 에 무엇이 등록되는지는 확인하지 못한다. stdio 경로는
 * safeTool 이 server.tool() 을 즉시 호출하므로 registerAllTools 의 반환 배열 필터는 등록 결과에 아무
 * 영향이 없다. 그래서 여기서는 실물 registerAllTools 를 쓰고 McpServer.tool 에 실제로 등록된 도구
 * 이름을 단언한다(CLI·opencode 가 tools/list 로 받는 목록과 같다).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const registeredToolNames: string[] = [];
const mcpServerConnectMock = vi.fn().mockResolvedValue(undefined);
vi.mock('@modelcontextprotocol/sdk/server/mcp.js', () => ({
  McpServer: vi.fn(function McpServer() {
    return {
      tool: (name: string) => {
        registeredToolNames.push(name);
      },
      connect: mcpServerConnectMock,
    };
  }),
}));

vi.mock('@modelcontextprotocol/sdk/server/stdio.js', () => ({
  StdioServerTransport: vi.fn(function StdioServerTransport() {
    return { send: vi.fn() };
  }),
}));

// 실물 FireHubApiClient 를 쓰되(도구 등록이 withPurpose 등 실제 메서드를 부른다) 권한 조회만 가로챈다.
// 등록 시점에는 네트워크 호출이 없으므로 실물 생성자로 충분하다.
const getSessionPermissionsMock = vi.fn<(timeoutMs?: number) => Promise<string[]>>();

/** 권한을 요구하는 대표 파괴 도구와, 권한 요구가 없는 일반 도구 */
const DESTRUCTIVE = ['delete_dataset', 'drop_dataset_column', 'set_user_roles', 'set_user_active'];
const UNGATED = 'list_datasets';

describe('stdio-server main() — 세션 사용자 권한으로 도구 등록 게이팅 (WD-63)', () => {
  const originalEnv = { ...process.env };
  // main() 이 console.log/info/debug 를 stderr 로 돌리므로 테스트마다 원복한다
  const originalConsole = { log: console.log, info: console.info, debug: console.debug };

  beforeEach(async () => {
    vi.clearAllMocks();
    registeredToolNames.length = 0;
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    process.env.USER_ID = '7';
    process.env.TENANT_ID = '3';
    const { FireHubApiClient } = await import('./api-client.js');
    vi.spyOn(FireHubApiClient.prototype, 'getSessionPermissions').mockImplementation((timeoutMs) =>
      getSessionPermissionsMock(timeoutMs),
    );
  });

  afterEach(() => {
    Object.assign(console, originalConsole);
    process.env = { ...originalEnv };
    vi.restoreAllMocks();
  });

  it('필요 권한을 가진 사용자에게는 파괴 도구가 등록된다', async () => {
    getSessionPermissionsMock.mockResolvedValue([
      'dataset:read',
      'dataset:write',
      'dataset:delete',
      'user:read',
      'user:write',
      'role:assign',
    ]);
    const { main } = await import('./stdio-server.js');
    await main();

    expect(getSessionPermissionsMock).toHaveBeenCalledTimes(1);
    // 핸드셰이크 앞 조회라 opencode 의 MCP 도구 조회 대기(5초)보다 짧은 타임아웃을 건다
    expect(getSessionPermissionsMock).toHaveBeenCalledWith(3_000);
    for (const name of DESTRUCTIVE) expect(registeredToolNames).toContain(name);
    expect(registeredToolNames).toContain(UNGATED);
  });

  it('권한이 없는 사용자에게는 파괴 도구가 등록되지 않고, 권한 요구 없는 도구는 남는다', async () => {
    getSessionPermissionsMock.mockResolvedValue(['dataset:read', 'data:read']);
    const { main } = await import('./stdio-server.js');
    await main();

    for (const name of DESTRUCTIVE) expect(registeredToolNames).not.toContain(name);
    // 보유 권한으로 허용되는 게이트 도구와 비게이트 도구는 그대로 노출된다(과잉 차단 방지)
    expect(registeredToolNames).toContain('find_datasets');
    expect(registeredToolNames).toContain(UNGATED);
    expect(mcpServerConnectMock).toHaveBeenCalledTimes(1);
  });

  it('권한 조회가 실패하면 fail-closed — 권한 요구 도구는 전부 빠지고 서버는 정상 기동한다', async () => {
    getSessionPermissionsMock.mockRejectedValue(new Error('network down'));
    const { main } = await import('./stdio-server.js');
    await expect(main()).resolves.toBeUndefined();

    for (const name of [...DESTRUCTIVE, 'find_datasets', 'graphrag_approve_review_item']) {
      expect(registeredToolNames).not.toContain(name);
    }
    expect(registeredToolNames).toContain(UNGATED);
    expect(mcpServerConnectMock).toHaveBeenCalledTimes(1);
  });

  it('main() 이후 console.log/info 는 stderr(console.error)로 간다 — stdout 은 JSON-RPC 전용', async () => {
    getSessionPermissionsMock.mockResolvedValue([]);
    const { main } = await import('./stdio-server.js');
    await main();

    console.log('[MCP API] GET /auth/me/permissions');
    console.info('info-line');
    expect(console.error).toHaveBeenCalledWith('[MCP API] GET /auth/me/permissions');
    expect(console.error).toHaveBeenCalledWith('info-line');
  });
});
