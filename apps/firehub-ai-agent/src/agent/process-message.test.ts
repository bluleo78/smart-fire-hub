import { describe, it, expect, vi, beforeEach } from 'vitest';
import { processMessage } from './process-message.js';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { MAX_BUDGET_USD } from '../constants.js';

// console 출력 억제
beforeEach(() => {
  vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

const tag = () => '[Test]';

describe('processMessage', () => {
  // PM-01: system/init — init 이벤트 반환
  it('PM-01: system/init returns init event with sessionId', () => {
    const msg = {
      type: 'system',
      subtype: 'init',
      session_id: 'sess-abc',
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'init', sessionId: 'sess-abc' }]);
  });

  // PM-02: system/compact_boundary — compaction completed 이벤트 반환
  it('PM-02: system/compact_boundary returns compaction completed event', () => {
    const msg = {
      type: 'system',
      subtype: 'compact_boundary',
      compact_metadata: { trigger: 'auto', pre_tokens: 90000 },
      session_id: 'sess-abc',
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{
      type: 'compaction',
      status: 'completed',
      trigger: 'auto',
      preTokens: 90000,
    }]);
  });

  // PM-03: system/status(compacting) — compaction started 이벤트 반환
  it('PM-03: system/status compacting returns compaction started event', () => {
    const msg = {
      type: 'system',
      subtype: 'status',
      status: 'compacting',
      session_id: 'sess-abc',
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'compaction', status: 'started' }]);
  });

  // PM-04: system/status(other) — 빈 배열 반환
  it('PM-04: system/status other than compacting returns empty array', () => {
    const msg = {
      type: 'system',
      subtype: 'status',
      status: 'ready',
      session_id: 'sess-abc',
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([]);
  });

  // PM-05: assistant/text(not streamed) — hasStreamedText=false이면 text 이벤트 반환
  it('PM-05: assistant text block emits text event when hasStreamedText=false', () => {
    const msg = {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'hello world' }] },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'text', content: 'hello world' }]);
  });

  // PM-06: assistant/text(streamed) — hasStreamedText=true이면 빈 배열 반환 (중복 방지)
  it('PM-06: assistant text block returns empty array when hasStreamedText=true', () => {
    const msg = {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'hello world' }] },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, true);

    expect(result).toEqual([]);
  });

  // PM-07: assistant/tool_use — tool_use 이벤트 반환
  it('PM-07: assistant tool_use block emits tool_use event with name and input', () => {
    const msg = {
      type: 'assistant',
      message: {
        content: [{ type: 'tool_use', name: 'list_categories', input: { page: 1 } }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'tool_use', toolName: 'list_categories', input: { page: 1 } }]);
  });

  // PM-08: user/tool_result(string content) — tool_result 이벤트 반환
  it('PM-08: user tool_result with string content emits tool_result event', () => {
    const msg = {
      type: 'user',
      message: {
        content: [{ type: 'tool_result', tool_use_id: 'id-1', content: 'ok' }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    // #206/#265: tool_result 페이로드는 항상 isError 필드를 포함한다 (safeTool 에러 전파용)
    expect(result).toEqual([{ type: 'tool_result', toolName: 'id-1', result: 'ok', isError: false }]);
  });

  // PM-09: user/tool_result(array content) — 텍스트 블록들을 \n으로 결합
  it('PM-09: user tool_result with array content joins text lines with newline', () => {
    const msg = {
      type: 'user',
      message: {
        content: [{
          type: 'tool_result',
          tool_use_id: 'id-2',
          content: [
            { type: 'text', text: 'line1' },
            { type: 'text', text: 'line2' },
          ],
        }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'tool_result', toolName: 'id-2', result: 'line1\nline2', isError: false }]);
  });

  // PM-09b: safeTool 에러 path — is_error: true → isError: true 전파 (refs #206/#265)
  it('PM-09b: user tool_result with is_error=true sets isError=true', () => {
    const msg = {
      type: 'user',
      message: {
        content: [{
          type: 'tool_result',
          tool_use_id: 'id-err',
          content: 'tool failed: 404',
          is_error: true,
        }],
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([
      { type: 'tool_result', toolName: 'id-err', result: 'tool failed: 404', isError: true },
    ]);
  });

  // PM-10: result/success — done 이벤트에 inputTokens + outputTokens 포함
  it('PM-10: result success emits done event with inputTokens and outputTokens', () => {
    const msg = {
      type: 'result',
      subtype: 'success',
      session_id: 'sess-done',
      usage: {
        input_tokens: 100,
        output_tokens: 50,
        cache_read_input_tokens: 10,
        cache_creation_input_tokens: 5,
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    // totalInputTokens = 100 + 10 + 5 = 115, totalOutputTokens = 50
    expect(result).toEqual([{
      type: 'done',
      sessionId: 'sess-done',
      inputTokens: 115,
      outputTokens: 50,
    }]);
  });

  // PM-11: result/error — error 이벤트에 inputTokens + outputTokens 포함
  it('PM-11: result error emits error event with joined errors, inputTokens and outputTokens', () => {
    const msg = {
      type: 'result',
      subtype: 'error',
      session_id: 'sess-err',
      errors: ['err1', 'err2'],
      usage: {
        input_tokens: 200,
        output_tokens: 30,
        cache_read_input_tokens: 0,
        cache_creation_input_tokens: 0,
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{
      type: 'error',
      message: 'err1; err2',
      sessionId: 'sess-err',
      inputTokens: 200,
      outputTokens: 30,
    }]);
  });

  // PM-12: stream_event/text_delta — text 이벤트 반환 (스트리밍 청크)
  it('PM-12: stream_event content_block_delta text_delta emits text event', () => {
    const msg = {
      type: 'stream_event',
      event: {
        type: 'content_block_delta',
        delta: { type: 'text_delta', text: 'streaming chunk' },
      },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([{ type: 'text', content: 'streaming chunk' }]);
  });

  // PM-BUDGET: result error_max_budget_usd → 예산 한도 전용 error 이벤트 반환
  it('PM-BUDGET: result error_max_budget_usd → 예산 한도 error 이벤트', () => {
    const msg = {
      type: 'result',
      subtype: 'error_max_budget_usd',
      session_id: 'sess-b',
      usage: { input_tokens: 10, output_tokens: 5 },
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toHaveLength(1);
    expect(result[0].type).toBe('error');
    expect(String(result[0].message)).toContain('비용 한도');
    expect(String(result[0].message)).toContain(String(MAX_BUDGET_USD));
  });

  // PM-13: unknown type — 빈 배열 반환
  it('PM-13: unknown message type returns empty array', () => {
    const msg = {
      type: 'totally_unknown',
    } as unknown as SDKMessage;

    const result = processMessage(msg, tag, false);

    expect(result).toEqual([]);
  });
});
