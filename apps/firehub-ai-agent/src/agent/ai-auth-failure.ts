/**
 * AI 공급자 오류 정책의 **단일 출처** (#410, #708, #711).
 *
 * <p>SDK 경로(process-message.ts)·CLI 경로(agent-cli.ts)·단발 completion(claude-sdk-completion-provider.ts)·
 * 프로액티브 라우트(proactive.ts)가 같은 판정(오류 종류·종결 여부·기계 판독 코드)과 같은 한국어 안내를
 * 쓰도록 한 곳에 둔다. 예전엔 경로마다 "인증이거나 결제면" 같은 집합을 손으로 적었고, 문구도 CLI 경로만
 * "OAuth 토큰을 갱신"을 안내해 API 키 테넌트는 무엇을 고쳐야 할지 알 수 없었다.
 */
import type { SDKAssistantMessageError } from '@anthropic-ai/claude-agent-sdk';
import { AdminActionableError } from './admin-actionable-error.js';

/** 구조화 오류 종류(SDK/CLI assistant `error`) 또는 원문에서 추정한 종류. 없으면 undefined. */
export type ProviderErrorKind = SDKAssistantMessageError | string | undefined;

/** SSE `error` 이벤트에 싣는 필드 — 사용자용 한국어 안내 + (해당하면) 기계 판독 코드. */
export interface ProviderErrorFields {
  message: string;
  code?: string;
}

/** 즉시 종결하지 않고 result 까지 판정을 미룬 assistant 오류. */
export interface PendingProviderError {
  kind: string;
  text: string;
}

/**
 * 인증·결제·요청 한도 실패를 나타내는 기계 판독용 코드.
 *
 * <p>SSE `error` 이벤트의 선택 필드 `code` 로 싣는다. 프로액티브 라우트는 이 코드를 보고 HTTP 502 +
 * 같은 `code` 로 응답하고, firehub-api ProactiveAiClient 는 응답 본문에서 이 문자열을 찾아 "AI 인증
 * 정보를 확인하라"는 조치 안내를 고른다. 채팅 SSE 에서는 알 수 없는 필드라 웹이 무시한다.
 */
export const AGENT_AUTH_OR_QUOTA_FAILURE = 'AGENT_AUTH_OR_QUOTA_FAILURE';

/**
 * 평범한 assistant 텍스트에 적용하는 **좁은** 인증 실패 패턴(#410 의 서명 넷).
 *
 * <p>CLI 경로는 인증 실패를 `result` 뿐 아니라 일반 assistant text·text_delta 로도 흘리므로 텍스트로도
 * 판정한다. 이 정규식은 **정상 답변 본문**(subagent 텍스트 포함)에 적용되므로 `invalid api key`·
 * `x-api-key`·`authentication_error` 같은 문구는 넣지 않는다 — 외부 API 오류를 인용하는 정상 답변에도
 * 나와 멀쩡한 세션이 죽고 프로액티브가 502 가 된다. API 키 인증 실패는 구조 신호(assistant `error`,
 * result success+is_error, error_* subtype)로 잡는다. 오류로 **확정된** 텍스트의 종류 추정은 더 넓은
 * {@link PROVIDER_ERROR_SIGNATURES} 가 한다.
 */
export const AUTH_FAILURE_PATTERN =
  /not logged in|please run \/login|failed to authenticate|oauth access token is invalid/i;

/** 텍스트 패턴이 한 번에 걸칠 수 있는 최대 길이(가장 긴 서명 + 여유) — 델타 경계 검사 창에 쓴다. */
export const AUTH_FAILURE_PATTERN_MAX_SPAN = 40;

/** 인증 실패 안내 — API 키와 OAuth 토큰 두 경우를 모두 다룬다. 원문 영문은 서버 로그에만 남긴다. */
export const AUTH_FAILURE_KOREAN_MESSAGE =
  'AI 에이전트 인증이 만료되었거나 올바르지 않습니다. 관리자에게 문의하거나 설정 › AI 에이전트에서 API 키 또는 OAuth 토큰을 다시 등록해 주세요.';

/** 요청에 자격증명이 없을 때 사용자에게 보일 안내. */
export const AI_CREDENTIAL_MISSING_MESSAGE =
  'AI 자격증명(API 키 또는 OAuth 토큰)이 설정되지 않았습니다. 관리자에게 문의하거나 설정 › AI 에이전트에서 등록해 주세요.';

/**
 * 오류로 **확정된** 원문의 서명 → 종류 표(소문자 리터럴). 두 곳이 같은 표를 쓴다:
 * - {@link inferProviderErrorKind}: 원문 어디든 포함되면 그 종류(is_error 결과·error_* 결과 전용).
 * - {@link leadingProviderErrorSignature}: 원문이 서명으로 **시작**하는지(프로액티브의 선두 검사).
 */
export const PROVIDER_ERROR_SIGNATURES: ReadonlyArray<readonly [string, SDKAssistantMessageError]> = [
  ['not logged in', 'authentication_failed'],
  ['please run /login', 'authentication_failed'],
  ['failed to authenticate', 'authentication_failed'],
  ['oauth access token is invalid', 'authentication_failed'],
  ['oauth token has expired', 'authentication_failed'],
  ['invalid api key', 'authentication_failed'], // "Invalid API key · Fix external API key"
  ['invalid bearer token', 'authentication_failed'],
  ['authentication_error', 'authentication_failed'],
  ['api error: 401', 'authentication_failed'],
  ['api error: 403', 'authentication_failed'],
  ['credit balance is too low', 'billing_error'],
  ['api error: 429', 'rate_limit'],
  ['rate_limit', 'rate_limit'],
  ['api error: 5', 'server_error'],
  ['overloaded', 'server_error'],
  ['internal server error', 'server_error'],
];

/**
 * 오류로 확정된 원문으로 종류를 추정한다(CLI stream-json 의 result 텍스트 등 구조화 종류가 없을 때).
 * 정상 답변 본문에는 쓰지 않는다 — "API Error: 401" 을 서술한 리포트가 오류로 오판된다.
 */
export function inferProviderErrorKind(rawText: string): SDKAssistantMessageError | undefined {
  const text = rawText.toLowerCase();
  return PROVIDER_ERROR_SIGNATURES.find(([sig]) => text.includes(sig))?.[1];
}

/**
 * 원문이 "설정을 확인해야 하는(코드가 붙는)" 오류 서명으로 **시작**하면 그 서명을 돌려준다.
 * 프로액티브 전용 선두 검사다 — 에이전트 실패 문구는 출력 첫 글자부터 시작하지만, 장애를 서술하는
 * 정상 리포트는 본문 중간에 같은 문구를 담을 수 있어 includes 로는 오탐한다.
 */
export function leadingProviderErrorSignature(rawText: string): string | null {
  const head = rawText.trim().toLowerCase();
  if (!head) return null;
  return (
    PROVIDER_ERROR_SIGNATURES.find(([sig, kind]) => head.startsWith(sig) && codeForKind(kind))?.[0] ??
    null
  );
}

/**
 * 설정(자격증명·결제)을 고치기 전엔 절대 회복되지 않는 **종결** 오류인지 — 인증·결제.
 * 이 종류는 스트림 도중이라도 즉시 error 1건으로 끝내고, completion 에서는 {@link AiCredentialFailureError}
 * 로 던져 GraphRAG 의 "실패 → 빈 결과" 삼킴에서도 빠진다.
 */
export function isTerminalProviderError(kind: ProviderErrorKind): boolean {
  return kind === 'authentication_failed' || kind === 'billing_error';
}

/**
 * 기계 판독 코드. 종결 오류(인증·결제)와 **요청 한도**에 붙는다.
 *
 * <p>요청 한도는 의도적으로 "코드는 붙지만 종결은 아닌" 종류다. 종결이 아닌 이유: Claude Code 가 같은
 * 턴 안에서 재시도로 회복할 수 있어, 스트림 도중 즉시 끊지 않고 result 까지 판정을 미룬다. 코드가 붙는
 * 이유: 결국 실패로 끝났다면 사용자의 조치(요금제·한도 확인)가 필요하다 — firehub-api 가 이 코드로 고르는
 * "AI 설정 확인" 안내가 맞고, 예전 proactive 의 텍스트 서명 목록도 429 를 같은 코드로 다뤘다.
 */
function codeForKind(kind: ProviderErrorKind): string | undefined {
  return isTerminalProviderError(kind) || kind === 'rate_limit' ? AGENT_AUTH_OR_QUOTA_FAILURE : undefined;
}

/** 정책이 아는 종류들 — 이 밖(invalid_request/unknown/없음)이면 원문 서명 추정이 이긴다. */
const KNOWN_KINDS: ReadonlySet<string> = new Set([
  'authentication_failed',
  'billing_error',
  'rate_limit',
  'server_error',
]);

/** 구조화 종류가 정책이 아는 종류면 그대로, 아니면 원문으로 추정한 종류(없으면 원래 종류). */
function effectiveKind(kind: ProviderErrorKind, rawText: string): ProviderErrorKind {
  if (kind && KNOWN_KINDS.has(kind)) return kind;
  return inferProviderErrorKind(rawText) ?? kind;
}

/** 종류별 사용자 안내. 알 수 없는 종류는 원문이 원인 파악에 더 유용해 그대로 전달한다. */
function messageForKind(kind: ProviderErrorKind, rawText: string): string {
  switch (kind) {
    case 'authentication_failed':
      return AUTH_FAILURE_KOREAN_MESSAGE;
    case 'billing_error':
      return 'AI 공급자 계정의 크레딧 또는 결제 한도가 부족해 응답을 생성하지 못했습니다. 관리자에게 문의해 주세요.';
    case 'rate_limit':
      return 'AI 공급자의 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.';
    case 'server_error':
      return 'AI 공급자 서버에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
    default:
      return rawText.trim() || 'AI 응답 생성 중 오류가 발생했습니다.';
  }
}

/**
 * 공급자 오류를 SSE `error` 이벤트 필드로 만든다. 종류는 한 번만 판정해 안내와 코드에 함께 쓴다.
 */
export function providerErrorFields(kind: ProviderErrorKind, rawText: string): ProviderErrorFields {
  const k = effectiveKind(kind, rawText);
  const message = messageForKind(k, rawText);
  const code = codeForKind(k);
  return code ? { message, code } : { message };
}

/** {@link providerErrorFields} 의 안내 문구만. */
export function describeAiProviderError(kind: ProviderErrorKind, rawText: string): string {
  return providerErrorFields(kind, rawText).message;
}

/**
 * assistant 메시지의 `error` 필드를 판정한다 — SDK·CLI 상태 기계가 공유한다.
 *
 * - 메인 메시지(parent_tool_use_id 없음)만 본다. subagent 오류는 그 위임의 tool_result 로 메인에 전달된다.
 * - `max_output_tokens` 는 Claude Code 가 "이어서 작성" 메타 메시지로 자동 회복하므로 오류가 아니다.
 * - 종결 오류면 `{ terminal }`(즉시 error 1건 후 종료), 아니면 `{ pending }`(본문만 억제, result 까지 미룸).
 * - 오류가 아니면 null — 호출부는 평소처럼 본문을 처리한다.
 * 어느 경우든 본문(영문 원문 "Failed to authenticate. API Error: 401 …")은 사용자에게 내보내지 않는다.
 */
export function classifyAssistantError(
  msg: { error?: string; message?: { content?: ReadonlyArray<{ type: string; text?: unknown }> } },
  parentToolUseId: string | null | undefined,
): { terminal: ProviderErrorFields; kind: string; text: string } | { pending: PendingProviderError } | null {
  const kind = msg.error;
  if (parentToolUseId || !kind || kind === 'max_output_tokens') return null;
  const text = (msg.message?.content ?? [])
    .map((block) => (block.type === 'text' && block.text !== undefined ? String(block.text) : ''))
    .join('');
  if (isTerminalProviderError(kind)) return { terminal: providerErrorFields(kind, text), kind, text };
  return { pending: { kind, text } };
}

/**
 * 오류로 끝난 result(success+is_error 또는 error_* subtype)의 이벤트 필드. 앞서 미뤄 둔 assistant 오류가
 * 있으면 그 종류·본문을 우선하고, 없으면 result 원문으로 추정한다.
 */
export function resolveResultError(
  pending: PendingProviderError | undefined,
  rawResult: string,
): ProviderErrorFields {
  return providerErrorFields(pending?.kind, pending?.text || rawResult);
}

/**
 * 요청에 자격증명이 없어 자식을 띄울 수 없을 때의 오류.
 *
 * <p>{@link AdminActionableError} 를 상속한다 — 채팅 라우트는 이 타입의 메시지만 그대로 내보내므로
 * 사용자가 "설정 › AI 에이전트에서 등록" 안내를 본다. 코드도 싣는다 — 프로액티브가 502 + 코드로 응답해
 * firehub-api 가 조치 안내를 고른다.
 */
export class MissingAiCredentialError extends AdminActionableError {
  readonly code = AGENT_AUTH_OR_QUOTA_FAILURE;
  constructor() {
    super(AI_CREDENTIAL_MISSING_MESSAGE);
    this.name = 'MissingAiCredentialError';
  }
}

/**
 * 단발 completion(분류·GraphRAG)이 종결 오류(인증·결제)로 끝났음을 알리는 오류. 메시지는 사용자용 한국어
 * 안내다(원문은 로그에만). 고치기 전엔 다시 불러도 같은 결과이므로 GraphRAG 의 "실패 → 빈 결과" 삼킴에서
 * 빠져 원인을 드러낸다.
 */
export class AiCredentialFailureError extends Error {
  readonly code = AGENT_AUTH_OR_QUOTA_FAILURE;
  constructor(message: string) {
    super(message);
    this.name = 'AiCredentialFailureError';
  }
}

/**
 * 삼키면 안 되는 AI 자격증명 실패인지 — 자격증명 없음 또는 종결 오류({@link isTerminalProviderError}).
 * 두 타입 모두 코드를 싣는다.
 */
export function isAiCredentialFailure(
  err: unknown,
): err is MissingAiCredentialError | AiCredentialFailureError {
  return err instanceof AiCredentialFailureError || err instanceof MissingAiCredentialError;
}

/**
 * 단발 completion 의 실패 원문(result success+is_error 의 result, error_* 의 errors)을 던질 오류로 바꾼다.
 * 종결 종류면 {@link AiCredentialFailureError}(한국어 안내만), 아니면 `context`(진단용 subtype 등)와
 * 한국어 안내(알 수 없으면 원문)를 담은 일반 오류.
 */
export function toCompletionError(rawText: string, context: string): Error {
  const kind = inferProviderErrorKind(rawText);
  const message = describeAiProviderError(kind, rawText);
  return isTerminalProviderError(kind)
    ? new AiCredentialFailureError(message)
    : new Error(`[completion] ${context}${rawText.trim() ? `: ${message}` : ''}`);
}
