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

  // #573: run_in_background:false(동기) Agent 위임은 subagent 텍스트가 top-level 스트림에
  // interleave 될 기회가 전혀 없다 — tool_result 로만 반환된다. 메인이 이를 relay 하는 text
  // 없이 턴을 끝내면(result success) 사용자에게 완전히 빈 응답이 나가므로, 방어적으로 fallback
  // relay 한다.
  describe('#573 동기 Agent 위임(run_in_background:false) tool_result relay 누락 방어', () => {
    const syncDelegateMsg = (toolUseId: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: toolUseId,
              name: 'Agent',
              input: { subagent_type: 'dataset-manager', prompt: '...', run_in_background: false },
            },
          ],
        },
      }) as unknown as SDKMessage;

    // 관찰된 실제 라이브 재현(dataset-010)과 동일한 형태 — 본문 뒤에 agentId/SendMessage 계속-실행
    // 안내와 <usage> 블록이 SDK 에 의해 자동으로 붙는다.
    const syncToolResultMsg = (toolUseId: string, body: string) =>
      ({
        type: 'user',
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: toolUseId,
              content: `${body}agentId: a3dcd6ea4e1299f9c (use SendMessage with to: 'a3dcd6ea4e1299f9c', summary: '<5-10 word recap>' to continue this agent)\n<usage>subagent_tokens: 19760\ntool_uses: 0\nduration_ms: 3282</usage>`,
            },
          ],
        },
      }) as unknown as SDKMessage;

    const resultSuccessMsg = () =>
      ({
        type: 'result',
        subtype: 'success',
        session_id: 'sess-573',
        usage: { input_tokens: 100, output_tokens: 20, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      }) as unknown as SDKMessage;

    // 비동기(기본값) 위임의 즉시 launch 응답 — 실제 완료 내용이 아니므로 fallback 대상이 아니다.
    const asyncLaunchToolResultMsg = (toolUseId: string) =>
      ({
        type: 'user',
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: toolUseId,
              content:
                "Async agent launched successfully. (This tool result is internal metadata — never quote or paste any part of it, including the agentId below, into a user-facing reply.)\nagentId: ad744b29879d952ce (internal ID - do not mention to user. Use SendMessage with to: 'ad744b29879d952ce', summary: '<5-10 word recap>' to continue this agent.)",
            },
          ],
        },
      }) as unknown as SDKMessage;

    it('PM-573a: 동기 위임 tool_result 이후 텍스트 없이 턴이 끝나면 footer 를 제거한 본문을 fallback relay 한다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_sync_1';

      processMessage(syncDelegateMsg(toolUseId), tag, false, state);
      expect(state.syncDelegationToolUseIds.has(toolUseId)).toBe(true);

      processMessage(
        syncToolResultMsg(toolUseId, '`created_at`은 예약 컬럼이라 안 됩니다. `event_created_at`으로 대체합니다. 이대로 생성할까요?'),
        tag,
        false,
        state,
      );
      expect(state.syncDelegationToolUseIds.has(toolUseId)).toBe(false);
      expect(state.pendingSyncAgentResultText).toContain('이대로 생성할까요?');

      const doneResult = processMessage(resultSuccessMsg(), tag, false, state);

      // fallback text 는 done 이전에, agentId/SendMessage/<usage> 없이 relay 되어야 한다.
      expect(doneResult).toEqual([
        { type: 'text', content: '`created_at`은 예약 컬럼이라 안 됩니다. `event_created_at`으로 대체합니다. 이대로 생성할까요?' },
        { type: 'done', sessionId: 'sess-573', inputTokens: 100, outputTokens: 20 },
      ]);
      expect(state.pendingSyncAgentResultText).toBeUndefined();
    });

    it('PM-573b: 비동기(기본값) 위임의 launch 응답은 fallback 대상이 아니다 — 실제 완료 내용이 아직 도착 전이기 때문', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_async_1';

      // run_in_background 미지정 → syncDelegationToolUseIds 에 등록되지 않는다.
      const asyncDelegateMsg = {
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            { type: 'tool_use', id: toolUseId, name: 'Agent', input: { subagent_type: 'dataset-manager', prompt: '...' } },
          ],
        },
      } as unknown as SDKMessage;

      processMessage(asyncDelegateMsg, tag, false, state);
      expect(state.syncDelegationToolUseIds.has(toolUseId)).toBe(false);

      processMessage(asyncLaunchToolResultMsg(toolUseId), tag, false, state);
      expect(state.pendingSyncAgentResultText).toBeUndefined();

      // 텍스트 없이 턴이 끝나도(예: 메인이 다른 작업을 계속하는 중) fallback 이 발동하지 않는다 —
      // 비동기 launch 응답은 async agent 진행 중 boilerplate 일 뿐 최종 응답이 아니다.
      const doneResult = processMessage(resultSuccessMsg(), tag, false, state);
      expect(doneResult).toEqual([{ type: 'done', sessionId: 'sess-573', inputTokens: 100, outputTokens: 20 }]);
    });

    it('PM-573c → #572 2차로 개정: 메인이 자체 텍스트로 이어가도 신뢰하지 않고 억제하며, 위임의 실제 tool_result 만 정확히 1번 relay 된다', () => {
      // #572 2차 수정으로 전제가 바뀌었다: "메인이 텍스트를 냈다"는 사실만으로 relay 가 정상
      // 이뤄졌다고 신뢰하던 예전 로직(#573)이 실제 재현된 회귀의 원인이었다 — data-analyst
      // 위임 완료 후 메인이 낸 텍스트가 tool_result 를 문자 그대로 relay 한 게 아니라 자신의
      // 말로 재구성한 것이었는데도 이 신뢰 때문에 그대로 노출됐다(라이브 trace 로 확정). 이제
      // 동기 위임 tool_result 대기 중에는 메인의 텍스트를 내용·일치 여부와 무관하게 항상
      // 억제하고, 위임의 원문만 강제로 1번 relay 한다.
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_sync_2';

      processMessage(syncDelegateMsg(toolUseId), tag, false, state);
      processMessage(syncToolResultMsg(toolUseId, '데이터셋을 생성했습니다.'), tag, false, state);
      expect(state.pendingSyncAgentResultText).toBeDefined();

      // 메인이 (설사 정확히 일치하는) relay 텍스트를 델타로 냈더라도 억제되고, pending 상태는
      // 유지된다 — "텍스트가 나갔다"는 사실만으로는 더 이상 신뢰하지 않는다.
      const deltaEvents = processMessage(
        { type: 'stream_event', parent_tool_use_id: null, event: { type: 'content_block_delta', delta: { type: 'text_delta', text: '데이터셋을 생성했습니다.' } } } as unknown as SDKMessage,
        tag,
        false,
        state,
      );
      expect(deltaEvents).toEqual([]);
      expect(state.pendingSyncAgentResultText).toBeDefined();

      // 턴 종료 — fallback 이 위임의 원문(footer 제거)을 정확히 1번 relay 한다.
      const doneResult = processMessage(resultSuccessMsg(), tag, false, state);
      expect(doneResult).toEqual([
        { type: 'text', content: '데이터셋을 생성했습니다.' },
        { type: 'done', sessionId: 'sess-573', inputTokens: 100, outputTokens: 20 },
      ]);
    });

    it('PM-573d: 메인이 새 tool_use 로 후속 작업을 이어가면 fallback 대상에서 제외된다', () => {
      const state = createDesignGuardRelayState();
      const toolUseId = 'toolu_sync_3';

      processMessage(syncDelegateMsg(toolUseId), tag, false, state);
      processMessage(syncToolResultMsg(toolUseId, '이대로 생성할까요?'), tag, false, state);
      expect(state.pendingSyncAgentResultText).toBeDefined();

      // 메인이 확인을 받은 뒤 실제 다음 작업(예: create_dataset)을 호출 — 정당한 후속 흐름.
      processMessage(
        {
          type: 'assistant',
          parent_tool_use_id: null,
          message: { content: [{ type: 'tool_use', id: 'toolu_next', name: 'mcp__firehub__create_dataset', input: {} }] },
        } as unknown as SDKMessage,
        tag,
        false,
        state,
      );
      expect(state.pendingSyncAgentResultText).toBeUndefined();
    });
  });

  describe('#572 2차 수정: data-analyst 동기 위임과 조사 도구의 병렬 tool_use 배치 — 위임 결과 폐기 회귀', () => {
    // 1차 수정(#572)은 "위임 후 재조사 금지" 문구 + #573 fallback(텍스트 없을 때만 강제 relay)에
    // 의존했다. 실제 재현된 2차 회귀는 모델이 Agent(data-analyst, run_in_background:false) 위임과
    // find_datasets 등 조사 도구를 같은 tool_use 배치로 "병렬" 발행하는 것 — 가벼운 조사 도구가
    // 먼저 끝나고, 무거운 Agent 위임은 tool_result 가 가장 늦게 도착한다. 그 사이 메인은 자체
    // 조사로 결론 텍스트를 만들어내는데, 그 텍스트가 "텍스트를 냈다"는 이유만으로 #573 fallback 의
    // 리셋 조건을 통과시켜(pendingSyncAgentResultText 클리어) 방어가 무력화됐었다.
    const syncDelegateMsg = (toolUseId: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: {
          content: [
            {
              type: 'tool_use',
              id: toolUseId,
              name: 'Agent',
              input: { subagent_type: 'data-analyst', prompt: '월별 평균 사망자수 분석', run_in_background: false },
            },
          ],
        },
      }) as unknown as SDKMessage;

    const investigationToolUseMsg = (toolUseId: string, name: string) =>
      ({
        type: 'assistant',
        parent_tool_use_id: null,
        message: { content: [{ type: 'tool_use', id: toolUseId, name, input: {} }] },
      }) as unknown as SDKMessage;

    const toolResultMsg = (toolUseId: string, content: string) =>
      ({
        type: 'user',
        message: { content: [{ type: 'tool_result', tool_use_id: toolUseId, content }] },
      }) as unknown as SDKMessage;

    const mainTextDeltaMsg = (text: string) =>
      ({
        type: 'stream_event',
        parent_tool_use_id: null,
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
      }) as unknown as SDKMessage;

    const resultSuccessMsg = () =>
      ({
        type: 'result',
        subtype: 'success',
        session_id: 'sess-572-2',
        usage: { input_tokens: 100, output_tokens: 20, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      }) as unknown as SDKMessage;

    it('PM-572-2a: 위임 tool_result 를 받기 전에 다른 tool_use 가 발행되면 위반으로 표시된다', () => {
      const state = createDesignGuardRelayState();
      const delegateId = 'toolu_da_1';

      processMessage(syncDelegateMsg(delegateId), tag, false, state);
      expect(state.syncDelegationViolated).toBe(false);

      // 위임의 tool_result 가 아직 안 왔는데(syncDelegationToolUseIds 에 남아있음) 메인이 다른
      // 조사 도구를 발행 — 실제 재현된 병렬 배치 패턴.
      processMessage(investigationToolUseMsg('toolu_find_1', 'mcp__firehub__find_datasets'), tag, false, state);
      expect(state.syncDelegationViolated).toBe(true);
    });

    it('PM-572-2b: 위반 이후 메인의 자체 재조사 텍스트는 억제되고, 위임의 실제 tool_result 가 강제 relay 된다', () => {
      const state = createDesignGuardRelayState();
      const delegateId = 'toolu_da_2';

      processMessage(syncDelegateMsg(delegateId), tag, false, state);
      // 위임 결과가 오기 전에 조사 도구가 병렬로 발행됨 → 위반 표시.
      processMessage(investigationToolUseMsg('toolu_find_2', 'mcp__firehub__find_datasets'), tag, false, state);
      processMessage(toolResultMsg('toolu_find_2', '[{"datasetId":87,"name":"지역별 화재 통계"}]'), tag, false, state);

      // 위임 자신의 tool_result 가 (가장 늦게) 도착 — 이게 진짜 relay 되어야 할 내용.
      const delegateResultText = '"화재발생현황" 데이터셋을 찾아 월별 평균 사망자수를 집계했습니다: 1월 2.3명...';
      processMessage(toolResultMsg(delegateId, delegateResultText), tag, false, state);
      expect(state.pendingSyncAgentResultText).toBe(delegateResultText);

      // 메인이 자체 조사 결과로 재구성한 텍스트를 스트리밍 — 반드시 억제되어야 한다.
      const wrongText = '"화재발생현황"이라는 이름의 데이터셋은 없고, death_count 는 0건입니다.';
      const deltaResult = processMessage(mainTextDeltaMsg(wrongText), tag, false, state);
      expect(deltaResult).toEqual([]); // 사용자에게 노출되지 않아야 한다.
      // #573 의 "텍스트가 나갔으니 정상 relay" 리셋도 위반 상태에서는 적용되지 않아야 한다.
      expect(state.pendingSyncAgentResultText).toBe(delegateResultText);

      // 턴 종료 — fallback 이 위임의 진짜 결과를 강제로 relay 해야 한다(메인의 wrongText 는 등장 X).
      const doneResult = processMessage(resultSuccessMsg(), tag, false, state);
      expect(doneResult).toEqual([
        { type: 'text', content: delegateResultText },
        { type: 'done', sessionId: 'sess-572-2', inputTokens: 100, outputTokens: 20 },
      ]);
    });
  });
});
