import type { OutputColumn, ClassifyResponse } from '../services/classification-service.js';
import type { SharePurpose } from '../mcp/api-client.js';

export type SSEEvent = {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction' | 'ping' | 'cost_alarm';
  [key: string]: unknown;
};

export interface ChatProviderOptions {
  message: string;
  sessionId?: string;
  /**
   * 실행 테넌트. 디스크 산출물 경로가 이 값에서 파생되므로 **필수**다(`AgentOptions.tenantId` 주석 참조).
   * 경로 스코핑 전용 — API 로 되돌아오는 호출의 테넌트는 API 가 멤버십에서 다시 파생한다.
   */
  tenantId: number;
  userId: number;
  fileIds?: number[];
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  /** true이면 systemPrompt가 기본 SYSTEM_PROMPT를 완전히 대체한다 (프로액티브 등 전용 프롬프트용) */
  overrideSystemPrompt?: boolean;
  /** WD-59: opencode 만 반영한다(라우트가 AGENT_RUN_SETTING_SUPPORT 로 다른 공급자에는 넘기지 않는다). */
  temperature?: number;
  /** WD-59: opencode 만 반영한다(OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX env — openCodeOutputTokenEnv). */
  maxTokens?: number;
  abortSignal?: AbortSignal;
  /**
   * S3: 'share' 면 이 실행의 MCP 호출이 X-AI-Purpose: share 를 싣는다(Proactive 리포트 — 결과가 발송된다).
   * 'share' 만 허용한다 — 'none'(AI 판정 제외)은 LLM 실행 경로에서 쓰이면 안 된다.
   */
  aiPurpose?: SharePurpose;
  /**
   * WD-62: Proactive 실행의 리포트 산출 디렉터리. opencode 실행기만 읽어 MCP 프로세스 env 로 주입한다
   * (write_report_file 도구가 이 디렉터리에만 쓴다). SDK·CLI 는 report-writer 하위 에이전트 경로를 쓰므로 무시한다.
   */
  reportDir?: string;
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
  /**
   * 자격증명 유형 판별자. opencode 테넌트는 apiKey 가 OpenAI 호환 키라 Claude SDK 로는 쓸 수
   * 없으므로, 이 값 없이는 completion 팩토리가 어느 provider 를 골라야 할지 알 수 없다
   * (route/classify.ts → classifyBatch → createCompletionProvider 전 구간에서 보존해야 한다).
   */
  agentType?: AgentType;
  /** opencode 전용: OpenAI 호환 provider 베이스 URL. */
  baseUrl?: string;
  /** opencode 전용: provider 식별자(payload.providerId). */
  providerId?: string;
  /** opencode 전용: 추론 강도. 현재 이 앱엔 사용처가 없다(ProviderConfig.reasoningEffort 참고). */
  reasoningEffort?: string;
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

/** 알려진 네 agentType 값. 라우트(바디)의 판별자 검증에 쓰는 단일 출처. */
export const KNOWN_AGENT_TYPES: readonly AgentType[] = ['sdk', 'cli', 'cli-api', 'opencode'];

/**
 * 값이 알려진 AgentType 인지 판별한다.
 *
 * /chat·/proactive·classify 세 라우트가 요청 바디의 agentType 을 이 술어로 검증한다. 검증 없이
 * 넘기면 undefined 나 오타 값이 라우트 기본값("sdk")으로 조용히 취급될 여지가 생기고, opencode
 * 자격증명(빈 Anthropic apiKey)이 Claude SDK 경로로 흘러 ambient ANTHROPIC_API_KEY 로 새는
 * 6b1c6383 과금 회귀가 재현된다. firehub-api 는 이제 자격증명 유형을 항상 명시해 보내므로
 * (설계서 "API 인터페이스" 절), 라우트 바디에서의 누락은 버그 신호로 다뤄야 한다.
 *
 * **주의**: 이 술어는 라우트 경계 전용이다. `ProviderFactory.createCompletionProvider` 는
 * stdio-server.ts 와 단독 스크립트 호출(agentType 없이 자격증명만 전달)이라는 정당한
 * 무-agentType 호출부를 가지므로 그쪽에서는 이 술어로 거부하지 않는다 — 팩토리는 agentType 이
 * 없으면 Claude SDK 로 보낸다(자격증명이 비면 complete() 가 실패한다, #708). stdio-server.ts 는 CLI 경로에서는 여전히 agentType 이
 * 없지만(자격증명이 env 로만 오간다), opencode 경로에서는 `AI_CREDENTIAL_AGENT_TYPE=opencode`
 * 를 명시로 실어 보낸다(Ruling #30, resolveStdioCredentials 참고) — 어느 쪽이든 이 팩토리는
 * agentType 유무로 거부하지 않는다는 사실 자체는 변하지 않는다.
 */
export function isKnownAgentType(value: unknown): value is AgentType {
  return typeof value === 'string' && (KNOWN_AGENT_TYPES as readonly string[]).includes(value);
}

/** 설정 › AI 에이전트의 실행 설정 중 공급자에 따라 반영 여부가 갈리는 세 항목(ai.max_turns·ai.temperature·ai.max_tokens). */
export type AgentRunSetting = 'maxTurns' | 'temperature' | 'maxTokens';

/**
 * WD-59: 공급자별로 실행 설정이 <b>실제로</b> 반영되는지의 단일 출처. 라우트(chat.ts)는 false 인 값을 공급자에 넘기지 않는다.
 *
 * - maxTurns: sdk 는 query() maxTurns, cli/cli-api 는 claude CLI `--max-turns`(--help 에는 없는 숨은 옵션 — SDK 도
 *   이 인자로 CLI 에 넘긴다), opencode 는 agent.build.steps(buildOpenCodeConfig).
 * - temperature·maxTokens: opencode 만 반영한다(agent.build.temperature, OUTPUT_TOKEN_MAX env). Claude Agent SDK Options 에는
 *   두 필드가 없고 CLI 에도 인자가 없다. maxTokens 는 CLAUDE_CODE_MAX_OUTPUT_TOKENS env 로 걸 수는 있지만 연결하지 않는다 —
 *   그 env 는 하위 에이전트(report-writer 의 파일 쓰기처럼 큰 도구 입력 포함)와 thinking 예산까지 한꺼번에 묶고, 이 경로는
 *   격리 스택에서 실제 실행으로 검증할 수 없다(Claude 자격증명 필요). 필요하면 별도 이슈로 다룬다.
 *
 * <b>firehub-web `src/lib/ai-credential.ts` 의 `AI_BEHAVIOR_SETTING_SUPPORT` 와 같은 사실이어야 한다</b>(앱 간 코드 공유가 없어
 * 각자 상수를 둔다 — 한쪽을 바꾸면 다른 쪽도 함께 고친다).
 */
export const AGENT_RUN_SETTING_SUPPORT: Readonly<Record<AgentType, Readonly<Record<AgentRunSetting, boolean>>>> = {
  sdk: { maxTurns: true, temperature: false, maxTokens: false },
  cli: { maxTurns: true, temperature: false, maxTokens: false },
  'cli-api': { maxTurns: true, temperature: false, maxTokens: false },
  opencode: { maxTurns: true, temperature: true, maxTokens: true },
};

export interface ProviderConfig {
  agentType: AgentType;
  model?: string;
  apiKey?: string;
  oauthToken?: string;
  /** opencode 전용: OpenAI 호환 provider 의 베이스 URL(예: https://api.openai.com/v1). */
  baseUrl?: string;
  /**
   * opencode 전용: 저장 규약상 provider 식별자(credential.payload.providerId).
   * `ai.model` 이 `providerId/modelId` 형식이라 모델 문자열에서 접두사를 벗기는 쪽(provider 구현)이
   * 실제로 쓰지만, 이 필드 자체는 검증·로깅용으로 팩토리까지 흘려 보존한다.
   */
  providerId?: string;
  /**
   * opencode 전용: 추론 강도(예: "medium"). buildOpenCodeConfig(agent-opencode.ts)가 provider
   * 모델의 `options.reasoningEffort` 로 싣는다(빈 값/공백이면 "설정 안 함"이라 options 자체를
   * 생략 — 빈 문자열을 그대로 보내면 opencode 가 400 을 반환한다). Claude 계열 추론 강도
   * (thinking/budget_tokens)는 여전히 이 앱에 배선이 없어 별도 이슈로 남아 있다(설계서 "확인된
   * 이슈" 절) — 이 필드는 opencode 전용이라 그것과 무관하다.
   */
  reasoningEffort?: string;
}
