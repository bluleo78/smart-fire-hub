import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import type { AgentOptions } from '../agent/agent-sdk.js';
import { executeAgent } from '../agent/agent-sdk.js';

export class ClaudeSdkChatProvider implements ChatProvider {
  readonly name = 'claude-sdk';

  constructor(
    private readonly apiKey: string,
    private readonly defaultModel: string,
  ) {}

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    const agentOptions: AgentOptions = {
      message: options.message,
      sessionId: options.sessionId,
      userId: options.userId,
      fileIds: options.fileIds,
      model: options.model || this.defaultModel,
      maxTurns: options.maxTurns,
      systemPrompt: options.systemPrompt,
      temperature: options.temperature,
      maxTokens: options.maxTokens,
      apiKey: this.apiKey,
      abortSignal: options.abortSignal,
    };
    yield* executeAgent(agentOptions);
  }
}
