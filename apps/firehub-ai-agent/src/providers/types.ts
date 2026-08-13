import type { OutputColumn, ClassifyResponse } from '../services/classification-service.js';

export type SSEEvent = {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction' | 'ping' | 'cost_alarm';
  [key: string]: unknown;
};

export interface ChatProviderOptions {
  message: string;
  sessionId?: string;
  userId: number;
  fileIds?: number[];
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  /** true이면 systemPrompt가 기본 SYSTEM_PROMPT를 완전히 대체한다 (프로액티브 등 전용 프롬프트용) */
  overrideSystemPrompt?: boolean;
  temperature?: number;
  maxTokens?: number;
  abortSignal?: AbortSignal;
}

export interface ChatProvider {
  readonly name: string;
  execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent>;
}

export interface ClassifyProviderOptions {
  rows: Record<string, unknown>[];
  prompt: string;
  outputColumns: OutputColumn[];
  model?: string;
  apiKey?: string;
  /** 구독(OAuth) 토큰. apiKey 와 함께 오면 OAuth 를 우선한다. */
  oauthToken?: string;
}

export interface ClassifyProvider {
  readonly name: string;
  classify(options: ClassifyProviderOptions): Promise<ClassifyResponse>;
}

/**
 * 단발(one-shot) 텍스트 completion 요청 옵션.
 *
 * ChatProvider(스트리밍)·ClassifyProvider(배치 분류)와 달리 "시스템 프롬프트 + 사용자 텍스트 →
 * 응답 텍스트" 한 번만 필요한 호출을 위한 세 번째 provider 종류다. GraphRAG 추출·온톨로지 추론
 * 등이 이 형태이며, 이 슬롯이 없던 탓에 graphrag/llm-cli.ts 가 provider 추상화를 우회하고
 * claude CLI 를 직접 spawn 해 인증 경로가 갈라졌다(#인증 통합).
 */
export interface CompletionOptions {
  model?: string;
  /**
   * 최대 출력 토큰. Agent SDK 의 query() 옵션에는 maxTokens 가 없고
   * CLAUDE_CODE_MAX_OUTPUT_TOKENS 환경변수로만 제어되므로 provider 가 env 로 변환한다.
   */
  maxOutputTokens?: number;
  /** 호출 상한(ms). 초과 시 abort 후 reject. */
  timeoutMs?: number;
  abortSignal?: AbortSignal;
  /**
   * 시스템 프롬프트 적용 방식.
   *
   * - `replace`(기본): 전달한 프롬프트만 사용한다. claude_code 프리셋이 섞이지 않아 순수 completion 에 적합.
   * - `append-to-preset`: claude_code 프리셋 뒤에 덧붙인다. 기존 `claude -p --append-system-prompt` 와
   *   동일한 의미이므로, 그 방식에 맞춰 튜닝된 프롬프트(GraphRAG 추출 등)의 동작을 보존할 때 쓴다.
   */
  systemPromptMode?: 'replace' | 'append-to-preset';
}

/**
 * completion 결과. usage 는 best-effort — 호출자(예: 분류 파이프라인)가 토큰 수를
 * 요구하지 않는 경우가 있어 필수 계약으로 두지 않는다.
 */
export interface CompletionResult {
  text: string;
  usage?: { inputTokens: number; outputTokens: number };
}

export interface CompletionProvider {
  readonly name: string;
  complete(
    systemPrompt: string,
    userText: string,
    options?: CompletionOptions,
  ): Promise<CompletionResult>;
}

export type AgentType = 'sdk' | 'cli' | 'cli-api' | 'opencode';

export interface ProviderConfig {
  agentType: AgentType;
  model?: string;
  apiKey?: string;
  oauthToken?: string;
}
