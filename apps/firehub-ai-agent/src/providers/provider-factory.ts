import type {
  ChatProvider,
  ClassifyProvider,
  CompletionProvider,
  ProviderConfig,
} from './types.js';
import { ClaudeSdkChatProvider } from './claude-sdk-chat-provider.js';
import { ClaudeSdkCompletionProvider } from './claude-sdk-completion-provider.js';
import { ClaudeCliChatProvider } from './claude-cli-chat-provider.js';
import { ClaudeClassifyProvider } from './claude-classify-provider.js';
import { OpenCodeChatProvider } from './opencode-chat-provider.js';
import { OpenAICompatCompletionProvider } from './openai-compat-completion-provider.js';
import { DEFAULT_MODEL } from '../constants.js';
import { resolveClaudeCredential } from '../agent/claude-child-env.js';
import { MissingAiCredentialError } from '../agent/ai-auth-failure.js';

export class ProviderFactory {
  static createChatProvider(config: ProviderConfig): ChatProvider {
    switch (config.agentType) {
      // Anthropic 계열 세 유형의 자격증명 검사는 자식 env 와 같은 판정(resolveClaudeCredential — 공백은
      // "없음")을 쓰고, 한국어 안내·코드를 담은 MissingAiCredentialError 로 실패한다(#708) — 채팅은 그
      // 안내를 그대로 보여 주고(AdminActionableError), 프로액티브는 502 + 코드로 응답한다.
      case 'sdk':
        // sdk는 API 키 또는 OAuth 토큰 중 하나만 있어도 동작(OAuth 우선).
        if (!resolveClaudeCredential(config)) throw new MissingAiCredentialError();
        return new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL, config.oauthToken);
      case 'cli':
        // #708: 구독(cli) 모드도 OAuth 토큰이 필수다 — 예전엔 검사가 없어 토큰 없이 claude CLI 를
        // 띄웠고, 그 CLI 는 호스트 키체인/~/.claude 로그인으로 조용히 인증됐다(env 로 가릴 수 없다).
        if (!resolveClaudeCredential({ oauthToken: config.oauthToken })) throw new MissingAiCredentialError();
        return new ClaudeCliChatProvider(true, undefined, config.oauthToken);
      case 'cli-api':
        if (!resolveClaudeCredential({ apiKey: config.apiKey })) throw new MissingAiCredentialError();
        return new ClaudeCliChatProvider(false, config.apiKey);
      case 'opencode':
        // provider 자격증명(baseURL/apiKey)은 테넌트별로 갈린다(옵션 3 폐기, 2026-09-19
        // 이슈 #693) — buildOpenCodeConfig 가 배포 측 전역 opencode 설정으로 조용히 떨어지지
        // 않도록, 불완전한 설정은 여기서 먼저 크게 실패한다(createCompletionProvider 의 baseUrl
        // 가드와 같은 정신).
        if (!config.providerId || !config.baseUrl) {
          throw new Error(
            '[opencode] providerId 또는 baseUrl 이 없습니다 — provider 설정이 불완전합니다.',
          );
        }
        return new OpenCodeChatProvider({
          providerId: config.providerId,
          baseUrl: config.baseUrl,
          apiKey: config.apiKey ?? '',
          reasoningEffort: config.reasoningEffort,
        });
      default:
        throw new Error(`Unknown agent type: ${(config as { agentType: string }).agentType}`);
    }
  }

  /**
   * 분류 프로바이더 생성. 자격증명은 요청 바디로 전달되므로 팩토리 인자가 필요 없다.
   * (이전 시그니처의 apiBaseUrl/internalToken 은 ai-agent 가 설정을 역조회할 때만 쓰였다.)
   */
  static createClassifyProvider(): ClassifyProvider {
    return new ClaudeClassifyProvider();
  }

  /**
   * 단발 completion 프로바이더 생성.
   *
   * **채팅과 달리 agentType 분기가 opencode 하나뿐이다** — 예전 주석은 "채팅과 달리 agentType
   * 분기가 없다"였는데, 그 근거("자격증명만 흘려받아 인증 경로가 갈라지지 않도록")는 자격증명이
   * 사실상 한 종류(Anthropic API 키/OAuth 토큰)였을 때만 성립했다. opencode 자격증명은 OpenAI
   * 호환 키라 Claude SDK 에 실으면 401 이거나, 비어 있으면 컨테이너의 ambient
   * ANTHROPIC_API_KEY 로 새어 플랫폼 계정에 과금되는 사고(6b1c6383)가 재현된다 — 분류
   * (classification-service.ts) 와 GraphRAG 추출(llm-completer.ts) 둘 다 이 함수를 거친다.
   * sdk/cli/cli-api 는 여전히 구분 없이 Claude SDK 경로 하나로 통일한다(그 셋은 전부 Anthropic
   * 자격증명이라 인증 경로가 갈라질 이유가 없다).
   *
   * **agentType 이 없거나 opencode 가 아닌 모든 값은 Claude SDK 로 간다** — 여기서 "모르는
   * agentType 은 fail-closed" 를 강제하지 않는다. 그 강제는 라우트 경계(routes/*.ts, 요청
   * 바디→ProviderConfig 매핑)의 몫이다. 이 팩토리에는 agentType 자체가 없는 정당한 호출부가
   * 있다 — `mcp/stdio-server.ts` 는 부모(agent-cli.ts buildMcpConfig)가 명시적으로 심어 준
   * 프로세스 env(ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN)만으로 자격증명을 넘기고, 단독
   * 스크립트는 진입점에서 읽은 자격증명만 넘긴다. 여기서 엄격하게 거부하면 그 호출부가 깨진다.
   *
   * config 는 필수다(#708) — 예전의 무인자 호출은 프로세스 환경이나 로컬 CLI 키체인으로 폴백했다.
   * 자격증명이 비어 있어도 **생성은 한다**: MCP 자식(stdio-server)은 기동 시점에 도구를 등록하며
   * 이 함수를 부르므로, 여기서 던지면 GraphRAG 와 무관한 도구까지 전부 죽는다. 대신 첫 complete()
   * 호출이 MissingAiCredentialError 로 실패한다(ClaudeSdkCompletionProvider → buildCompletionEnv).
   */
  static createCompletionProvider(
    config: Partial<
      Pick<
        ProviderConfig,
        'agentType' | 'apiKey' | 'oauthToken' | 'model' | 'baseUrl' | 'providerId' | 'reasoningEffort'
      >
    >,
  ): CompletionProvider {
    // reasoningEffort 는 여기까지는 도달하지만 소비되지 않는다 — OpenAICompatCompletionProvider
    // 생성자에 넘기지 않는다. firehub-ai-agent 전체에 이 값을 실제로 쓰는 경로가 아직 없다
    // (설계서 §322, Claude 계열 추론 강도조차 전달 경로가 없어 별도 이슈로 남겨졌다). 타입에는
    // 남겨 둔다 — 그 이슈가 풀릴 때 이 시그니처를 다시 넓히지 않아도 되게 하기 위함이다.
    if (config.agentType === 'opencode') {
      // Opencode.apiKey 는 OpenAI 호환 키다. baseUrl 없이 Claude SDK 로 흘리면 위 규약대로
      // 6b1c6383 이 재현되므로, baseUrl 이 없는 opencode 설정은 여기서 크게 실패한다
      // (spec §98 "알 수 없는 agentType 은 fail-closed" 와 같은 정신 — 여기선 "불완전한
      // opencode 설정"이 그 자리를 대신한다. ambient 폴백으로 조용히 넘어가지 않는다).
      if (!config.baseUrl) {
        throw new Error(
          '[completion] opencode 자격증명에 baseUrl 이 없습니다 — provider 설정이 불완전합니다.',
        );
      }
      return new OpenAICompatCompletionProvider(
        config.baseUrl,
        config.apiKey ?? '',
        config.model ?? '',
      );
    }
    return new ClaudeSdkCompletionProvider(config.apiKey, config.oauthToken, config.model);
  }
}
