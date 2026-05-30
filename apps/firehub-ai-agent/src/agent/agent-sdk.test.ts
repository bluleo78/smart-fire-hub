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
  FireHubApiClient: vi.fn().mockImplementation(function (this: Record<string, unknown>) {
    this.getSessionPermissions = vi.fn().mockResolvedValue([]);
    return this;
  }),
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

    // #206/#265: tool_result 페이로드는 항상 isError 필드를 포함한다 (safeTool 에러 전파용)
    expect(result).toEqual([
      { type: 'tool_result', toolName: 'tool1', result: 'result text', isError: false },
    ]);
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

    expect(result).toEqual([
      { type: 'tool_result', toolName: 'tool2', result: 'line1\nline2', isError: false },
    ]);
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

    expect(result).toEqual([
      { type: 'tool_result', toolName: 'tool3', result: undefined, isError: false },
    ]);
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

    // totalInputTokens = 100 + 10 + 5 = 115, totalOutputTokens = 50
    expect(result).toEqual([{ type: 'done', sessionId: 'sess-1', inputTokens: 115, outputTokens: 50 }]);
  });

  // AS-11: result error returns error event with joined messages
  it('AS-11: result error returns error event with joined error messages', () => {
    const msg = {
      type: 'result',
      subtype: 'error',
      errors: ['err1', 'err2'],
    } as unknown as SDKMessage;

    const result = processMessage(msg, mockTag, false);

    expect(result).toEqual([{ type: 'error', message: 'err1; err2', sessionId: undefined, inputTokens: 0, outputTokens: 0 }]);
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

    // totalInputTokens = 200 + 20 + 0 = 220, totalOutputTokens = 80
    expect(result).toEqual([{ type: 'done', sessionId: 'sess-2', inputTokens: 220, outputTokens: 80 }]);
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

  // AS-21 (#216): deferred-tools(ToolSearch 메타 호출) 강제 비활성 검증.
  //
  // 1차 fix(f81beb89): settingSources:[] 만 적용 → 회귀 발생.
  // 2차 fix: options.env.ENABLE_TOOL_SEARCH=false 추가 → 여전히 회귀.
  //   원인 — SDK 의 E96()/getExternalMcpMode(cli.js dm8) 는 query() 옵션의 env 가
  //   아닌 **호스트 process.env.ENABLE_TOOL_SEARCH** 를 직접 읽는다.
  //   따라서 options.env 는 Bash subprocess 환경에만 영향, SDK 내부 모드 결정
  //   에는 영향 없음 → "tst-auto" 기본 모드 유지 → 임계치 도달 시 ToolSearch 발생.
  // 3차 fix: 모듈 로드 시점에 process.env 를 직접 설정 (agent-sdk.ts top-level).
  //
  // SDK 내부 isToolSearchEnabled 는 다음 두 경로에서 disable:
  //   (a) process.env.ENABLE_TOOL_SEARCH ∈ {"false","0","off","no"} → "standard"
  //   (b) disallowedTools 에 "ToolSearch" 포함 → XOq 체크에서 즉시 false
  // 본 테스트는 (a) process.env 가 실제 설정되었는지, (b) options.env 와
  // disallowedTools 가 SDK 에 전달되는지를 모두 검증한다.
  it('AS-21: deferred-tools 강제 비활성 (env+disallowedTools 이중 차단, #216)', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    async function* fakeStream() {
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-settings',
        usage: {
          input_tokens: 1,
          output_tokens: 1,
          cache_read_input_tokens: 0,
          cache_creation_input_tokens: 0,
        },
      };
    }
    mockQuery.mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    for await (const _event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test-key',
    })) {
      // drain
    }

    expect(mockQuery).toHaveBeenCalledOnce();
    const callArgs = mockQuery.mock.calls[0][0] as {
      options?: {
        settingSources?: string[];
        disallowedTools?: string[];
        env?: Record<string, string>;
      };
    };
    // (구 검증 유지) ~/.claude 상속 차단
    expect(callArgs.options?.settingSources).toEqual([]);
    // (a) **process.env** — SDK E96()/dm8 이 직접 읽는 핵심 신호.
    //     모듈 로드 시점에 agent-sdk.ts top-level 에서 설정되어야 한다.
    expect(process.env.ENABLE_TOOL_SEARCH).toBe('false');
    expect(process.env.ENABLE_EXPERIMENTAL_MCP_CLI).toBe('false');
    // (a-2) options.env 도 함께 설정되어야 Bash subprocess 환경 일관성 보장
    expect(callArgs.options?.env?.ENABLE_TOOL_SEARCH).toBe('false');
    expect(callArgs.options?.env?.ENABLE_EXPERIMENTAL_MCP_CLI).toBe('false');
    // (b) ToolSearch 자체를 disallowedTools 로 명시적 차단 (이중 안전망)
    expect(callArgs.options?.disallowedTools).toContain('ToolSearch');
  });

  // AS-22 (#256): 메인 에이전트 host 도구 화이트리스트/블랙리스트 강제.
  //
  // 배경: ai-driven-agent-inspector 라운드 9 trace(skill-repro-010.sse)에서
  // 메인 에이전트가 `Skill(superpowers:brainstorming)`, `TaskCreate`,
  // `TaskUpdate` 를 자유 호출. allowedTools 에 host filesystem/shell/web 도구가
  // 광범위하게 포함돼 있었고 disallowedTools 에 Skill/Task* 명시 차단 누락.
  //
  // 본 테스트는:
  //   (a) allowedTools 가 mcp__firehub__* + Agent 로 좁혀졌는지
  //   (b) disallowedTools 에 Skill/TaskCreate/TaskUpdate/TaskList/TaskGet/
  //       TaskStop/TaskOutput + Read/Write/Edit/NotebookEdit/Bash/Glob/Grep/LS +
  //       WebFetch/WebSearch + ToolSearch/mcp__claude-search__* 가 포함됐는지
  // 를 검증해 회귀를 방지한다.
  it('AS-22: 메인 에이전트 host 도구 화이트리스트/블랙리스트 강제 (#256)', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    async function* fakeStream() {
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-tools',
        usage: {
          input_tokens: 1,
          output_tokens: 1,
          cache_read_input_tokens: 0,
          cache_creation_input_tokens: 0,
        },
      };
    }
    mockQuery.mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    for await (const _event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test-key',
    })) {
      // drain
    }

    expect(mockQuery).toHaveBeenCalledOnce();
    const callArgs = mockQuery.mock.calls[0][0] as {
      options?: {
        allowedTools?: string[];
        disallowedTools?: string[];
      };
    };

    const disallowed = callArgs.options?.disallowedTools ?? [];

    // #266: allowedTools 는 미전달 (allow-by-default). disallowedTools 만으로 명시 차단.
    expect(callArgs.options?.allowedTools).toBeUndefined();

    // strict 모드 기본값(테스트 환경) 기준: 호스트 파일 변조 / skill·task ecosystem /
    // meta-search 가 명시 차단된다. WebFetch/WebSearch/Read/Bash/Glob/Grep/LS/
    // AskUserQuestion 등은 차단 대상 아님 (#266).
    for (const blocked of [
      'Write',
      'Edit',
      'NotebookEdit',
      'Skill',
      'TaskCreate',
      'TaskUpdate',
      'TaskList',
      'TaskGet',
      'TaskStop',
      'TaskOutput',
      'ToolSearch',
      'mcp__claude-search__*',
    ]) {
      expect(disallowed).toContain(blocked);
    }
    for (const allowed of ['WebFetch', 'WebSearch', 'AskUserQuestion', 'Glob', 'Grep', 'LS', 'TodoWrite']) {
      expect(disallowed).not.toContain(allowed);
    }
  });

  // AS-23: 동일 도구가 동일 오류로 8회 연속 실패하면 Tier2 강제중단
  it('AS-23: 동일 도구가 동일 오류로 8회 실패하면 강제중단 + error emit', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    // tool_use + tool_result 페어를 10회 반복 — 8회째에서 halt가 트리거되어야 한다
    async function* fakeStream() {
      for (let i = 0; i < 10; i++) {
        // assistant: tool_use 블록
        yield {
          type: 'assistant',
          message: {
            content: [{ type: 'tool_use', name: 'execute_sql_query', input: { sql: `SELECT c${i}` } }],
          },
        };
        // user: tool_result 블록 (isError=true, 동일 오류 메시지)
        yield {
          type: 'user',
          message: {
            content: [{
              type: 'tool_result',
              tool_use_id: `tu_${i}`,
              content: 'column "x" does not exist',
              is_error: true,
            }],
          },
        };
      }
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-halt',
        usage: { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      };
    }
    mockQuery.mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    const events: { type: string; message?: unknown }[] = [];
    for await (const event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test-key',
    })) {
      events.push(event as { type: string; message?: unknown });
    }

    const errs = events.filter((e) => e.type === 'error');
    expect(errs.length).toBeGreaterThanOrEqual(1);
    expect(String(errs[0].message)).toContain('execute_sql_query');
    // 10회 전에 중단되어야 한다
    expect(events.filter((e) => e.type === 'tool_result').length).toBeLessThan(10);
  });

  // AS-24: 성공이 섞이면 streak 리셋 → 강제중단 없음
  it('AS-24: 성공이 섞이면 리셋되어 중단되지 않음', async () => {
    const { query } = await import('@anthropic-ai/claude-agent-sdk');
    const mockQuery = vi.mocked(query);

    // 14회 중 매 3번째(i % 3 === 2)만 성공 → streak 계속 리셋됨
    async function* fakeStream() {
      for (let i = 0; i < 14; i++) {
        yield {
          type: 'assistant',
          message: {
            content: [{ type: 'tool_use', name: 'execute_sql_query', input: { sql: 'q' } }],
          },
        };
        const ok = i % 3 === 2;
        yield {
          type: 'user',
          message: {
            content: [{
              type: 'tool_result',
              tool_use_id: `tu_${i}`,
              content: ok ? 'ok' : 'column "x" does not exist',
              is_error: !ok,
            }],
          },
        };
      }
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-mix',
        usage: { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      };
    }
    mockQuery.mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    const events: { type: string }[] = [];
    for await (const event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test-key',
    })) {
      events.push(event as { type: string });
    }

    expect(events.filter((e) => e.type === 'error').length).toBe(0);
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

/**
 * Task 9: 세션 사용자 권한 조회 → MCP 도구 필터링 연동 테스트.
 *
 * fetchSessionPermissionsFailClosed 가 성공 시 권한 배열을 그대로 반환하고,
 * 실패 시 `[]` 로 폴백(fail-closed)하는지 검증한다. 또한 executeAgent 실행 경로에서
 * 권한 조회가 실패해도 에이전트가 정상 기동하고, createFireHubMcpServer 에
 * `userPermissions: []` 가 전달되어 T8 필터가 파괴 도구를 차단하도록 한다.
 */
describe('fetchSessionPermissionsFailClosed (Task 9)', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  it('AS-T9-1: returns permission codes on success', async () => {
    const { fetchSessionPermissionsFailClosed } = await import('./agent-sdk.js');
    const apiClient = {
      getSessionPermissions: vi.fn().mockResolvedValue(['dataset:read', 'dataset:delete']),
    } as unknown as import('../mcp/api-client.js').FireHubApiClient;

    const result = await fetchSessionPermissionsFailClosed(apiClient);
    expect(result).toEqual(['dataset:read', 'dataset:delete']);
  });

  it('AS-T9-2: returns [] (fail-closed) when backend errors', async () => {
    const { fetchSessionPermissionsFailClosed } = await import('./agent-sdk.js');
    const apiClient = {
      getSessionPermissions: vi.fn().mockRejectedValue(new Error('API 오류 (500): boom')),
    } as unknown as import('../mcp/api-client.js').FireHubApiClient;

    const result = await fetchSessionPermissionsFailClosed(apiClient);
    // 중요: undefined 가 아닌 빈 배열이어야 T8 필터가 파괴 도구를 차단(fail-closed)한다
    expect(result).toEqual([]);
    expect(result).not.toBeUndefined();
  });

  it('AS-T9-3: executeAgent still runs and passes userPermissions=[] to MCP server on permission fetch failure', async () => {
    const { FireHubApiClient } = await import('../mcp/api-client.js');
    const { createFireHubMcpServer } = await import('../mcp/firehub-mcp-server.js');
    const { query } = await import('@anthropic-ai/claude-agent-sdk');

    // FireHubApiClient 인스턴스의 getSessionPermissions 가 500 으로 실패하도록 재구성
    vi.mocked(FireHubApiClient).mockImplementationOnce(function (this: Record<string, unknown>) {
      this.getSessionPermissions = vi.fn().mockRejectedValue(new Error('API 오류 (500): boom'));
      return this as unknown as import('../mcp/api-client.js').FireHubApiClient;
    } as unknown as typeof FireHubApiClient);

    // 최소한의 성공 스트림
    async function* fakeStream() {
      yield {
        type: 'result',
        subtype: 'success',
        session_id: 'sess-t9',
        usage: {
          input_tokens: 1,
          output_tokens: 1,
          cache_read_input_tokens: 0,
          cache_creation_input_tokens: 0,
        },
      };
    }
    vi.mocked(query).mockReturnValue(fakeStream() as unknown as ReturnType<typeof query>);

    const { executeAgent } = await import('./agent-sdk.js');

    const events: unknown[] = [];
    for await (const event of executeAgent({
      message: 'hello',
      userId: 1,
      apiKey: 'sk-test',
    })) {
      events.push(event);
    }

    // 에이전트는 여전히 실행되어 done 이벤트를 방출해야 한다 (권한 조회 실패가 치명적이지 않음)
    expect(events.some((e) => (e as { type: string }).type === 'done')).toBe(true);
    expect(events.every((e) => (e as { type: string }).type !== 'error')).toBe(true);

    // createFireHubMcpServer 는 userPermissions: [] 로 호출되어야 한다 (fail-closed)
    expect(createFireHubMcpServer).toHaveBeenCalled();
    const callArgs = vi.mocked(createFireHubMcpServer).mock.calls.at(-1);
    expect(callArgs).toBeDefined();
    // 두 번째 인자는 { userPermissions: [] } 여야 한다
    const options = callArgs?.[1] as { userPermissions?: string[] } | undefined;
    expect(options?.userPermissions).toEqual([]);
  });
});
