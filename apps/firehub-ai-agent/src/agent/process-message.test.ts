import { describe, it, expect, vi, beforeEach } from 'vitest';
import { processMessage, createDesignGuardRelayState } from './process-message.js';
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

  // #428: DESIGN 가드 subagent(pipeline-builder 등) 완료 후 메인의 중복 확인 재요약 억제
  describe('#428 DESIGN 가드 subagent 결과 중복 relay 억제', () => {
    // 위임 tool_use(Agent, subagent_type=pipeline-builder) 메시지 — parent_tool_use_id 없음(메인)
    const delegateMsg = (toolUseId: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: toolUseId,
              name: 'Agent',
              input: { subagent_type: 'pipeline-builder', prompt: 'Mode: DESIGN\n...' },
            },
          ],
        },
      }) as unknown as SDKMessage;

    // subagent 자신의 확인 질문 완료 메시지 — parent_tool_use_id = 위임 tool_use id
    const subagentConfirmMsg = (toolUseId: string, text: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: toolUseId,
        message: { content: [{ type: 'text', text }] },
      }) as unknown as SDKMessage;

    // 메인의 스트리밍 텍스트 델타 — parent_tool_use_id 없음(메인)
    const mainDeltaMsg = (text: string) =>
      ({
        type: 'stream_event',
        parent_tool_use_id: null,
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
      }) as unknown as SDKMessage;

    it('PM-428a: 위임 전 메인 델타는 그대로 통과한다', () => {
      const state = createDesignGuardRelayState();
      const result = processMessage(mainDeltaMsg('진행 중입니다'), tag, false, state);
      expect(result).toEqual([{ type: 'text', content: '진행 중입니다' }]);
    });

    it('PM-428b: subagent 가 확인 질문으로 완료하면 이후 메인 델타를 억제한다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_1';

      // 1) 메인이 pipeline-builder 에 위임
      processMessage(delegateMsg(toolUseId), tag, false, state);
      expect(state.pendingGuardToolUseIds.has(toolUseId)).toBe(true);

      // 2) subagent 가 확인 질문으로 자기 턴을 마침
      const confirmResult = processMessage(
        subagentConfirmMsg(toolUseId, '## 설계안 ...\n이대로 생성할까요? (예 / 수정 요청)'),
        tag,
        true, // 이미 델타로 스트리밍됐다고 가정 — 이 경로는 상태만 갱신
        state,
      );
      expect(state.suppressMainText).toBe(true);
      // hasStreamedText=true 이므로 이 assistant 블록 자체는 원래도 emit 되지 않는다
      expect(confirmResult).toEqual([]);

      // 3) 메인이 같은 턴에서 재요약을 시도해도 억제된다
      const mainResult = processMessage(mainDeltaMsg('이대로 생성할까요? 다시 물어봅니다'), tag, false, state);
      expect(mainResult).toEqual([]);
    });

    it('PM-428c: 확인 질문 문구가 아니어도(내용 무관) subagent 완료 시 억제한다 — 정규식 매칭 제거 회귀 가드', () => {
      // 라이브 재현에서 실제로 관찰된 케이스: "생성할까요"/"진행할까요" 정규식을 피해가는
      // 동의어 표현("생성해도 될지")도 여전히 subagent 의 최종 응답이므로 억제되어야 한다.
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_2';

      processMessage(delegateMsg(toolUseId), tag, false, state);
      processMessage(
        subagentConfirmMsg(toolUseId, '어느 쪽으로 진행할지, 그리고 이 설계 그대로 생성해도 될지 확인 부탁드립니다.'),
        tag,
        true,
        state,
      );
      expect(state.suppressMainText).toBe(true);

      const mainResult = processMessage(mainDeltaMsg('## 설계안 재요약'), tag, false, state);
      expect(mainResult).toEqual([]);
    });

    it('PM-428e: 확인 질문 없이(에러/거부로) 끝난 subagent 완료도 동일하게 억제한다', () => {
      // subagent 완료 텍스트는 내용에 관계없이 이번 위임의 최종 응답이므로, 에러/거부
      // 텍스트라 해도 메인이 별도 재서술을 덧붙이는 것은 동일하게 중복이다.
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_2b';

      processMessage(delegateMsg(toolUseId), tag, false, state);
      processMessage(
        subagentConfirmMsg(toolUseId, '이 요청은 범위 밖이라 처리할 수 없습니다.'),
        tag,
        true,
        state,
      );
      expect(state.suppressMainText).toBe(true);

      const mainResult = processMessage(mainDeltaMsg('추가 안내를 드릴게요'), tag, false, state);
      expect(mainResult).toEqual([]);
    });

    it('PM-428d: subagent 자신의 확인 텍스트(비스트리밍 fallback)는 억제 대상이 아니다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_3';

      processMessage(delegateMsg(toolUseId), tag, false, state);
      // hasStreamedText=false 인 비스트리밍 fallback 경로에서도 subagent 자신의 텍스트는 emit 된다
      const result = processMessage(
        subagentConfirmMsg(toolUseId, '이대로 생성할까요?'),
        tag,
        false,
        state,
      );
      expect(result).toEqual([{ type: 'text', content: '이대로 생성할까요?' }]);
    });
  });

  // #429: 3개 subagent_type 화이트리스트를 폐지하고 Agent 로 위임되는 모든 subagent 에
  // 동일하게 relay 억제를 적용 + 메인의 새 tool_use 로 억제를 해제하는 회귀 가드.
  describe('#429 relay 억제 화이트리스트 폐지 + 새 tool_use 로 해제', () => {
    const delegateMsg = (toolUseId: string, subagentType: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: toolUseId,
              name: 'Agent',
              input: { subagent_type: subagentType, prompt: '...' },
            },
          ],
        },
      }) as unknown as SDKMessage;

    const subagentConfirmMsg = (toolUseId: string, text: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: toolUseId,
        message: { content: [{ type: 'text', text }] },
      }) as unknown as SDKMessage;

    const mainDeltaMsg = (text: string) =>
      ({
        type: 'stream_event',
        parent_tool_use_id: null,
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
      }) as unknown as SDKMessage;

    // 메인(parent 없음)의 새 tool_use 메시지 — Agent 가 아닌 일반 도구도 포함.
    const mainToolUseMsg = (toolUseId: string, name: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [{ type: 'tool_use', id: toolUseId, name, input: {} }],
        },
      }) as unknown as SDKMessage;

    it('PM-429a: smart-job-manager(3개 화이트리스트 밖) 위임 완료 후에도 메인 재요약을 억제한다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_sjm_1';

      processMessage(delegateMsg(toolUseId, 'smart-job-manager'), tag, false, state);
      expect(state.pendingGuardToolUseIds.has(toolUseId)).toBe(true);

      processMessage(subagentConfirmMsg(toolUseId, '## 설계안 ...\n이대로 생성할까요?'), tag, true, state);
      expect(state.suppressMainText).toBe(true);

      const mainResult = processMessage(mainDeltaMsg('## 설계안 재요약 ...\n확인 부탁드립니다.'), tag, false, state);
      expect(mainResult).toEqual([]);
    });

    it('PM-429b: subagent 완료 후 메인이 새 tool_use 를 발행하면 억제가 해제되고 이후 메인 텍스트는 emit 된다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_reset_1';

      processMessage(delegateMsg(toolUseId, 'pipeline-builder'), tag, false, state);
      processMessage(subagentConfirmMsg(toolUseId, '파이프라인을 생성했습니다.'), tag, true, state);
      expect(state.suppressMainText).toBe(true);

      // 메인이 새 도구(run_pipeline)를 호출 — 재서술이 아니라 실제 다음 작업이라는 구조적 증거.
      processMessage(mainToolUseMsg('toolu_run', 'mcp__firehub__run_pipeline'), tag, false, state);
      expect(state.suppressMainText).toBe(false);

      // 억제가 해제됐으므로 이후 메인 텍스트는 정상적으로 emit 된다.
      const mainResult = processMessage(mainDeltaMsg('파이프라인 실행을 시작했습니다.'), tag, false, state);
      expect(mainResult).toEqual([{ type: 'text', content: '파이프라인 실행을 시작했습니다.' }]);
    });

    it('PM-429c: subagent 내부 tool_use(parent 있음)는 억제를 해제하지 않는다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_internal_1';

      processMessage(delegateMsg(toolUseId, 'smart-job-manager'), tag, false, state);
      processMessage(subagentConfirmMsg(toolUseId, '## 설계안 ...\n이대로 생성할까요?'), tag, true, state);
      expect(state.suppressMainText).toBe(true);

      // subagent 내부 tool_use — parent_tool_use_id 가 위임 id 이므로 메인의 새 작업이 아니다.
      const internalToolUseMsg = {
        type: 'assistant',
        parent_tool_use_id: toolUseId,
        message: {
          content: [{ type: 'tool_use', id: 'toolu_internal_call', name: 'mcp__firehub__list_proactive_jobs', input: {} }],
        },
      } as unknown as SDKMessage;
      processMessage(internalToolUseMsg, tag, false, state);
      expect(state.suppressMainText).toBe(true);

      const mainResult = processMessage(mainDeltaMsg('## 설계안 재요약 ...\n확인 부탁드립니다.'), tag, false, state);
      expect(mainResult).toEqual([]);
    });
  });
});
