import { describe, it, expect, vi } from 'vitest';

const executeAgentMock = vi.fn();
vi.mock('../agent/agent-sdk.js', () => ({ executeAgent: (o: unknown) => executeAgentMock(o) }));

import { ClaudeSdkChatProvider } from './claude-sdk-chat-provider.js';

// WD-79: 방언이 provider 를 거쳐 AgentOptions 까지 전달되는지 — 전달이 빠지면 SDK 경로에서만 Tibero 도구 숨김이 사라진다.
describe('ClaudeSdkChatProvider — dbDialect 전달 (WD-79)', () => {
  const run = async (dbDialect?: 'TIBERO' | 'POSTGRESQL') => {
    executeAgentMock.mockReset();
    executeAgentMock.mockReturnValue((async function* () {})());
    const p = new ClaudeSdkChatProvider('k', 'm');
    for await (const e of p.execute({ message: 'hi', tenantId: 1, userId: 1, dbDialect })) void e;
    return executeAgentMock.mock.calls[0][0] as { dbDialect?: string };
  };

  it('TIBERO 를 AgentOptions.dbDialect 로 넘긴다', async () => {
    expect((await run('TIBERO')).dbDialect).toBe('TIBERO');
  });
  it('없으면 undefined', async () => {
    expect((await run()).dbDialect).toBeUndefined();
  });
});
