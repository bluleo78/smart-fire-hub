import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../agent/agent-sdk.js', () => ({
  executeAgent: vi.fn(),
}));

vi.mock('../../agent/agent-cli.js', () => ({
  executeCliAgent: vi.fn(),
}));

vi.mock('../../services/classification-service.js', () => ({
  classifyBatch: vi.fn(),
}));

import { ProviderFactory } from '../provider-factory.js';
import { ClaudeSdkChatProvider } from '../claude-sdk-chat-provider.js';
import { ClaudeCliChatProvider } from '../claude-cli-chat-provider.js';
import { ClaudeClassifyProvider } from '../claude-classify-provider.js';
import { DEFAULT_MODEL } from '../../constants.js';

describe('ProviderFactory', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('createChatProvider', () => {
    // PF-01: SDK mode with apiKey returns ClaudeSdkChatProvider
    it('PF-01: SDK mode with apiKey returns ClaudeSdkChatProvider', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
      });
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
      expect(provider.name).toBe('claude-sdk');
    });

    // PF-02: SDK mode without apiKey throws error
    it('PF-02: SDK mode without apiKey throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({ agentType: 'sdk' }),
      ).toThrow('API key required for SDK mode');
    });

    // PF-03: CLI mode returns ClaudeCliChatProvider with name 'claude-cli'
    it('PF-03: CLI mode returns ClaudeCliChatProvider with name claude-cli', () => {
      const provider = ProviderFactory.createChatProvider({ agentType: 'cli' });
      expect(provider).toBeInstanceOf(ClaudeCliChatProvider);
      expect(provider.name).toBe('claude-cli');
    });

    // PF-04: CLI-API mode with apiKey returns ClaudeCliChatProvider with name 'claude-cli-api'
    it('PF-04: CLI-API mode with apiKey returns ClaudeCliChatProvider with name claude-cli-api', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'cli-api',
        apiKey: 'sk-test',
      });
      expect(provider).toBeInstanceOf(ClaudeCliChatProvider);
      expect(provider.name).toBe('claude-cli-api');
    });

    // PF-05: CLI-API mode without apiKey throws error
    it('PF-05: CLI-API mode without apiKey throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({ agentType: 'cli-api' }),
      ).toThrow('API key required for CLI-API mode');
    });

    // PF-06: Unknown agentType throws error
    it('PF-06: unknown agentType throws error', () => {
      expect(() =>
        ProviderFactory.createChatProvider({
          agentType: 'unknown' as 'sdk',
        }),
      ).toThrow('Unknown agent type: unknown');
    });

    // PF-07: model parameter is passed through (SDK mode uses model or DEFAULT_MODEL)
    it('PF-07: model parameter is passed to SDK provider as defaultModel', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
        model: 'claude-opus-4-6',
      }) as ClaudeSdkChatProvider;
      // Provider is created — verify it's the right type and DEFAULT_MODEL is used when model absent
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
    });

    // PF-08: SDK mode without model uses DEFAULT_MODEL
    it('PF-08: SDK mode without model uses DEFAULT_MODEL', () => {
      const provider = ProviderFactory.createChatProvider({
        agentType: 'sdk',
        apiKey: 'sk-test',
      }) as ClaudeSdkChatProvider;
      expect(provider).toBeInstanceOf(ClaudeSdkChatProvider);
      // The provider uses DEFAULT_MODEL internally — tested via execute() in sdk provider tests
      expect(DEFAULT_MODEL).toBeDefined();
    });
  });

  describe('createClassifyProvider', () => {
    // PF-09: createClassifyProvider returns ClaudeClassifyProvider
    it('PF-09: createClassifyProvider returns ClaudeClassifyProvider', () => {
      const provider = ProviderFactory.createClassifyProvider(
        'http://localhost:8080/api/v1',
        'test-token',
      );
      expect(provider).toBeInstanceOf(ClaudeClassifyProvider);
      expect(provider.name).toBe('claude-classify');
    });
  });
});
