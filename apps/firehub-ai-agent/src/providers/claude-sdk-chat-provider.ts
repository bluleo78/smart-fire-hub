import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import type { AgentOptions } from '../agent/agent-sdk.js';
import { executeAgent } from '../agent/agent-sdk.js';

export class ClaudeSdkChatProvider implements ChatProvider {
  readonly name = 'claude-sdk';

  constructor(
    private readonly apiKey: string | undefined,
    private readonly defaultModel: string,
    private readonly oauthToken?: string,
  ) {}

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    const agentOptions: AgentOptions = {
      message: options.message,
      tenantId: options.tenantId,
      sessionId: options.sessionId,
      userId: options.userId,
      fileIds: options.fileIds,
      model: options.model || this.defaultModel,
      maxTurns: options.maxTurns,
      systemPrompt: options.systemPrompt,
      overrideSystemPrompt: options.overrideSystemPrompt,
      apiKey: this.apiKey,
      oauthToken: this.oauthToken,
      abortSignal: options.abortSignal,
      aiPurpose: options.aiPurpose,
      proactiveExecutionId: options.proactiveExecutionId,
      dbDialect: options.dbDialect,
      rowSearch: options.rowSearch,
      // 프로액티브 리포트 실행이면 report-writer 의 reportDir 저장(Write)을 연다.
      reportDir: options.reportDir,
    };
    yield* executeAgent(agentOptions);
  }
}
