import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import type { SSEEvent } from './agent-sdk.js';
import { truncate } from '../utils.js';
import { MAX_BUDGET_USD } from '../constants.js';
// 지역 변수 totalInputTokens와 이름이 겹치므로 별칭으로 들여온다.
import { totalInputTokens as sumInputTokens } from './token-usage.js';

// Agent 위임 relay 가드(#428→#429 일반화) — SDK Agent 도구로 위임된 subagent 는
// parent_tool_use_id 가 위임 tool_use 의 id 로 채워진 채 같은 top-level query() 스트림에
// 인터리브된다. subagent 가 이미 자기 턴을 텍스트로 마쳤는데, 메인(parent_tool_use_id=null)
// 이 같은 응답 턴에서 이를 재요약해 별도 text 를 또 출력하면 사용자에게 동일 확인이 두 번
// 노출된다(코디네이터 완료-notification 처리가 "결과 보고"로 흘러 재서술을 유발). 이 문제
// 클래스에 "확인 질문으로 끝났는지"를 문구로 판별할 필요가 없다 — Agent 로 위임된 이상 subagent
// 텍스트 자체가 항상 이번 위임의 최종 사용자 응답이므로, 내용에 관계없이 무조건 메인의
// 후속 재서술을 억제한다. (최초 구현은 "생성할까요|진행할까요" 정규식으로 확인 질문만
// 골라 억제했으나, 라이브 재현 3회 중 1회가 "생성해도 될지" 같은 동의어 표현으로 정규식을
// 피해가 중복이 재발함을 확인 — #428 검증 로그. 문구 매칭은 LLM 표현 변주에 근본적으로
// 취약하므로 제거하고 구조적 판별자(parent_tool_use_id)만으로 억제한다.)
//
// #429: 최초 구현은 pipeline-builder/template-builder/dashboard-builder 3개 subagent_type
// 화이트리스트(DESIGN_GUARD_SUBAGENT_TYPES)로 범위를 좁혔으나, 같은 근본 원인(메인의 재서술)이
// smart-job-manager 등 화이트리스트 밖 subagent 에서도 그대로 재현됐다. 판별 자체가 이미
// parent_tool_use_id 구조 기반이라 특정 subagent_type 에 의존할 이유가 없으므로, Agent 도구로
// 위임되는 모든 subagent 에 동일하게 적용한다(화이트리스트 폐지).
//
// 단, suppressMainText 는 한 번 세팅되면 요청이 끝날 때까지 유지되던 기존 설계라 3개 subagent
// 로 범위가 좁을 때는 문제가 없었지만, 전체로 확장하면 "여러 subagent 를 순차 위임하고 마지막에
// 메인이 추가 작업(도구 호출)을 한 뒤 그 결과를 보고"하는 정당한 시나리오까지 영구 억제해버린다
// (예: "파이프라인 만들고 바로 실행해줘" → pipeline-builder 완료 후 메인이 run_pipeline 을 호출해
// 결과를 보고해야 하는데 억제되면 그 보고가 사라짐 — 기능 손실이지 단순 UX 문제가 아니다).
// 그래서 메인(parent_tool_use_id=null)이 새 tool_use 를 발행하는 순간 억제를 해제한다 — 새 도구
// 호출은 메인이 재서술이 아니라 실제로 다음 작업을 하고 있다는 구조적 증거이기 때문이다.
//
// #573: 위 가드는 "subagent 가 같은 top-level 스트림에 interleave 된다"는 전제(async 위임 기본값)
// 에서만 작동한다. 메인이 `Agent(..., run_in_background: false)`로 **동기** 위임하면 SDK 는
// subagent 의 내부 assistant 메시지를 top-level 스트림에 전혀 interleave 하지 않고, 최종 응답을
// 단일 tool_result 문자열로만 반환한다 — 즉 subagent 자신의 텍스트가 `text` 이벤트로 노출될
// 구조적 기회 자체가 없다. 이 경우 relay 책임은 전적으로 메인(LLM)의 사후 발화에 달려 있는데,
// 시스템 프롬프트 준수가 실패하면(관찰된 회귀) 사용자는 완전히 빈 응답을 받는다. 방어적으로,
// 동기 위임 tool_result 를 기록해두고 텍스트 없이 턴이 끝나면(`result` 성공) 그 내용을 강제로
// relay 한다 — 문구 매칭이 아니라 위임 시점의 `run_in_background: false` 구조적 신호로 판별한다.

/**
 * processMessage 호출 간 유지되는 상태 — 한 HTTP 요청(executeAgent 1회 호출)의 수명과 일치한다.
 * agent-sdk.ts 가 요청마다 새로 생성해 각 processMessage 호출에 전달한다.
 */
export interface DesignGuardRelayState {
  /** 메인이 Agent 로 위임한 tool_use id 집합(위임 시점에 채움) — 어떤 subagent_type 이든 포함 */
  pendingGuardToolUseIds: Set<string>;
  /** 위 위임 중 하나라도 텍스트로 완료됐으면 true(문구 내용 불문) — 이후 메인이 새 tool_use 를
   *  발행하기 전까지 메인(parent_tool_use_id=null)의 text 이벤트를 억제한다 */
  suppressMainText: boolean;
  /** #573: `run_in_background: false` 로 위임된 Agent tool_use id 집합 — 이 id 의 tool_result 는
   *  subagent 내부 상태가 아니라 subagent 의 최종 응답 자체이므로 fallback relay 대상 후보다 */
  syncDelegationToolUseIds: Set<string>;
  /** #573: 동기 위임 tool_result 중 아직 메인이 텍스트로 relay 하지 않은 내용(footer 제거 전 원문).
   *  이후 메인이 텍스트를 내거나 새 tool_use 를 발행하면 정리되고, 텍스트 없이 턴이 끝나면
   *  `result` 처리 시 이 내용을 강제로 text 이벤트로 relay 한다 */
  pendingSyncAgentResultText?: string;
}

export function createDesignGuardRelayState(): DesignGuardRelayState {
  return {
    pendingGuardToolUseIds: new Set(),
    suppressMainText: false,
    syncDelegationToolUseIds: new Set(),
  };
}

// #573: Agent tool_result 끝에 SDK 가 자동으로 붙이는 내부 계속-실행 안내(agentId/SendMessage)와
// 사용량 블록(<usage>...</usage>)을 제거한다. 이 텍스트가 사용자에게 그대로 노출되면 내부
// 식별자(agentId) 유출이 되어 system-prompt.ts 의 PII/내부 식별자 마스킹 규칙을 위반한다.
// #573 2차 수정: CLI 프로바이더(agent-cli.ts)도 동일한 footer 제거가 필요해 export 한다 —
// 두 실행 경로(SDK/CLI)가 각자 복제하면 문구 패턴이 갈라질 수 있으므로 단일 출처로 공유.
export function stripAgentResultFooter(text: string): string {
  return text
    .replace(/agentId:\s*\S+\s*\(use SendMessage[^)]*\)/g, '')
    .replace(/<usage>[\s\S]*?<\/usage>/g, '')
    .trim();
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
            // #573: 메인이 텍스트로 응답을 이어갔다면(스트리밍 여부 무관) 동기 위임 fallback 은
            // 더 이상 필요 없다 — relay 가 이미 정상적으로 일어난 것이므로 중복 방지를 위해 지운다.
            if (!parentToolUseId) {
              relayState.pendingSyncAgentResultText = undefined;
            }
          } else if (block.type === 'tool_use' && 'name' in block) {
            const input = 'input' in block ? block.input : {};
            console.log(`${tag()} ◀ Tool call: ${block.name}(${truncate(JSON.stringify(input))})`);
            // #429: 메인(parent_tool_use_id=null)이 새 tool_use 를 발행하면 그 시점에 억제를
            // 해제한다 — 새 도구 호출은 메인이 이전 subagent 응답을 재서술하는 게 아니라 실제로
            // 다음 작업을 하고 있다는 구조적 증거다. subagent 내부 tool_use(parentToolUseId 있음)
            // 는 메인의 작업이 아니므로 해제 대상이 아니다.
            if (!parentToolUseId && relayState.suppressMainText) {
              relayState.suppressMainText = false;
              console.log(`${tag()} 🔊 메인 새 tool_use 발행 — 재요약 억제 해제(#429)`);
            }
            // #573: 메인이 새 tool_use 를 발행했다는 것은 이전 동기 위임 결과에 대한 실제 후속
            // 작업(예: 확인 후 실제 도구 호출)이 진행 중이라는 구조적 증거다 — fallback relay 대상에서
            // 제외한다(강제 relay 가 후속 작업과 순서/내용이 어긋나는 것을 방지).
            if (!parentToolUseId) {
              relayState.pendingSyncAgentResultText = undefined;
            }
            // #428/#429: 메인이 Agent 로 위임하는 순간(subagent_type 불문) 을 기록해, 해당
            // subagent 의 완료 텍스트를 이후 parent_tool_use_id 로 식별할 수 있게 한다.
            //
            // #573 2차 수정: `run_in_background:false`(동기 위임) id 는 이 화이트리스트
            // (pendingGuardToolUseIds)에 넣지 않는다 — CLI 프로바이더(agent-cli.ts)에서 동기
            // 위임 id 를 이 화이트리스트에도 같이 넣었더니, 내부적으로 뒤늦게 중복 발사되는
            // 비동기 완료 알림(#430)이 "이미 사용자에게 보였다"는 잘못된 전제로 메인의 첫 relay
            // 텍스트까지 억제해버리는 회귀가 실제로 재현됐다. SDK 프로바이더는 현재 #430 에
            // 해당하는 알림 채널이 없어 무해하지만, 동기 위임의 완료는 애초에 이 화이트리스트가
            // 다루는 "인터리브 텍스트로 이미 노출됨" 전제에 해당하지 않으므로 대칭적으로 제외한다
            // — 완료 relay 는 오직 syncDelegationToolUseIds/pendingSyncAgentResultText 로만 다룬다.
            if (
              !parentToolUseId &&
              block.name === 'Agent' &&
              'id' in block &&
              typeof (input as { subagent_type?: unknown })?.subagent_type === 'string'
            ) {
              const toolUseId = String((block as { id: string }).id);
              if ((input as { run_in_background?: unknown })?.run_in_background === false) {
                relayState.syncDelegationToolUseIds.add(toolUseId);
              } else {
                relayState.pendingGuardToolUseIds.add(toolUseId);
              }
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
            // #573: 이 tool_result 가 동기(run_in_background:false) Agent 위임의 결과라면, subagent
            // 는 자신의 텍스트를 interleave 로 노출할 기회가 없었으므로 이 문자열 자체가 relay 되어야
            // 할 최종 응답이다. 메인이 이후 텍스트로 이어가거나 새 tool_use 를 내면 지워지고, 텍스트
            // 없이 턴이 끝나면(`result` 처리) 강제로 relay 된다.
            if (!parentToolUseId && !isError && resultStr && relayState.syncDelegationToolUseIds.has(toolId)) {
              relayState.syncDelegationToolUseIds.delete(toolId);
              relayState.pendingSyncAgentResultText = resultStr;
            }
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
        // #573: 동기 위임 tool_result 를 아무도 relay 하지 않은 채 턴이 끝나는 경우 — 시스템
        // 프롬프트 준수가 실패해도 사용자에게 완전히 빈 응답이 나가지 않도록 방어적으로 relay 한다.
        if (relayState.pendingSyncAgentResultText) {
          const relayText = stripAgentResultFooter(relayState.pendingSyncAgentResultText);
          relayState.pendingSyncAgentResultText = undefined;
          if (relayText) {
            console.warn(`${tag()} ⚠️ 동기 Agent 위임 결과가 텍스트 없이 턴 종료 — fallback relay 적용(#573): ${truncate(relayText)}`);
            events.push({ type: 'text', content: relayText });
          }
        }
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
          // #573: 메인의 실제 텍스트 델타가 나가면(억제 여부 무관 — 억제는 중복 방지 목적이지
          // 무응답 방지 목적이 아니다) 동기 위임 fallback 은 더 이상 필요 없다.
          if (!parentToolUseId) {
            relayState.pendingSyncAgentResultText = undefined;
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
