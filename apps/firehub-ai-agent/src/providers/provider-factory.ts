import type { ChatProvider, ClassifyProvider, ProviderConfig } from './types.js';
import { ClaudeSdkChatProvider } from './claude-sdk-chat-provider.js';
import { ClaudeCliChatProvider } from './claude-cli-chat-provider.js';
import { ClaudeClassifyProvider } from './claude-classify-provider.js';
import { DEFAULT_MODEL } from '../constants.js';

export class ProviderFactory {
  static createChatProvider(config: ProviderConfig): ChatProvider {
    switch (config.agentType) {
      case 'sdk':
        if (!config.apiKey) throw new Error('API key required for SDK mode');
        return new ClaudeSdkChatProvider(config.apiKey, config.model || DEFAULT_MODEL);
      case 'cli':
        return new ClaudeCliChatProvider(true, undefined, config.cliOauthToken);
      case 'cli-api':
        if (!config.apiKey) throw new Error('API key required for CLI-API mode');
        return new ClaudeCliChatProvider(false, config.apiKey);
      default:
        throw new Error(`Unknown agent type: ${(config as { agentType: string }).agentType}`);
    }
  }

  static createClassifyProvider(apiBaseUrl: string, internalToken: string): ClassifyProvider {
    return new ClaudeClassifyProvider(apiBaseUrl, internalToken);
  }
}
