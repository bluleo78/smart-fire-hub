import { describe, it, expect, vi, beforeEach } from 'vitest';

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

  it('자격증명이 없으면 undefined로 생성해 환경 폴백에 맡긴다(단독 스크립트 경로)', async () => {
    const { createCompleter } = await import('./llm-completer.js');
    createCompleter();

    expect(createCompletionProviderMock).toHaveBeenCalledWith({
      apiKey: undefined,
      oauthToken: undefined,
      model: undefined,
    });
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
    const complete = createCompleter();

    await expect(complete('시스템 프롬프트', '사용자 텍스트')).resolves.toBe('추출 결과');
    // 기존 `claude -p --append-system-prompt` 의미 보존 — 프롬프트 튜닝 전제가 바뀌지 않도록.
    expect(completeMock).toHaveBeenCalledWith('시스템 프롬프트', '사용자 텍스트', {
      systemPromptMode: 'append-to-preset',
    });
  });

  it('provider가 실패하면 에러를 그대로 전파한다', async () => {
    completeMock.mockRejectedValue(new Error('[completion] SDK 실행 실패 (subtype=error_during_execution): Not logged in'));

    const { createCompleter } = await import('./llm-completer.js');
    const complete = createCompleter();

    await expect(complete('sys', 'user')).rejects.toThrow(/Not logged in/);
  });
});
