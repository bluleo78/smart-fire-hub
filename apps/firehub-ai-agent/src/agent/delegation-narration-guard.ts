/**
 * 위임 narration 가드 (#578 — #239 회귀 재발 방지, 코드 레벨 백스톱).
 *
 * 배경: #239 는 "메인 에이전트가 tool_use 사이에 계획·진행 narration 을 내지 않는다"는 텍스트
 * 계약(시스템 프롬프트 L2)만으로 고쳤다. 이후 #428/#572/#573 이 relay 규칙을 덧붙이는 과정에서
 * "pipeline-builder에게 맡길게요 류 진행 안내 … 정상" 같은 문구가 들어가 프롬프트 안에서 모순이
 * 생겼고, trigger-manager 위임 흐름에서 다음이 재발했다(inspector 2026-09-09T23-15 trace):
 *   - "trigger-manager에게 위임합니다." (subagent 코드명 노출 — L2 노출 금지 위반)
 *   - Agent 위임 직후 "…요청했습니다. 완료되면 결과를 전달드릴게요." (위임 예고 — subagent 가 직접
 *     답하므로 항상 중복)
 *   - `Bash("echo noop")` 같은 목적 없는 호스트 도구 호출이 사용자에게 도구 호출 칩으로 노출
 *
 * 프롬프트 보강만으로는 재호출에서 "…에게 위임하겠습니다"·"진행 중입니다. 완료되면 전달드릴게요"·
 * `echo noop` 이 그대로 다시 관찰됐다(LLM 비결정성). 따라서 두 프로바이더(agent-cli.ts / process-message.ts)
 * 가 공유하는 **구조적·결정적** 판별 함수를 두고, 프롬프트는 1차 방어, 이 모듈은 2차 안전망으로 삼는다.
 *
 * 판별은 문구 매칭이 아니라 다음 두 축으로만 한다:
 *   1. subagent 코드명 포함 여부 — 정의된 subagent 이름(loadSubagents 키) 토큰 매칭. 어떤 사용자
 *      응답에도 코드명이 들어갈 정당한 이유가 없다(L2 노출 금지).
 *   2. 위임 직후 구간 — 메인이 비동기 Agent/SendMessage 를 발행한 뒤 subagent 의 텍스트가 도착하기
 *      전까지의 메인 텍스트. 이 구간의 메인 텍스트는 정의상 "위임 예고"뿐이다(결과는 subagent 가
 *      parent_tool_use_id 로 직접 내보낸다).
 */

/** 기본 subagent 코드명 접미사 패턴 — loadSubagents 를 못 쓰는 경로(단위 테스트 등)의 하한선 */
const SUBAGENT_SUFFIX_PATTERN = /\b[a-z]+(?:-[a-z]+)*-(?:manager|builder|analyst|writer)\b/i;

/**
 * 텍스트가 subagent 내부 코드명을 포함하는지 판별한다.
 *
 * @param text 메인 에이전트가 낸 사용자 대상 텍스트
 * @param subagentNames 정의된 subagent 이름 목록(`Object.keys(loadSubagents())`). 비어 있으면
 *        `*-manager`/`*-builder`/`*-analyst`/`*-writer` 접미사 패턴으로만 판별한다.
 */
export function mentionsSubagentIdentifier(text: string, subagentNames: readonly string[] = []): boolean {
  if (!text) return false;
  for (const name of subagentNames) {
    if (!name) continue;
    // 이름 앞뒤가 식별자 문자([A-Za-z0-9_-])가 아닐 때만 매칭 — "data-analyst" 가 "data-analysts"
    // 같은 다른 토큰에 부분 매칭되는 것을 막는다. 한글·공백·구두점("trigger-manager에게")은 경계로 본다.
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`(^|[^A-Za-z0-9_-])${escaped}(?![A-Za-z0-9_-])`, 'i');
    if (re.test(text)) return true;
  }
  return SUBAGENT_SUFFIX_PATTERN.test(text);
}

/**
 * 텍스트 안의 subagent 코드명을 중립 표현으로 치환한다 — 억제된 텍스트를 빈 응답 방지용 fallback 으로
 * 내보낼 때 코드명만은 노출하지 않기 위해 사용한다.
 */
export function redactSubagentIdentifiers(text: string, subagentNames: readonly string[] = []): string {
  let out = text;
  for (const name of subagentNames) {
    if (!name) continue;
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`(^|[^A-Za-z0-9_-])${escaped}(?![A-Za-z0-9_-])`, 'gi'), '$1전문 에이전트');
  }
  return out.replace(new RegExp(SUBAGENT_SUFFIX_PATTERN.source, 'gi'), '전문 에이전트');
}

/**
 * 내부 라우팅 어휘 → 중립 표현 치환표 (#581).
 *
 * 배경: #578 의 두 축(코드명 / 비동기 위임 직후 구간)은 **도구 호출이 전혀 없는 턴**의 어휘 누출을
 * 구조상 잡지 못한다 — 실측(inspector crosscheck-578-trig-009): "트리거 삭제(파괴 작업)는 위임하되,
 * 먼저 어느 파이프라인 소속인지 알려주시겠어요?" (tool_use 0건, 코드명 없음). '위임'은 시스템 프롬프트의
 * 라우팅 표에서만 쓰이는 내부 어휘라 사용자 응답에 나올 정당한 이유가 없다(L2 노출 금지).
 *
 * 억제가 아니라 **치환**인 이유: 되묻기 턴은 그 텍스트가 응답의 전부라 억제하면 빈 응답(#573 급 critical)
 * 이 되고, 동기 위임 relay(#572/#573) 텍스트를 오탐 억제할 위험도 있다. 치환은 문장을 살리면서 어휘만
 * 가리므로 relay 누락·빈 응답을 만들 수 없다.
 *
 * 보수성: 단어 목록은 라우팅 문맥 밖에서 거의 쓰이지 않는 것만 둔다('맡기다' 같은 일상어는 제외).
 * 사용자가 자기 메시지에 이 어휘를 직접 쓴 경우(데이터 문맥 — 예: "업무 위임 테이블")는 치환하지 않는다
 * (`createRoutingVocabRedactor` 의 userMessage 인자).
 */
const ROUTING_VOCAB_RULES: ReadonlyArray<readonly [term: string, replacement: string]> = [
  ['위임', '처리'],
  ['라우팅', '연결'],
];

/** 텍스트에 내부 라우팅 어휘가 들어 있는지 판별한다 */
export function containsRoutingVocabulary(text: string): boolean {
  if (!text) return false;
  return ROUTING_VOCAB_RULES.some(([term]) => text.includes(term));
}

/** 텍스트 안의 내부 라우팅 어휘를 중립 표현으로 치환한다(완성 텍스트용) */
export function redactRoutingVocabulary(text: string): string {
  if (!text) return text;
  let out = text;
  for (const [term, replacement] of ROUTING_VOCAB_RULES) {
    out = out.split(term).join(replacement);
  }
  return out;
}

/**
 * 스트리밍 델타용 라우팅 어휘 치환기.
 *
 * 델타는 토큰 단위로 쪼개져 '위' + '임하되' 처럼 어휘가 경계에 걸칠 수 있다. 그래서 델타 끝이 어떤 어휘의
 * 진접두사(예: '위', '라', '라우')면 그 꼬리만 보류했다가 다음 델타와 이어 붙여 판정한다. 보류 꼬리는
 * 다음 델타에서 즉시 풀리므로 체감 지연은 없고, 스트림이 끝나면 `flush()` 로 남은 꼬리를 내보낸다.
 *
 * @param userMessage 사용자 원문 — 사용자가 라우팅 어휘를 직접 썼으면(데이터 문맥) 치환을 끈다.
 */
export interface RoutingVocabRedactor {
  /** 치환이 켜져 있는지(사용자가 어휘를 직접 쓴 경우 false) */
  readonly enabled: boolean;
  /** 델타를 넣고 지금 내보내도 되는(치환된) 부분을 돌려받는다. 빈 문자열이면 아직 보류 중 */
  push(delta: string): string;
  /** 스트림 종료·비델타 이벤트 도착 시 보류 꼬리를 내보낸다 */
  flush(): string;
  /** 완성 블록(비스트림) 치환 — 보류 상태를 건드리지 않는다 */
  redact(text: string): string;
}

export function createRoutingVocabRedactor(userMessage = ''): RoutingVocabRedactor {
  const enabled = !containsRoutingVocabulary(userMessage);
  let tail = '';
  return {
    enabled,
    push(delta: string): string {
      if (!enabled) return delta;
      const joined = tail + delta;
      // 끝부분이 어휘의 진접두사와 일치하는 가장 긴 길이만큼 보류
      let hold = 0;
      for (const [term] of ROUTING_VOCAB_RULES) {
        for (let k = term.length - 1; k > hold; k--) {
          if (joined.endsWith(term.slice(0, k))) {
            hold = k;
            break;
          }
        }
      }
      tail = hold ? joined.slice(joined.length - hold) : '';
      const emit = hold ? joined.slice(0, joined.length - hold) : joined;
      return redactRoutingVocabulary(emit);
    },
    flush(): string {
      const out = tail;
      tail = '';
      return out;
    },
    redact(text: string): string {
      return enabled ? redactRoutingVocabulary(text) : text;
    },
  };
}

/** 목적 없는 no-op 명령 — 턴을 채우기 위한 호출로 실제 작업이 아니다 */
const NOOP_COMMAND_PATTERN = /^\s*(?:echo(?:\s+["']?(?:noop|no-op|ok|start|ready)?["']?)?|true|:)\s*;?\s*$/i;

/**
 * 메인의 호스트 도구 호출이 목적 없는 no-op(`Bash("echo noop")`, `Bash("true")`)인지 판별한다.
 * 첨부 파일 처리 등 정당한 Bash 사용(경로·명령이 있는 호출)은 no-op 이 아니다.
 */
export function isNoopHostToolCall(toolName: string, input: unknown): boolean {
  if (toolName !== 'Bash') return false;
  const command = (input as { command?: unknown } | undefined)?.command;
  if (typeof command !== 'string') return false;
  return NOOP_COMMAND_PATTERN.test(command);
}

/**
 * 위임 직후 구간 상태 — 한 HTTP 요청 수명 동안 유지한다.
 *
 * - `awaitingAsyncDelegation`: 메인이 비동기 위임(Agent/SendMessage, run_in_background≠false)을
 *   발행했고 아직 (a) 그 subagent 의 텍스트가 도착하지 않았으며 (b) 메인이 다른 tool_use 로 실제
 *   후속 작업에 들어가지도 않은 상태. 이 동안의 메인 텍스트는 위임 예고이므로 억제한다.
 * - `lastSuppressedMainText`: 위 억제로 버린 마지막 메인 텍스트. subagent 가 텍스트 없이 끝나는 등
 *   사용자에게 아무 텍스트도 나가지 않은 채 턴이 끝나면 완전히 빈 응답을 막기 위해 이것을 fallback
 *   으로 내보낸다(#573 과 같은 방어 원칙).
 * - `hiddenNoopToolUseIds`: 사용자 표시에서 숨긴 no-op 호출의 tool_use id — 대응 tool_result 도 숨긴다.
 */
export interface DelegationNarrationState {
  awaitingAsyncDelegation: boolean;
  lastSuppressedMainText?: string;
  hiddenNoopToolUseIds: Set<string>;
}

export function createDelegationNarrationState(): DelegationNarrationState {
  return { awaitingAsyncDelegation: false, hiddenNoopToolUseIds: new Set() };
}

/**
 * 메인(parent_tool_use_id 없음) 텍스트를 사용자에게 내보내도 되는지 판별하고, 억제 시 사유를 돌려준다.
 * 억제된 텍스트는 fallback 용으로 상태에 보관한다.
 */
export function classifyMainText(
  state: DelegationNarrationState,
  text: string,
  subagentNames: readonly string[],
): { suppress: boolean; reason?: 'subagent-identifier' | 'awaiting-delegation' } {
  if (mentionsSubagentIdentifier(text, subagentNames)) {
    state.lastSuppressedMainText = text;
    return { suppress: true, reason: 'subagent-identifier' };
  }
  if (state.awaitingAsyncDelegation) {
    state.lastSuppressedMainText = text;
    return { suppress: true, reason: 'awaiting-delegation' };
  }
  return { suppress: false };
}

/**
 * 메인이 tool_use 를 발행했을 때 상태를 갱신한다.
 * - 비동기 Agent/SendMessage → 위임 직후 구간 시작
 * - 그 외 도구 → 메인이 실제 후속 작업에 들어갔다는 구조적 증거이므로 구간 종료(#429 와 동일 원칙)
 */
export function noteMainToolUse(
  state: DelegationNarrationState,
  toolName: string,
  input: unknown,
): void {
  const isDelegation = toolName === 'Agent' || toolName === 'SendMessage';
  const runInBackground = (input as { run_in_background?: unknown } | undefined)?.run_in_background;
  if (isDelegation && runInBackground !== false) {
    state.awaitingAsyncDelegation = true;
    return;
  }
  if (!isDelegation) {
    state.awaitingAsyncDelegation = false;
  }
}

/** subagent(parent_tool_use_id 있음)의 텍스트가 도착하면 위임 직후 구간이 끝난다 */
export function noteSubagentText(state: DelegationNarrationState): void {
  state.awaitingAsyncDelegation = false;
}

/**
 * 메인이 받은 tool_result 가 위임 실패(is_error)면 구간을 끝낸다 — 이 경우 subagent 가 답할 수 없어
 * 메인이 직접 사용자에게 설명해야 한다.
 */
export function noteMainToolResult(state: DelegationNarrationState, isError: boolean): void {
  if (isError) state.awaitingAsyncDelegation = false;
}
