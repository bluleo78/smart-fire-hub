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
import { DEFAULT_MODEL } from '../constants.js';

export class ProviderFactory {
  static createChatProvider(config: ProviderConfig): ChatProvider {
    switch (config.agentType) {
      case 'sdk':
        // sdk는 API 키 또는 OAuth 토큰 중 하나만 있어도 동작(OAuth 우선).
        if (!config.apiKey && !config.oauthToken)
          throw new Error('API key or OAuth token required for SDK mode');
        return new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL, config.oauthToken);
      case 'cli':
        return new ClaudeCliChatProvider(true, undefined, config.oauthToken);
      case 'cli-api':
        if (!config.apiKey) throw new Error('API key required for CLI-API mode');
        return new ClaudeCliChatProvider(false, config.apiKey);
      case 'opencode':
        // 인증은 배포 환경 opencode auth 에 의존(옵션 3) — 키 주입 없음
        return new OpenCodeChatProvider();
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
   * 채팅과 달리 agentType 분기가 없다 — GraphRAG 추출·분류 같은 내부 호출은 사용자가 고른
   * 에이전트 종류(cli/sdk/opencode)와 무관하게 SDK 경로 하나로 통일한다. 자격증명만 요청에서
   * 흘려받아 인증 경로가 갈라지지 않도록 한다.
   *
   * config 를 생략하면 자격증명 없이 생성되어 프로세스 환경(ANTHROPIC_API_KEY /
   * CLAUDE_CODE_OAUTH_TOKEN)이나 로컬 CLI 키체인 인증으로 폴백한다 — 단독 스크립트용.
   */
  static createCompletionProvider(
    config?: Pick<ProviderConfig, 'apiKey' | 'oauthToken' | 'model'>,
  ): CompletionProvider {
    return new ClaudeSdkCompletionProvider(config?.apiKey, config?.oauthToken, config?.model);
  }
}
