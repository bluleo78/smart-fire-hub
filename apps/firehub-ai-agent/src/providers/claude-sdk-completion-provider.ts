import { query } from '@anthropic-ai/claude-agent-sdk';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import type { CompletionOptions, CompletionProvider, CompletionResult } from './types.js';
import { totalInputTokens } from '../agent/token-usage.js';
import { DISALLOWED_TOOLS } from '../agent/tool-policy.js';

// #216 과 동일 이유의 방어: SDK 의 도구 노출 모드 판정(getExternalMcpMode)은 query() 옵션의 env 가
// 아니라 **현재 프로세스의 process.env** 를 직접 읽는다. agent-sdk.ts 가 같은 가드를 모듈 로드
// 시점에 두고 있지만, 단독 스크립트(graphrag/dump-extraction.ts, graphrag/eval/run-eval.ts)는
// agent-sdk.ts 를 import 하지 않으므로 이 모듈에도 가드를 복제해야 "tst-auto" 로 새지 않는다.
process.env.ENABLE_TOOL_SEARCH = 'false';
process.env.ENABLE_EXPERIMENTAL_MCP_CLI = 'false';

/** 호출 상한 기본값(ms). 기존 llm-cli.ts 의 CLI 스폰 타임아웃과 동일하게 맞춘다. */
const DEFAULT_TIMEOUT_MS = 90_000;

/** 중단(타임아웃/외부 abort)으로 인한 실패를 다른 에러와 구분하기 위한 sentinel. */
const ABORTED = Symbol('completion-aborted');

/**
 * 자격증명을 child 프로세스 env 로 변환한다.
 *
 * 우선순위: OAuth 구독 토큰 > API 키 > 프로세스 환경 폴백 — agent-sdk.ts 의 인증 규칙과 동일하다.
 * OAuth 토큰이 있으면 SDK 가 구독 인증을 쓰도록 ANTHROPIC_API_KEY 를 반드시 제거해야 한다.
 * process.env 를 직접 변경하지 않고 복사본을 만들어, 동시 요청끼리 자격증명이 섞이지 않게 한다.
 */
export function buildCompletionEnv(
  credentials: { apiKey?: string; oauthToken?: string } | undefined,
  maxOutputTokens?: number,
): NodeJS.ProcessEnv {
  const env = { ...process.env };
  // 중첩 세션 방지 — agent-sdk.ts 와 동일.
  delete env.CLAUDECODE;
  delete env.CLAUDE_CODE_ENTRYPOINT;

  // 어느 쪽을 쓰든 반대편 자격증명은 제거한다. 컨테이너 env 에 남은 낡은 값이 요청 자격증명을
  // 이기고 조용히 선택되는 사고를 막는다(요청이 명시한 것이 항상 이겨야 한다).
  if (credentials?.oauthToken?.trim()) {
    delete env.ANTHROPIC_API_KEY;
    env.CLAUDE_CODE_OAUTH_TOKEN = credentials.oauthToken;
  } else if (credentials?.apiKey?.trim()) {
    delete env.CLAUDE_CODE_OAUTH_TOKEN;
    env.ANTHROPIC_API_KEY = credentials.apiKey;
  }
  // 둘 다 없으면 프로세스 환경(ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN) 또는
  // 로컬 개발 시 CLI 의 macOS 키체인 인증에 맡긴다. 단독 스크립트 경로가 여기에 해당한다.

  if (maxOutputTokens !== undefined) {
    env.CLAUDE_CODE_MAX_OUTPUT_TOKENS = String(maxOutputTokens);
  }

  return env;
}

/**
 * Agent SDK 기반 단발 completion 구현.
 *
 * 채팅 경로(ClaudeSdkChatProvider → executeAgent)와 **같은 SDK·같은 인증 규칙**을 쓰는 것이 핵심이다.
 * 도구는 일절 붙이지 않는다:
 *  - mcpServers 미전달 — 이 completer 는 firehub MCP 도구 *안에서* 호출되므로 도구를 붙이면 재귀한다.
 *  - allowedTools: [] — SDK 에서 빈 화이트리스트는 그 외 host 도구 전체 차단으로 동작한다.
 *  - maxTurns: 1 — 단발 응답만 받는다.
 */
export class ClaudeSdkCompletionProvider implements CompletionProvider {
  readonly name = 'claude-sdk-completion';

  constructor(
    private readonly apiKey: string | undefined,
    private readonly oauthToken: string | undefined,
    private readonly defaultModel?: string,
  ) {}

  async complete(
    systemPrompt: string,
    userText: string,
    options?: CompletionOptions,
  ): Promise<CompletionResult> {
    const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;

    // 이미 취소된 신호로 들어오면 SDK 를 띄우지 않고 즉시 실패한다
    // (once 리스너는 이미 발화한 신호에 대해 다시 불리지 않아, 그냥 두면 타임아웃까지 매달린다).
    if (options?.abortSignal?.aborted) {
      throw new Error('[completion] 호출 전에 이미 중단된 요청입니다.');
    }

    const abortController = new AbortController();

    // 외부 abortSignal 과 자체 타임아웃을 하나의 컨트롤러로 합류시킨다.
    const onExternalAbort = () => abortController.abort();
    options?.abortSignal?.addEventListener('abort', onExternalAbort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      abortController.abort();
    }, timeoutMs);

    const model = options?.model ?? this.defaultModel;

    try {
      const stream = query({
        prompt: userText,
        options: {
          ...(model ? { model } : {}),
          // 기본은 replace(프리셋 미사용). GraphRAG 처럼 기존 `--append-system-prompt` 의미에 맞춰
          // 튜닝된 프롬프트는 append-to-preset 으로 동작을 보존한다.
          systemPrompt:
            options?.systemPromptMode === 'append-to-preset'
              ? { type: 'preset' as const, preset: 'claude_code' as const, append: systemPrompt }
              : systemPrompt,
          maxTurns: 1,
          allowedTools: [],
          // allowedTools: [] 만으로 host 도구가 확실히 막히는지는 SDK 구현에 달려 있다.
          // permissionMode 가 bypassPermissions 이므로 뚫릴 경우 자동 승인까지 되어버린다 —
          // 챗 경로와 동일한 블랙리스트(tool-policy.ts, single source of truth)를 함께 적용한다.
          disallowedTools: [...DISALLOWED_TOOLS],
          permissionMode: 'bypassPermissions',
          // 호스트 사용자의 ~/.claude/settings.json 상속 차단 (agent-sdk.ts 와 동일 이유).
          settingSources: [],
          includePartialMessages: false,
          abortController,
          env: buildCompletionEnv(
            { apiKey: this.apiKey, oauthToken: this.oauthToken },
            options?.maxOutputTokens,
          ),
        },
      });

      const consume = async (): Promise<CompletionResult> => {
        for await (const msg of stream as AsyncIterable<SDKMessage>) {
          if (msg.type !== 'result') continue;

          if (msg.subtype === 'success') {
            return {
              text: msg.result,
              usage: {
                inputTokens: totalInputTokens(msg.usage),
                outputTokens: msg.usage?.output_tokens ?? 0,
              },
            };
          }

          // error_during_execution / error_max_turns 등 — 원인 문자열을 그대로 노출한다.
          // (기존 llm-cli.ts 는 stderr 만 담아 "Not logged in" 같은 stdout 원인을 놓쳤다.)
          const errors = 'errors' in msg && Array.isArray(msg.errors) ? msg.errors.join('; ') : '';
          throw new Error(
            `[completion] SDK 실행 실패 (subtype=${msg.subtype})${errors ? `: ${errors}` : ''}`,
          );
        }

        throw new Error('[completion] SDK 스트림이 result 메시지 없이 종료되었습니다.');
      };

      // abort 는 SDK 가 스트림을 즉시 닫아주지 않을 수 있으므로 소비 루프와 경합시킨다.
      // (그렇지 않으면 타임아웃이 지난 뒤 도착한 응답이 성공으로 처리된다.)
      const aborted = new Promise<never>((_, reject) => {
        abortController.signal.addEventListener('abort', () => reject(ABORTED), { once: true });
      });

      return await Promise.race([consume(), aborted]);
    } catch (err) {
      // 타임아웃 메시지로 바꾸는 것은 "중단 때문에 실패한 경우"로 한정한다.
      // 무조건 덮어쓰면 타이머와 비슷한 시점에 도착한 진짜 원인(인증 실패 등)을 잃는다.
      if (err === ABORTED) {
        throw new Error(
          timedOut
            ? `[completion] 호출이 ${timeoutMs}ms 내에 끝나지 않아 중단했습니다.`
            : '[completion] 호출이 중단되었습니다.',
        );
      }
      throw err;
    } finally {
      clearTimeout(timer);
      options?.abortSignal?.removeEventListener('abort', onExternalAbort);
    }
  }
}
