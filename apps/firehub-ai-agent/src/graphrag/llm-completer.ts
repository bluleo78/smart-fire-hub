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
   * 요청 자격증명. MCP 도구 경로에서는 채팅 요청의 apiKey/oauthToken 이 전달되고,
   * 단독 스크립트에서는 생략되어 프로세스 환경 / CLI 키체인 인증으로 폴백한다.
   */
  credentials?: Pick<ProviderConfig, 'apiKey' | 'oauthToken'>;
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
