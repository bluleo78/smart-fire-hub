// GraphRAG 추출용 LLM 호출을 providers/ 의 CompletionProvider 에 위임한다.
//
// 이력: 원래 axios 로 x-api-key 를 직접 호출했으나 prod 는 ai.api_key 가 비어 있고
// ai.cli_oauth_token(OAuth)만 설정되어 있어 동작하지 않았다. 그 대응으로 claude CLI 를
// 직접 spawn 하도록 바꿨는데, 이는 providers/ 추상화를 통째로 우회해 인증 경로를 갈라놓았고
// 요청별 자격증명이 전달되지 않아 컨테이너 고정 env 에만 의존하는 문제를 낳았다.
// 이제는 채팅 경로와 동일한 Agent SDK 기반 CompletionProvider 를 사용한다.
import { ProviderFactory } from '../providers/index.js';
import type { ProviderConfig } from '../providers/index.js';

// (systemPrompt, userText) → LLM 응답 텍스트. extractor.ts가 이 함수를 주입받아 순수/테스트 가능하게 유지한다.
export type CompleteFn = (systemPrompt: string, userText: string) => Promise<string>;

export interface CompleterOptions {
  model?: string;
  /**
   * 요청 자격증명. MCP 도구 경로에서는 채팅 요청의 자격증명이 전달되고, 단독 스크립트는 진입점에서
   * 읽은 값을 명시적으로 넘긴다. 비어 있으면 호출 시점에 provider 가 MissingAiCredentialError 로
   * 실패한다(#708) — 프로세스 환경이나 CLI 키체인으로 폴백하지 않는다. 생성 시점엔 던지지 않는다:
   * createCompleter 는 MCP 자식 기동 중 도구 등록에서 불리므로 GraphRAG 와 무관한 도구까지 죽는다.
   *
   * agentType/baseUrl/providerId 를 포함하는 이유(Task 8): classification-service.ts 와 이
   * 함수(llm-completer.ts:30, 설계서가 명시한 두 completion 호출부)가 같은
   * ProviderFactory.createCompletionProvider 를 거치고 같은 opencode 분기를 타야 한다.
   * opencode 로 설정한 테넌트의 챗은 OpenCode CLI 를 스폰하는 별도 경로를 타지만, 그 CLI 가
   * 다시 spawn 하는 firehub MCP 자식(stdio-server.ts)이 이 함수를 GraphRAG 도구 경유로 호출할
   * 때는 opencode 의 provider 자격증명을 채워 보낸다(Ruling #30, stdio-server.ts 의
   * resolveStdioCredentials 참고) — "이 값을 실제로 채워 보내는 호출부는 없다"던 이전 상태는
   * 그 배선이 완성되며 끝났다.
   */
  credentials?: Partial<
    Pick<
      ProviderConfig,
      'apiKey' | 'oauthToken' | 'agentType' | 'baseUrl' | 'providerId' | 'reasoningEffort'
    >
  >;
}

/**
 * CompletionProvider 를 GraphRAG 소비자들이 쓰는 CompleteFn 형태로 감싼다.
 *
 * extractor / semantic-link / mapping-inference / ontology-inference 4곳이 이 시그니처를
 * 주입받으므로 형태를 유지해 호출부·테스트를 건드리지 않는다.
 */
export function createCompleter(opts?: CompleterOptions): CompleteFn {
  const provider = ProviderFactory.createCompletionProvider({
    apiKey: opts?.credentials?.apiKey,
    oauthToken: opts?.credentials?.oauthToken,
    agentType: opts?.credentials?.agentType,
    baseUrl: opts?.credentials?.baseUrl,
    providerId: opts?.credentials?.providerId,
    reasoningEffort: opts?.credentials?.reasoningEffort,
    model: opts?.model ?? process.env.AI_CLI_MODEL,
  });

  return async (systemPrompt: string, userText: string): Promise<string> => {
    // 기존 구현이 `claude -p --append-system-prompt` 였으므로 프롬프트들이 프리셋 위에 덧붙는 전제로
    // 튜닝돼 있다. 출력 형식(JSON 등) 동작이 바뀌지 않도록 같은 의미를 유지한다.
    const result = await provider.complete(systemPrompt, userText, {
      systemPromptMode: 'append-to-preset',
    });
    return result.text;
  };
}
