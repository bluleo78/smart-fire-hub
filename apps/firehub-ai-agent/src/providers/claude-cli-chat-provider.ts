import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import type { CliAgentOptions } from '../agent/agent-cli.js';
import { executeCliAgent } from '../agent/agent-cli.js';

export class ClaudeCliChatProvider implements ChatProvider {
  readonly name: string;

  constructor(
    private readonly useSubscription: boolean,
    private readonly apiKey?: string,
    private readonly cliOauthToken?: string,
  ) {
    this.name = useSubscription ? 'claude-cli' : 'claude-cli-api';
  }

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    const cliOptions: CliAgentOptions = {
      message: options.message,
      sessionId: options.sessionId,
      userId: options.userId,
      fileIds: options.fileIds,
      model: options.model,
      maxTurns: options.maxTurns,
      systemPrompt: options.systemPrompt,
      overrideSystemPrompt: options.overrideSystemPrompt,
      temperature: options.temperature,
      maxTokens: options.maxTokens,
      useSubscription: this.useSubscription,
      apiKey: this.apiKey,
      cliOauthToken: this.cliOauthToken,
      abortSignal: options.abortSignal,
    };
    yield* executeCliAgent(cliOptions);
  }
}
