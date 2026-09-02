import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import type { SSEEvent } from './agent-sdk.js';
import { truncate } from '../utils.js';
import { MAX_BUDGET_USD } from '../constants.js';
// 지역 변수 totalInputTokens와 이름이 겹치므로 별칭으로 들여온다.
import { totalInputTokens as sumInputTokens } from './token-usage.js';

// L3 DESIGN 가드 subagent(#428) — SDK Agent 도구로 위임된 subagent 는 parent_tool_use_id 가
// 위임 tool_use 의 id 로 채워진 채 같은 top-level query() 스트림에 인터리브된다. subagent 가
// 이미 자기 턴을 텍스트로 마쳤는데, 메인(parent_tool_use_id=null) 이 같은 응답 턴에서 이를
// 재요약해 별도 text 를 또 출력하면 사용자에게 동일 확인이 두 번 노출된다(코디네이터
// 완료-notification 처리가 "결과 보고"로 흘러 재서술을 유발). 애초에 이 문제 클래스에
// "확인 질문으로 끝났는지"를 문구로 판별할 필요가 없다 — DESIGN 모드로 위임된 이상 subagent
// 텍스트 자체가 항상 이번 위임의 최종 사용자 응답이므로, 내용에 관계없이 무조건 메인의
// 후속 재서술을 억제한다. (최초 구현은 "생성할까요|진행할까요" 정규식으로 확인 질문만
// 골라 억제했으나, 라이브 재현 3회 중 1회가 "생성해도 될지" 같은 동의어 표현으로 정규식을
// 피해가 중복이 재발함을 확인 — #428 검증 로그. 문구 매칭은 LLM 표현 변주에 근본적으로
// 취약하므로 제거하고 구조적 판별자(parent_tool_use_id)만으로 억제한다.)
export const DESIGN_GUARD_SUBAGENT_TYPES = new Set(['pipeline-builder', 'template-builder', 'dashboard-builder']);

/**
 * processMessage 호출 간 유지되는 상태 — 한 HTTP 요청(executeAgent 1회 호출)의 수명과 일치한다.
 * agent-sdk.ts 가 요청마다 새로 생성해 각 processMessage 호출에 전달한다.
 */
export interface DesignGuardRelayState {
  /** 메인이 DESIGN 가드 subagent 에 위임한 Agent tool_use id 집합(위임 시점에 채움) */
  pendingGuardToolUseIds: Set<string>;
  /** 위 위임 중 하나라도 텍스트로 완료됐으면 true(문구 내용 불문) — 이후
   *  메인(parent_tool_use_id=null)의 text 이벤트를 이번 요청이 끝날 때까지 억제한다 */
  suppressMainText: boolean;
}

export function createDesignGuardRelayState(): DesignGuardRelayState {
  return { pendingGuardToolUseIds: new Set(), suppressMainText: false };
}

export function processMessage(
  msg: SDKMessage,
  tag: () => string,
  hasStreamedText: boolean,
  relayState: DesignGuardRelayState = createDesignGuardRelayState(),
): SSEEvent[] {
  const events: SSEEvent[] = [];
  // SDKMessage 유니온 중 parent_tool_use_id 를 갖는 타입(assistant/user/stream_event)에서만
  // 존재 — 메인 top-level 메시지는 null, subagent 위임 메시지는 위임 tool_use id.
  const parentToolUseId = 'parent_tool_use_id' in msg ? (msg as { parent_tool_use_id: string | null }).parent_tool_use_id : null;

  switch (msg.type) {
    case 'system': {
      if (msg.subtype === 'init') {
        console.log(`${tag()} ● Session init: ${msg.session_id}`);
        events.push({
          type: 'init',
          sessionId: msg.session_id,
        });
      } else if (msg.subtype === 'compact_boundary') {
        const metadata = (msg as { compact_metadata?: { trigger?: string; pre_tokens?: number } }).compact_metadata;
        console.log(`${tag()} ● Compaction boundary (trigger=${metadata?.trigger}, pre_tokens=${metadata?.pre_tokens})`);
        events.push({
          type: 'compaction',
          status: 'completed',
          trigger: metadata?.trigger,
          preTokens: metadata?.pre_tokens,
        });
      } else if (msg.subtype === 'status') {
        const status = (msg as { status?: string }).status;
        if (status === 'compacting') {
          console.log(`${tag()} ● Compaction started`);
          events.push({
            type: 'compaction',
            status: 'started',
          });
        } else {
          console.log(`${tag()} ● Status: ${status}`);
        }
      } else {
        console.log(`${tag()} ● System: ${msg.subtype}`);
      }
      break;
    }

    case 'assistant': {
      // Normally text is streamed via stream_event (text_delta), so we skip text blocks
      // here to avoid duplication. However, in error cases (e.g. credit balance too low),
      // the SDK may return text directly in the assistant message without streaming.
      // In that case, emit the text so the frontend can display it.
      if (msg.message?.content) {
        for (const block of msg.message.content) {
          if (block.type === 'text' && 'text' in block) {
            console.log(`${tag()} ◀ Text: "${truncate(String(block.text))}"`);
            // #428: subagent(parent_tool_use_id 있음) 텍스트가 위임 목록에 있으면 문구 내용과
            // 무관하게 이 요청이 끝날 때까지 메인(parent 없음)의 후속 text 를 억제한다.
            if (parentToolUseId && relayState.pendingGuardToolUseIds.has(parentToolUseId)) {
              relayState.suppressMainText = true;
              console.log(`${tag()} 🔇 DESIGN 가드 subagent 완료 감지 — 메인 재요약 억제(#428)`);
            }
            const isSuppressedMainText = !parentToolUseId && relayState.suppressMainText;
            if (!hasStreamedText && !isSuppressedMainText) {
              events.push({ type: 'text', content: block.text });
            }
          } else if (block.type === 'tool_use' && 'name' in block) {
            const input = 'input' in block ? block.input : {};
            console.log(`${tag()} ◀ Tool call: ${block.name}(${truncate(JSON.stringify(input))})`);
            // #428: 메인이 DESIGN 가드 subagent 에 위임하는 순간(Agent tool_use) 을 기록해,
            // 해당 subagent 의 완료 텍스트를 이후 parent_tool_use_id 로 식별할 수 있게 한다.
            if (
              !parentToolUseId &&
              block.name === 'Agent' &&
              'id' in block &&
              typeof (input as { subagent_type?: unknown })?.subagent_type === 'string' &&
              DESIGN_GUARD_SUBAGENT_TYPES.has((input as { subagent_type: string }).subagent_type)
            ) {
              relayState.pendingGuardToolUseIds.add(String((block as { id: string }).id));
            }
            events.push({
              type: 'tool_use',
              toolName: block.name,
              input,
            });
          } else {
            console.log(`${tag()} ◀ Assistant block: ${block.type}`);
          }
        }
      }
      break;
    }

    case 'user': {
      if (msg.message?.content && Array.isArray(msg.message.content)) {
        for (const block of msg.message.content) {
          if (typeof block === 'object' && 'type' in block && block.type === 'tool_result') {
            const rawContent = 'content' in block ? block.content : undefined;
            let resultStr: string | undefined;
            if (typeof rawContent === 'string') {
              resultStr = rawContent;
            } else if (Array.isArray(rawContent)) {
              resultStr = rawContent
                .map((c: unknown) =>
                  typeof c === 'object' && c !== null && 'text' in c
                    ? (c as { text: string }).text
                    : JSON.stringify(c),
                )
                .join('\n');
            } else if (rawContent !== undefined) {
              resultStr = JSON.stringify(rawContent);
            }
            const toolId = 'tool_use_id' in block ? String(block.tool_use_id) : 'unknown';
            // is_error 필드 추출: safeTool()이 에러 발생 시 isError: true를 반환하므로 SSE 이벤트에 포함
            const isError = 'is_error' in block ? Boolean((block as { is_error?: unknown }).is_error) : false;
            console.log(`${tag()} ◀ Tool result [${toolId}]${isError ? ' (ERROR)' : ''}: ${truncate(resultStr ?? '(empty)')}`);
            events.push({
              type: 'tool_result',
              toolName: toolId,
              result: resultStr,
              isError,
            });
          } else {
            const blockType =
              typeof block === 'object' && block !== null && 'type' in block
                ? (block as { type: string }).type
                : 'unknown';
            console.log(`${tag()} ◀ User block: ${blockType}`);
          }
        }
      }
      break;
    }

    case 'result': {
      const resultMsg = msg as {
        usage?: Record<string, number>;
        modelUsage?: Record<
          string,
          {
            inputTokens: number;
            outputTokens: number;
            cacheReadInputTokens: number;
            cacheCreationInputTokens: number;
          }
        >;
      };
      let totalInputTokens = 0;
      let totalOutputTokens = 0;
      if (resultMsg.usage) {
        const u = resultMsg.usage;
        // #336: 공용 헬퍼로 통일 (경로별 합산 누락 재발 방지).
        totalInputTokens = sumInputTokens(u);
        totalOutputTokens = u.output_tokens ?? 0;
        console.log(
          `${tag()} 📊 Total tokens — input: ${u.input_tokens ?? 0}, output: ${totalOutputTokens}, cache_read: ${u.cache_read_input_tokens ?? 0}, cache_create: ${u.cache_creation_input_tokens ?? 0} (total_input: ${totalInputTokens})`,
        );
      }
      if (resultMsg.modelUsage) {
        for (const [modelName, u] of Object.entries(resultMsg.modelUsage)) {
          console.log(
            `${tag()} 📊 Model ${modelName} — input: ${u.inputTokens}, output: ${u.outputTokens}, cache_read: ${u.cacheReadInputTokens}, cache_create: ${u.cacheCreationInputTokens}`,
          );
        }
      }
      if (msg.subtype === 'success') {
        console.log(`${tag()} ✓ Session completed: ${msg.session_id}`);
        events.push({
          type: 'done',
          sessionId: msg.session_id,
          inputTokens: totalInputTokens,
          outputTokens: totalOutputTokens,
        });
      } else {
        // #277: 예산 초과는 전용 메시지로 구분(그 외는 기존 max_turns 처리 유지)
        const isBudget = (msg.subtype as string) === 'error_max_budget_usd';
        const rawError = 'errors' in msg ? (msg as { errors: string[] }).errors.join('; ') : '';
        const errorMsg = isBudget
          ? `이 작업이 비용 한도($${MAX_BUDGET_USD})에 도달해 자동 중단되었습니다. 범위를 좁혀 다시 시도해 주세요.`
          : rawError || 'max_turns_exceeded';
        console.error(`${tag()} ✗ Session failed: ${errorMsg}`);
        events.push({
          type: 'error',
          message: errorMsg,
          sessionId: msg.session_id,
          inputTokens: totalInputTokens,
          outputTokens: totalOutputTokens,
        });
      }
      break;
    }

    case 'stream_event': {
      const event = msg.event;
      if (event.type === 'content_block_delta' && 'delta' in event) {
        const delta = event.delta;
        if (delta.type === 'text_delta' && 'text' in delta) {
          // #428: 실제 사용자 노출 텍스트는 대부분 이 델타 스트림으로 나간다. DESIGN 가드
          // subagent 가 이미 확인 질문으로 마쳤다면(relayState.suppressMainText), 메인
          // (parent_tool_use_id=null) 의 후속 델타는 억제해 중복 확인 노출을 차단한다.
          const isSuppressedMainDelta = !parentToolUseId && relayState.suppressMainText;
          if (!isSuppressedMainDelta) {
            events.push({
              type: 'text',
              content: delta.text,
            });
          }
        }
      } else if (event.type === 'message_delta') {
        const delta = event as { type: string; usage?: { output_tokens?: number } };
        if (delta.usage?.output_tokens) {
          console.log(
            `${tag()} ⚡ Stream: message_delta (output_tokens: ${delta.usage.output_tokens})`,
          );
        } else {
          console.log(`${tag()} ⚡ Stream: message_delta`);
        }
      } else if (event.type !== 'content_block_delta') {
        console.log(`${tag()} ⚡ Stream: ${event.type}`);
      }
      break;
    }

    default: {
      console.log(`${tag()} ❓ Unknown SDK message: ${(msg as { type: string }).type}`);
      break;
    }
  }

  return events;
}
