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

describe('ProviderFactory sdk 케이스 (Task 1: OAuth 인증)', () => {
  it('apiKey만 있어도 생성된다', () => {
    expect(ProviderFactory.createChatProvider({ agentType: 'sdk', apiKey: 'sk-1' }).name).toBe(
      'claude-sdk',
    );
  });
  it('oauthToken만 있어도 생성된다', () => {
    expect(
      ProviderFactory.createChatProvider({ agentType: 'sdk', oauthToken: 'oat-1' }).name,
    ).toBe('claude-sdk');
  });
  it('apiKey·oauthToken 모두 없으면 throw', () => {
    expect(() => ProviderFactory.createChatProvider({ agentType: 'sdk' })).toThrow();
  });
});
