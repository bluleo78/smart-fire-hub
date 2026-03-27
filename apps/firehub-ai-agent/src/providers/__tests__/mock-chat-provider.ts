import type { ChatProvider, ChatProviderOptions, SSEEvent } from '../types.js';

export class MockChatProvider implements ChatProvider {
  readonly name = 'mock';
  readonly calls: ChatProviderOptions[] = [];

  constructor(private readonly responses: SSEEvent[][] = [[{ type: 'done' }]]) {}

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    this.calls.push(options);
    const events = this.responses.shift() || [{ type: 'done' }];
    for (const event of events) {
      yield event;
    }
  }
}
