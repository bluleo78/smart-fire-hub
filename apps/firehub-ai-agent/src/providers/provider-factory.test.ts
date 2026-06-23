import { describe, it, expect } from 'vitest';
import { ProviderFactory } from './provider-factory.js';
import { OpenCodeChatProvider } from './opencode-chat-provider.js';

describe('ProviderFactory opencode', () => {
  it('agentType=opencode 이면 OpenCodeChatProvider 를 생성한다', () => {
    const provider = ProviderFactory.createChatProvider({ agentType: 'opencode' });
    expect(provider).toBeInstanceOf(OpenCodeChatProvider);
    expect(provider.name).toBe('opencode');
  });
});
