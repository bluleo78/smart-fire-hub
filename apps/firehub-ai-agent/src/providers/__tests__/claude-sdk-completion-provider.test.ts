import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Agent SDK query()를 목킹해 실제 CLI 실행 없이 옵션 구성·결과 추출·에러 처리만 검증한다.
const queryMock = vi.fn();
vi.mock('@anthropic-ai/claude-agent-sdk', () => ({
  query: (input: unknown) => queryMock(input),
}));

const { ClaudeSdkCompletionProvider, buildCompletionEnv } = await import(
  '../claude-sdk-completion-provider.js'
);

/** query()가 돌려주는 비동기 스트림을 흉내낸다. */
function streamOf(...messages: unknown[]) {
  return {
    async *[Symbol.asyncIterator]() {
      for (const m of messages) yield m;
    },
  };
}

function successResult(text: string) {
  return {
    type: 'result',
    subtype: 'success',
    result: text,
    usage: { input_tokens: 100, cache_read_input_tokens: 20, output_tokens: 50 },
  };
}

describe('buildCompletionEnv', () => {
  const ORIGINAL = { ...process.env };

  afterEach(() => {
    process.env = { ...ORIGINAL };
  });

  it('OAuth 토큰이 있으면 ANTHROPIC_API_KEY를 제거하고 OAuth를 설정한다', () => {
    process.env.ANTHROPIC_API_KEY = 'stale-env-key';

    const env = buildCompletionEnv({ apiKey: 'sk-abc', oauthToken: 'oauth-xyz' });

    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBe('oauth-xyz');
    expect(env.ANTHROPIC_API_KEY).toBeUndefined();
  });

  it('API 키만 있으면 ANTHROPIC_API_KEY를 설정한다', () => {
    const env = buildCompletionEnv({ apiKey: 'sk-abc' });

    expect(env.ANTHROPIC_API_KEY).toBe('sk-abc');
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
  });

  it('API 키 요청이면 컨테이너에 남은 낡은 OAuth 토큰을 제거한다', () => {
    // 제거하지 않으면 SDK 가 낡은 OAuth 를 우선해 요청이 지정한 키가 무시된다.
    process.env.CLAUDE_CODE_OAUTH_TOKEN = 'stale-container-oauth';

    const env = buildCompletionEnv({ apiKey: 'sk-abc' });

    expect(env.ANTHROPIC_API_KEY).toBe('sk-abc');
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
  });

  it('자격증명이 없으면 프로세스 환경을 그대로 둔다(환경/키체인 폴백)', () => {
    process.env.ANTHROPIC_API_KEY = 'env-key';

    const env = buildCompletionEnv(undefined);

    expect(env.ANTHROPIC_API_KEY).toBe('env-key');
  });

  it('공백 문자열 자격증명은 "없음"으로 취급한다', () => {
    process.env.ANTHROPIC_API_KEY = 'env-key';

    const env = buildCompletionEnv({ apiKey: '   ', oauthToken: '  ' });

    expect(env.ANTHROPIC_API_KEY).toBe('env-key');
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
  });

  it('process.env를 변경하지 않는다(동시 요청 간 자격증명 오염 방지)', () => {
    process.env.ANTHROPIC_API_KEY = 'env-key';

    buildCompletionEnv({ oauthToken: 'oauth-xyz' });

    expect(process.env.ANTHROPIC_API_KEY).toBe('env-key');
    expect(process.env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
  });

  it('중첩 세션 방지 변수를 제거한다', () => {
    process.env.CLAUDECODE = '1';
    process.env.CLAUDE_CODE_ENTRYPOINT = 'cli';

    const env = buildCompletionEnv({ apiKey: 'sk' });

    expect(env.CLAUDECODE).toBeUndefined();
    expect(env.CLAUDE_CODE_ENTRYPOINT).toBeUndefined();
  });

  it('maxOutputTokens를 CLAUDE_CODE_MAX_OUTPUT_TOKENS로 변환한다', () => {
    const env = buildCompletionEnv({ apiKey: 'sk' }, 16384);

    expect(env.CLAUDE_CODE_MAX_OUTPUT_TOKENS).toBe('16384');
  });
});

describe('ClaudeSdkCompletionProvider', () => {
  beforeEach(() => {
    queryMock.mockReset();
  });

  it('도구 없이 단발 실행하도록 query 옵션을 구성한다', async () => {
    queryMock.mockReturnValue(streamOf(successResult('응답')));

    const provider = new ClaudeSdkCompletionProvider(undefined, 'oauth-xyz', 'claude-sonnet-5');
    await provider.complete('시스템', '사용자');

    const input = queryMock.mock.calls[0][0] as {
      prompt: string;
      options: Record<string, unknown>;
    };
    expect(input.prompt).toBe('사용자');
    expect(input.options.maxTurns).toBe(1);
    expect(input.options.allowedTools).toEqual([]);
    // GraphRAG completer 가 MCP 도구 *안에서* 호출되므로 도구를 붙이면 재귀한다.
    expect(input.options.mcpServers).toBeUndefined();
    expect(input.options.settingSources).toEqual([]);
    // allowedTools: [] 만 믿지 않는다 — bypassPermissions 상태에서 뚫리면 자동 승인까지 된다.
    expect(input.options.disallowedTools).toEqual(expect.arrayContaining(['Write', 'Edit']));
    expect(input.options.model).toBe('claude-sonnet-5');
    expect((input.options.env as NodeJS.ProcessEnv).CLAUDE_CODE_OAUTH_TOKEN).toBe('oauth-xyz');
  });

  it('기본은 systemPrompt를 그대로(replace) 전달한다', async () => {
    queryMock.mockReturnValue(streamOf(successResult('ok')));

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);
    await provider.complete('시스템 프롬프트', 'user');

    const input = queryMock.mock.calls[0][0] as { options: Record<string, unknown> };
    expect(input.options.systemPrompt).toBe('시스템 프롬프트');
  });

  it('append-to-preset 모드는 claude_code 프리셋에 덧붙인다(기존 --append-system-prompt 의미)', async () => {
    queryMock.mockReturnValue(streamOf(successResult('ok')));

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);
    await provider.complete('시스템 프롬프트', 'user', { systemPromptMode: 'append-to-preset' });

    const input = queryMock.mock.calls[0][0] as { options: Record<string, unknown> };
    expect(input.options.systemPrompt).toEqual({
      type: 'preset',
      preset: 'claude_code',
      append: '시스템 프롬프트',
    });
  });

  it('result(success)에서 텍스트와 usage를 추출한다(캐시 토큰 합산)', async () => {
    queryMock.mockReturnValue(streamOf(successResult('추출 결과')));

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);
    const result = await provider.complete('시스템', '사용자');

    expect(result.text).toBe('추출 결과');
    expect(result.usage).toEqual({ inputTokens: 120, outputTokens: 50 });
  });

  it('result(error)면 subtype과 원인 문자열을 포함해 실패한다', async () => {
    queryMock.mockReturnValue(
      streamOf({
        type: 'result',
        subtype: 'error_during_execution',
        errors: ['Not logged in'],
        usage: {},
      }),
    );

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(provider.complete('시스템', '사용자')).rejects.toThrow(
      /error_during_execution.*Not logged in/,
    );
  });

  it('result 없이 스트림이 끝나면 실패한다', async () => {
    queryMock.mockReturnValue(streamOf({ type: 'system', subtype: 'init' }));

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(provider.complete('시스템', '사용자')).rejects.toThrow(/result 메시지 없이/);
  });

  it('타임아웃을 초과하면 중단 메시지로 실패한다', async () => {
    queryMock.mockReturnValue({
      async *[Symbol.asyncIterator]() {
        await new Promise((resolve) => setTimeout(resolve, 50));
        yield successResult('too late');
      },
    });

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(provider.complete('시스템', '사용자', { timeoutMs: 5 })).rejects.toThrow(
      /5ms 내에 끝나지 않아/,
    );
  });

  it('이미 중단된 abortSignal이면 SDK를 띄우지 않고 즉시 실패한다', async () => {
    const controller = new AbortController();
    controller.abort();

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(
      provider.complete('시스템', '사용자', { abortSignal: controller.signal }),
    ).rejects.toThrow(/이미 중단된/);
    expect(queryMock).not.toHaveBeenCalled();
  });

  it('타이머와 비슷한 시점에 실패해도 진짜 원인을 타임아웃 메시지로 덮지 않는다', async () => {
    queryMock.mockReturnValue({
      [Symbol.asyncIterator]: () => ({
        next: async () => {
          await new Promise((resolve) => setTimeout(resolve, 5));
          throw new Error('Invalid API key · Please run /login');
        },
      }),
    });

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(provider.complete('시스템', '사용자', { timeoutMs: 8 })).rejects.toThrow(
      /Invalid API key/,
    );
  });
});
