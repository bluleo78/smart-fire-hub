import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import { executeOpenCodeAgent } from '../agent/agent-opencode.js';

/** OpenCode CLI 기반 채팅 프로바이더. */
export class OpenCodeChatProvider implements ChatProvider {
  readonly name = 'opencode';
  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    yield* executeOpenCodeAgent(options);
  }
}
