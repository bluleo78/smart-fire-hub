import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ProviderFactory를 목킹해 실제 Agent SDK 실행 없이 어댑터 동작만 검증한다.
const completeMock = vi.fn();
const createCompletionProviderMock = vi.fn((_config?: unknown) => ({
  name: 'mock-completion',
  complete: completeMock,
}));
vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createCompletionProvider: (config?: unknown) => createCompletionProviderMock(config),
  },
}));

describe('createCompleter', () => {
  beforeEach(() => {
    completeMock.mockReset();
    createCompletionProviderMock.mockClear();
    delete process.env.AI_CLI_MODEL;
  });

  it('요청 자격증명을 CompletionProvider 생성에 그대로 전달한다', async () => {
    const { createCompleter } = await import('./llm-completer.js');
    createCompleter({
      model: 'claude-haiku-4-5',
      credentials: { oauthToken: 'oauth-xyz', apiKey: 'sk-abc' },
    });

    expect(createCompletionProviderMock).toHaveBeenCalledWith({
      apiKey: 'sk-abc',
      oauthToken: 'oauth-xyz',
      model: 'claude-haiku-4-5',
    });
  });

  // Task 8: agentType/baseUrl/providerId 가 이 함수를 거치며 사라지지 않는지 검증한다.
  // classification-service.ts 와 같은 팩토리(ProviderFactory.createCompletionProvider)를 거치고
  // 같은 opencode 분기를 타야 한다(설계서 "테스트로 고정할 것" 3번 — 두 호출부 모두 같은 분기).
  it('opencode 자격증명(agentType/baseUrl/providerId/reasoningEffort)을 CompletionProvider 생성에 그대로 전달한다', async () => {
    const { createCompleter } = await import('./llm-completer.js');
    createCompleter({
      model: 'openai/gpt-4o',
      credentials: {
        agentType: 'opencode',
        apiKey: 'openai-key',
        baseUrl: 'https://x/v1',
        providerId: 'openai',
        reasoningEffort: 'medium',
      },
    });

    expect(createCompletionProviderMock).toHaveBeenCalledWith({
      apiKey: 'openai-key',
      oauthToken: undefined,
      agentType: 'opencode',
      baseUrl: 'https://x/v1',
      providerId: 'openai',
      reasoningEffort: 'medium',
      model: 'openai/gpt-4o',
    });
  });

  // Fix round 2 (리뷰 지적 #1): 6b1c6383 이 실제로 발생한 지점은 라우트도 팩토리도 아니라
  // 이 함수(그리고 classification-service.ts) 자신이다 — opencode 자격증명에서 apiKey 가
  // 빠졌을 때 이 함수가 스스로 ambient 로 메워 넣지 않는지 여기서 직접 고정한다. 팩토리/
  // provider 계층 테스트(mutant 9·10)는 이 함수가 이미 올바른 값을 넘겨줬다고 가정하므로,
  // 이 함수 자체가 ambient 를 끼워 넣는 회귀는 그 테스트들로 잡히지 않는다.
  describe('ambient ANTHROPIC_API_KEY 미사용 (opencode, apiKey 생략)', () => {
    const ORIGINAL = process.env.ANTHROPIC_API_KEY;

    beforeEach(() => {
      process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    });

    afterEach(() => {
      if (ORIGINAL === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = ORIGINAL;
    });

    it('apiKey 를 생략한 opencode 자격증명은 ambient 로 메워지지 않고 undefined 그대로 전달된다', async () => {
      const { createCompleter } = await import('./llm-completer.js');
      createCompleter({
        model: 'openai/gpt-4o',
        credentials: { agentType: 'opencode', baseUrl: 'https://x/v1', providerId: 'openai' },
      });

      const calledWith = createCompletionProviderMock.mock.calls[0][0] as { apiKey?: string };
      expect(calledWith.apiKey).toBeUndefined();
    });
  });

  // #708: 자격증명이 없으면 생성은 되지만(MCP 자식 기동 시 도구 등록이 죽지 않도록) 호출 시점에
  // ambient 환경/키체인으로 폴백하지 않고 명확히 실패한다. ambient 값을 심어 둬도 쓰이지 않는다.
  it('자격증명이 없으면 호출 시 ambient 값이 있어도 provider 의 자격증명 없음 오류가 그대로 드러난다', async () => {
    const saved = process.env.ANTHROPIC_API_KEY;
    process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    try {
      // 팩토리 목 대신 실제 ClaudeSdkCompletionProvider 를 쓴다 — 자격증명 검사는 이제 provider 가 자식 env 를
      // 만들 때 한 곳에서 한다(buildClaudeChildEnv). env 조립이 query() 호출 전에 던지므로 SDK 는 뜨지 않는다.
      const { ClaudeSdkCompletionProvider } = await vi.importActual<
        typeof import('../providers/claude-sdk-completion-provider.js')
      >('../providers/claude-sdk-completion-provider.js');
      createCompletionProviderMock.mockImplementation((config?: unknown) => {
        const c = (config ?? {}) as { apiKey?: string; oauthToken?: string };
        return new ClaudeSdkCompletionProvider(c.apiKey, c.oauthToken) as never;
      });
      const { createCompleter } = await import('./llm-completer.js');
      const complete = createCompleter();
      await expect(complete('sys', 'user')).rejects.toThrow(/AI 자격증명/);
      expect(completeMock).not.toHaveBeenCalled();
    } finally {
      createCompletionProviderMock.mockImplementation((_config?: unknown) => ({
        name: 'mock-completion',
        complete: completeMock,
      }));
      if (saved === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = saved;
    }
  });

  it('opencode 자격증명은 apiKey 가 비어 있어도 호출 시 막지 않는다 (키 없는 호환 엔드포인트)', async () => {
    completeMock.mockResolvedValue({ text: 'ok', usage: { inputTokens: 1, outputTokens: 1 } });
    const { createCompleter } = await import('./llm-completer.js');
    const complete = createCompleter({
      credentials: { agentType: 'opencode', baseUrl: 'http://llm.local/v1', providerId: 'local' },
    });
    await expect(complete('sys', 'user')).resolves.toBe('ok');
  });

  it('model 미지정 시 AI_CLI_MODEL 환경변수를 사용한다', async () => {
    process.env.AI_CLI_MODEL = 'claude-sonnet-5';
    const { createCompleter } = await import('./llm-completer.js');
    createCompleter();

    expect(createCompletionProviderMock).toHaveBeenCalledWith(
      expect.objectContaining({ model: 'claude-sonnet-5' }),
    );
  });

  it('CompleteFn은 provider 결과의 text만 반환한다', async () => {
    completeMock.mockResolvedValue({
      text: '추출 결과',
      usage: { inputTokens: 10, outputTokens: 5 },
    });

    const { createCompleter } = await import('./llm-completer.js');
    const complete = createCompleter({ credentials: { oauthToken: 'oauth-xyz' } });

    await expect(complete('시스템 프롬프트', '사용자 텍스트')).resolves.toBe('추출 결과');
    // 기존 `claude -p --append-system-prompt` 의미 보존 — 프롬프트 튜닝 전제가 바뀌지 않도록.
    expect(completeMock).toHaveBeenCalledWith('시스템 프롬프트', '사용자 텍스트', {
      systemPromptMode: 'append-to-preset',
    });
  });

  it('provider가 실패하면 에러를 그대로 전파한다', async () => {
    completeMock.mockRejectedValue(new Error('[completion] SDK 실행 실패 (subtype=error_during_execution): Not logged in'));

    const { createCompleter } = await import('./llm-completer.js');
    const complete = createCompleter({ credentials: { oauthToken: 'oauth-xyz' } });

    await expect(complete('sys', 'user')).rejects.toThrow(/Not logged in/);
  });
});
