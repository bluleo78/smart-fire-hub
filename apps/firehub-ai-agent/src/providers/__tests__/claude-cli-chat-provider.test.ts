import { describe, it, expect, vi, beforeEach } from 'vitest';
import { makeStream } from './test-helpers.js';

vi.mock('../../agent/agent-cli.js', () => ({
  executeCliAgent: vi.fn(),
}));

import { ClaudeCliChatProvider } from '../claude-cli-chat-provider.js';
import { executeCliAgent } from '../../agent/agent-cli.js';

const mockExecuteCliAgent = vi.mocked(executeCliAgent);

describe('ClaudeCliChatProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // CLI-01: CLI subscription mode passes useSubscription=true
  it('CLI-01: CLI subscription mode passes useSubscription=true to executeCliAgent', async () => {
    mockExecuteCliAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeCliChatProvider(true);
    for await (const _ of provider.execute({ message: 'hello', userId: 1 })) { /* consume */ }

    expect(mockExecuteCliAgent).toHaveBeenCalledOnce();
    const called = mockExecuteCliAgent.mock.calls[0][0];
    expect(called.useSubscription).toBe(true);
    expect(called.apiKey).toBeUndefined();
  });

  // CLI-02: CLI API mode passes useSubscription=false and apiKey
  it('CLI-02: CLI API mode passes useSubscription=false and apiKey to executeCliAgent', async () => {
    mockExecuteCliAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeCliChatProvider(false, 'sk-cli-api-key');
    for await (const _ of provider.execute({ message: 'hello', userId: 2 })) { /* consume */ }

    expect(mockExecuteCliAgent).toHaveBeenCalledOnce();
    const called = mockExecuteCliAgent.mock.calls[0][0];
    expect(called.useSubscription).toBe(false);
    expect(called.apiKey).toBe('sk-cli-api-key');
  });

  // CLI-03: name is 'claude-cli' for subscription mode, 'claude-cli-api' for API mode
  it('CLI-03: name is claude-cli for subscription mode', () => {
    const cli = new ClaudeCliChatProvider(true);
    const cliApi = new ClaudeCliChatProvider(false, 'sk-test');
    expect(cli.name).toBe('claude-cli');
    expect(cliApi.name).toBe('claude-cli-api');
  });

  // CLI-04: ChatProviderOptions fields are mapped to CliAgentOptions correctly
  it('CLI-04: ChatProviderOptions fields are explicitly mapped to CliAgentOptions', async () => {
    mockExecuteCliAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeCliChatProvider(true, undefined, 'oauth-token');
    const options = {
      message: 'test',
      userId: 10,
      sessionId: 'sess-abc',
      model: 'claude-haiku-4-5-20251001',
      maxTurns: 5,
    };
    for await (const _ of provider.execute(options)) { /* consume */ }

    const called = mockExecuteCliAgent.mock.calls[0][0];
    expect(called.message).toBe('test');
    expect(called.userId).toBe(10);
    expect(called.sessionId).toBe('sess-abc');
    expect(called.model).toBe('claude-haiku-4-5-20251001');
    expect(called.maxTurns).toBe(5);
    expect(called.cliOauthToken).toBe('oauth-token');
    expect(called.useSubscription).toBe(true);
  });
});
