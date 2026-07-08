import type { ChatProvider, ClassifyProvider, ProviderConfig } from './types.js';
import { ClaudeSdkChatProvider } from './claude-sdk-chat-provider.js';
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

  static createClassifyProvider(apiBaseUrl: string, internalToken: string): ClassifyProvider {
    return new ClaudeClassifyProvider(apiBaseUrl, internalToken);
  }
}
