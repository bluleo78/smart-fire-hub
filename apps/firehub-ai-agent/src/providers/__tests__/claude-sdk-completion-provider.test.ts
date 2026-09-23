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

  // #708: 자격증명이 없으면 ambient 환경/키체인에 맡기지 않고 실패한다.
  it('자격증명이 없으면 ambient 값이 있어도 실패한다(환경/키체인 폴백 없음)', () => {
    process.env.ANTHROPIC_API_KEY = 'env-key';

    expect(() => buildCompletionEnv(undefined)).toThrow(/AI 자격증명/);
  });

  it('공백 문자열 자격증명은 "없음"으로 취급해 실패한다', () => {
    process.env.ANTHROPIC_API_KEY = 'env-key';

    expect(() => buildCompletionEnv({ apiKey: '   ', oauthToken: '  ' })).toThrow(/AI 자격증명/);
  });

  // #708: 요청 자격증명이 있을 때도 다른 ambient 인증 경로(Bearer 토큰·엔드포인트·Bedrock 전환·AWS
  // 체인)는 자식에 도달하지 않는다. #711: 재시도 횟수가 명시된다.
  it('ambient 인증 경로를 전부 걷어내고 재시도 횟수를 명시한다', () => {
    process.env.ANTHROPIC_AUTH_TOKEN = 'ambient-bearer';
    process.env.ANTHROPIC_BASE_URL = 'https://ambient.example';
    process.env.CLAUDE_CODE_USE_BEDROCK = '1';
    process.env.AWS_ACCESS_KEY_ID = 'AKIA-ambient';
    process.env.CLAUDE_CODE_OAUTH_TOKEN = 'ambient-oauth';

    const env = buildCompletionEnv({ apiKey: 'sk-request' });

    expect(env.ANTHROPIC_API_KEY).toBe('sk-request');
    expect(env.ANTHROPIC_AUTH_TOKEN).toBeUndefined();
    expect(env.ANTHROPIC_BASE_URL).toBeUndefined();
    expect(env.CLAUDE_CODE_USE_BEDROCK).toBeUndefined();
    expect(env.AWS_ACCESS_KEY_ID).toBeUndefined();
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
    expect(env.CLAUDE_CODE_MAX_RETRIES).toBe('2');
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

  // #708: 자격증명 없는 provider 는 SDK 를 띄우지 않고 실패한다(키체인 로그인은 env 로 가릴 수 없다).
  it('자격증명이 없으면 query 를 호출하지 않고 실패한다', async () => {
    queryMock.mockReturnValue(streamOf(successResult('응답')));

    const provider = new ClaudeSdkCompletionProvider(undefined, undefined);
    await expect(provider.complete('시스템', '사용자')).rejects.toThrow(/AI 자격증명/);
    expect(queryMock).not.toHaveBeenCalled();
  });

  // #711 (코드리뷰): result(success) 에 is_error=true 면 원문을 모델 출력으로 돌려주지 않고 실패한다.
  it('result(success, is_error) 인증 실패는 한국어 안내의 AiCredentialFailureError 로 실패한다', async () => {
    queryMock.mockReturnValue(
      streamOf({
        type: 'result',
        subtype: 'success',
        is_error: true,
        result: 'Invalid API key · Fix external API key',
        usage: { input_tokens: 1, output_tokens: 1 },
      }),
    );
    const { AiCredentialFailureError } = await import('../../agent/ai-auth-failure.js');

    const provider = new ClaudeSdkCompletionProvider('sk-bad', undefined);
    const err = await provider.complete('s', 'u').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AiCredentialFailureError);
    expect((err as Error).message).toContain('설정 › AI 에이전트');
    expect((err as Error).message).not.toContain('Invalid API key');
  });

  it('result(success, is_error) 일시 오류는 원문이 아니라 한국어 안내로 실패한다(자격증명 오류 타입은 아님)', async () => {
    queryMock.mockReturnValue(
      streamOf({
        type: 'result',
        subtype: 'success',
        is_error: true,
        result: 'API Error: 529 overloaded_error',
        usage: { input_tokens: 1, output_tokens: 1 },
      }),
    );
    const { AiCredentialFailureError } = await import('../../agent/ai-auth-failure.js');

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);
    const err = await provider.complete('s', 'u').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(Error);
    expect(err).not.toBeInstanceOf(AiCredentialFailureError);
    expect((err as Error).message).toContain('일시적인 오류');
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
        errors: ['tool process crashed'],
        usage: {},
      }),
    );

    const provider = new ClaudeSdkCompletionProvider('sk', undefined);

    await expect(provider.complete('시스템', '사용자')).rejects.toThrow(
      /error_during_execution.*tool process crashed/,
    );
  });

  // 두 실패 출구가 같은 경로(toCompletionError)를 탄다 — error_* 로 보고된 인증 실패도
  // AiCredentialFailureError 가 되어 GraphRAG 의 빈 결과 삼킴에서 빠진다.
  it('result(error_*) 의 인증 실패도 한국어 안내의 AiCredentialFailureError 로 실패한다', async () => {
    queryMock.mockReturnValue(
      streamOf({
        type: 'result',
        subtype: 'error_during_execution',
        errors: ['Not logged in · Please run /login'],
        usage: {},
      }),
    );
    const { AiCredentialFailureError } = await import('../../agent/ai-auth-failure.js');

    const err = await new ClaudeSdkCompletionProvider('sk', undefined).complete('s', 'u').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AiCredentialFailureError);
    expect((err as Error).message).toContain('설정 › AI 에이전트');
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
