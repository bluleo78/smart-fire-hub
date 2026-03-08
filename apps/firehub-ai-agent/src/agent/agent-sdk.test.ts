import { describe, it, expect, vi, beforeEach } from 'vitest';
import { processMessage } from './process-message.js';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';

// Mock dependencies needed for executeAgent
vi.mock('@anthropic-ai/claude-agent-sdk', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@anthropic-ai/claude-agent-sdk')>();
  return {
    ...actual,
    query: vi.fn(),
  };
});

vi.mock('../mcp/api-client.js', () => ({
  FireHubApiClient: vi.fn().mockImplementation(function () { return {}; }),
}));

vi.mock('../mcp/firehub-mcp-server.js', () => ({
  createFireHubMcpServer: vi.fn().mockReturnValue({}),
}));

const mockTag = () => '[Test]';

// Suppress console output during tests
beforeEach(() => {
  vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('processMessage', () => {
  // AS-01: system init message returns init event
  it('AS-01: system init message returns init event', () => {
    const msg = {
      type: 'system',
      subtype: 'init',
      session_id: 'sess-1',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'init', sessionId: 'sess-1' }]);
  });

  // AS-02: system non-init message returns empty
  it('AS-02: system non-init message returns empty array', () => {
    const msg = {
      type: 'system',
      subtype: 'something',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([]);
  });

  // AS-16: system compact_boundary returns compaction completed event
  it('AS-16: system compact_boundary returns compaction completed event', () => {
    const msg = {
      type: 'system',
      subtype: 'compact_boundary',
      compact_metadata: { trigger: 'auto', pre_tokens: 120000 },
      uuid: 'test-uuid',
      session_id: 'sess-1',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{
      type: 'compaction',
      status: 'completed',
      trigger: 'auto',
      preTokens: 120000,
    }]);
  });

  // AS-17: system status compacting returns compaction started event
  it('AS-17: system status compacting returns compaction started event', () => {
    const msg = {
      type: 'system',
      subtype: 'status',
      status: 'compacting',
      uuid: 'test-uuid',
      session_id: 'sess-1',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{
      type: 'compaction',
      status: 'started',
    }]);
  });

  // AS-18: system status null returns empty array
  it('AS-18: system status null returns empty array', () => {
    const msg = {
      type: 'system',
      subtype: 'status',
      status: null,
      uuid: 'test-uuid',
      session_id: 'sess-1',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([]);
  });

  // AS-03: assistant text block when not streamed
  it('AS-03: assistant text block emits text event when hasStreamedText=false', () => {
    const msg = {
      type: 'assistant',
      message: {
        content: [{ type: 'text', text: 'hello' }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'text', content: 'hello' }]);
  });

  // AS-04: assistant text block when already streamed returns empty
  it('AS-04: assistant text block returns empty when hasStreamedText=true', () => {
    const msg = {
      type: 'assistant',
      message: {
        content: [{ type: 'text', text: 'hello' }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, true);

    expect(result).toEqual([]);
  });

  // AS-05: assistant tool_use block
  it('AS-05: assistant tool_use block emits tool_use event', () => {
    const msg = {
      type: 'assistant',
      message: {
        content: [{ type: 'tool_use', name: 'list_categories', input: {} }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'tool_use', toolName: 'list_categories', input: {} }]);
  });

  // AS-06: assistant mixed text+tool_use blocks produces 2 events in order
  it('AS-06: assistant mixed text and tool_use blocks emit events in order', () => {
    const msg = {
      type: 'assistant',
      message: {
        content: [
          { type: 'text', text: 'I will call a tool' },
          { type: 'tool_use', name: 'list_categories', input: { page: 1 } },
        ],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ type: 'text', content: 'I will call a tool' });
    expect(result[1]).toEqual({
      type: 'tool_use',
      toolName: 'list_categories',
      input: { page: 1 },
    });
  });

  // AS-07: user tool_result with string content
  it('AS-07: user tool_result with string content emits tool_result event', () => {
    const msg = {
      type: 'user',
      message: {
        content: [{ type: 'tool_result', tool_use_id: 'tool1', content: 'result text' }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'tool_result', toolName: 'tool1', result: 'result text' }]);
  });

  // AS-08: user tool_result with array content joins text fields with \n
  it('AS-08: user tool_result with array content joins text fields with newline', () => {
    const msg = {
      type: 'user',
      message: {
        content: [
          {
            type: 'tool_result',
            tool_use_id: 'tool2',
            content: [
              { type: 'text', text: 'line1' },
              { type: 'text', text: 'line2' },
            ],
          },
        ],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'tool_result', toolName: 'tool2', result: 'line1\nline2' }]);
  });

  // AS-09: user tool_result with undefined content
  it('AS-09: user tool_result with no content field has result=undefined', () => {
    const msg = {
      type: 'user',
      message: {
        content: [{ type: 'tool_result', tool_use_id: 'tool3' }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'tool_result', toolName: 'tool3', result: undefined }]);
  });

  // AS-10: result success returns done event with correct inputTokens
  it('AS-10: result success returns done event with summed inputTokens', () => {
    const msg = {
      type: 'result',
      subtype: 'success',
      session_id: 'sess-1',
      usage: {
        input_tokens: 100,
        output_tokens: 50,
        cache_read_input_tokens: 10,
        cache_creation_input_tokens: 5,
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    // totalInputTokens = 100 + 10 + 5 = 115
    expect(result).toEqual([{ type: 'done', sessionId: 'sess-1', inputTokens: 115 }]);
  });

  // AS-11: result error returns error event with joined messages
  it('AS-11: result error returns error event with joined error messages', () => {
    const msg = {
      type: 'result',
      subtype: 'error',
      errors: ['err1', 'err2'],
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'error', message: 'err1; err2', sessionId: undefined, inputTokens: 0 }]);
  });

  // AS-12: result with modelUsage logs correctly and still returns events
  it('AS-12: result success with modelUsage still returns done event correctly', () => {
    const msg = {
      type: 'result',
      subtype: 'success',
      session_id: 'sess-2',
      usage: {
        input_tokens: 200,
        output_tokens: 80,
        cache_read_input_tokens: 20,
        cache_creation_input_tokens: 0,
      },
      modelUsage: {
        'claude-sonnet-4-6': {
          inputTokens: 200,
          outputTokens: 80,
          cacheReadInputTokens: 20,
          cacheCreationInputTokens: 0,
        },
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    // totalInputTokens = 200 + 20 + 0 = 220
    expect(result).toEqual([{ type: 'done', sessionId: 'sess-2', inputTokens: 220 }]);
  });

  // AS-13: stream_event text_delta returns text event
  it('AS-13: stream_event content_block_delta with text_delta returns text event', () => {
    const msg = {
      type: 'stream_event',
      event: {
        type: 'content_block_delta',
        delta: { type: 'text_delta', text: 'chunk' },
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'text', content: 'chunk' }]);
  });

  // AS-14: stream_event message_delta returns empty array
  it('AS-14: stream_event message_delta returns empty array', () => {
    const msg = {
      type: 'stream_event',
      event: { type: 'message_delta' },
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([]);
  });

  // AS-15: unknown message type returns empty array
  it('AS-15: unknown message type returns empty array', () => {
    const msg = {
      type: 'something_else',
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([]);
  });
});

describe('executeAgent', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    // Reset env
    delete process.env.ANTHROPIC_API_KEY;
  });

  // AS-19: apiKey option is written into cleanEnv.ANTHROPIC_API_KEY
  it('AS-19: sets ANTHROPIC_API_KEY from apiKey option and yields done event', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    // Simulate a minimal successful SDK stream: system init → result success
    async function* fakeStream() {
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-api-key',
        usage: {
          input_tokens: 10,
          output_tokens: 5,
          cache_read_input_tokens: 0,
          cache_creation_input_tokens: 0,
        },
      };
    }
    mockQuery.mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    const events: unknown[] = [];
    for await (const event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test-key',
    })) {
      events.push(event);
    }

    // Verify query was called with env containing the provided apiKey
    expect(mockQuery).toHaveBeenCalledOnce();
    const callArgs = mockQuery.mock.calls[0][0] as { options?: { env?: Record<string, string> } };
    expect(callArgs.options?.env?.ANTHROPIC_API_KEY).toBe('sk-test-key');

    // Verify at least a done event was yielded (not an error)
    expect(events.some((e) => (e as { type: string }).type === 'done')).toBe(true);
    expect(events.every((e) => (e as { type: string }).type !== 'error')).toBe(true);
  });

  // AS-20: missing apiKey and no env var yields error event immediately
  it('AS-20: yields error event when apiKey is missing and ANTHROPIC_API_KEY env var is unset', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    const { executeAgent } = await import('./agent-sdk.js');

    const events: unknown[] = [];
    for await (const event of executeAgent({
      message: 'hello',
      userId: 1,
      // no apiKey
    })) {
      events.push(event);
    }

    // query should never be called — we bail out before reaching it
    expect(mockQuery).not.toHaveBeenCalled();

    // Should yield exactly one error event
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: 'error' });
  });
});
