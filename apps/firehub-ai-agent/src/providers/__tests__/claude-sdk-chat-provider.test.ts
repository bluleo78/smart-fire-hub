import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { SSEEvent } from '../types.js';
import { makeStream } from './test-helpers.js';

vi.mock('../../agent/agent-sdk.js', () => ({
  executeAgent: vi.fn(),
}));

import { ClaudeSdkChatProvider } from '../claude-sdk-chat-provider.js';
import { executeAgent } from '../../agent/agent-sdk.js';
import { DEFAULT_MODEL } from '../../constants.js';

const mockExecuteAgent = vi.mocked(executeAgent);

describe('ClaudeSdkChatProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // SDK-01: execute() returns AsyncGenerator<SSEEvent>
  it('SDK-01: execute() returns an AsyncGenerator', async () => {
    mockExecuteAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeSdkChatProvider('sk-test', DEFAULT_MODEL);
    const gen = provider.execute({ message: 'hello', userId: 1 });

    expect(typeof gen[Symbol.asyncIterator]).toBe('function');

    const events: SSEEvent[] = [];
    for await (const e of gen) events.push(e);
    expect(events).toEqual([{ type: 'done' }]);
  });

  // SDK-02: execute() passes correct AgentOptions including apiKey from constructor
  it('SDK-02: passes correct AgentOptions to executeAgent (apiKey from constructor)', async () => {
    mockExecuteAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeSdkChatProvider('sk-from-constructor', DEFAULT_MODEL);
    for await (const _ of provider.execute({
      message: 'test message',
      userId: 42,
      sessionId: 'sess-1',
    })) { /* consume */ }

    expect(mockExecuteAgent).toHaveBeenCalledOnce();
    const called = mockExecuteAgent.mock.calls[0][0];
    expect(called.apiKey).toBe('sk-from-constructor');
    expect(called.message).toBe('test message');
    expect(called.userId).toBe(42);
    expect(called.sessionId).toBe('sess-1');
  });

  // SDK-03: model defaults to defaultModel when not provided in options
  it('SDK-03: model defaults to defaultModel when not provided in options', async () => {
    mockExecuteAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeSdkChatProvider('sk-test', 'my-default-model');
    for await (const _ of provider.execute({ message: 'hi', userId: 1 })) { /* consume */ }

    const called = mockExecuteAgent.mock.calls[0][0];
    expect(called.model).toBe('my-default-model');
  });

  // SDK-04: model from options overrides defaultModel
  it('SDK-04: model from options overrides defaultModel', async () => {
    mockExecuteAgent.mockReturnValue(makeStream([{ type: 'done' }]));

    const provider = new ClaudeSdkChatProvider('sk-test', DEFAULT_MODEL);
    for await (const _ of provider.execute({
      message: 'hi',
      userId: 1,
      model: 'claude-opus-4-6',
    })) { /* consume */ }

    const called = mockExecuteAgent.mock.calls[0][0];
    expect(called.model).toBe('claude-opus-4-6');
  });
});
