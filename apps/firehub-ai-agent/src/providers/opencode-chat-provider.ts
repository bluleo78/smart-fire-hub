import type { ChatProvider, ChatProviderOptions, SSEEvent } from './types.js';
import { executeOpenCodeAgent, type OpenCodeCredentials } from '../agent/agent-opencode.js';

/**
 * OpenCode CLI 기반 채팅 프로바이더.
 *
 * provider 자격증명(providerId/baseUrl/apiKey/reasoningEffort)은 생성 시점(ProviderFactory)에
 * 받아 인스턴스에 고정한다 — ClaudeSdkChatProvider 가 apiKey/model/oauthToken 을 생성자에서
 * 받는 것과 같은 패턴이다. ChatProviderOptions(execute 의 인자)는 요청마다 바뀌는 메시지/세션
 * 값만 담고, 테넌트별로 고정된 provider 설정과 섞지 않는다.
 */
export class OpenCodeChatProvider implements ChatProvider {
  readonly name = 'opencode';

  constructor(private readonly credentials: OpenCodeCredentials) {}

  async *execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent> {
    yield* executeOpenCodeAgent(options, this.credentials);
  }
}
