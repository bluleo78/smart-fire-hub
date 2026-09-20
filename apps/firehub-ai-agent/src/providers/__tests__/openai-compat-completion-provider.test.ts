import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { OpenAICompatCompletionProvider } from '../openai-compat-completion-provider.js';

/** OpenAI 호환 chat/completions 응답을 흉내내는 fetch Response 를 만든다. */
function jsonResponse(body: unknown, ok = true, status = 200) {
  return {
    ok,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response;
}

// 이 파일의 대부분 테스트는 `https://203.0.113.10/v1` 을 baseUrl 로 쓴다. 203.0.113.0/24 는
// RFC5737 이 문서화용으로 예약한 대역(TEST-NET-3)이라 실제 서비스가 존재하지 않지만, IP
// 리터럴이라 `assertSafeCompletionTarget`(보안 리뷰 Fix2, 매 호출마다 도는 실제 SSRF 가드)이
// DNS 조회 없이 즉시 판정하고, 어느 차단 대역에도 속하지 않아 통과한다 — 이 파일의 다른
// 관심사(요청 조립/응답 처리/타임아웃 등)를 검증하는 테스트가 SSRF 가드 자체 때문에 실패하지
// 않게 하려는 선택이다(가드 자체의 검증은 아래 "SSRF 가드" describe 블록 참고).


describe('OpenAICompatCompletionProvider', () => {
  it('chat/completions 를 부르고 텍스트를 돌려준다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        choices: [{ message: { content: '답' } }],
        usage: { prompt_tokens: 3, completion_tokens: 5 },
      }),
    );
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    const r = await p.complete('시스템', '사용자');

    expect(fetchMock.mock.calls[0][0]).toBe('https://203.0.113.10/v1/chat/completions');
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toMatchObject({
      Authorization: 'Bearer k',
    });
    expect(r.text).toBe('답');
    expect(r.usage).toEqual({ inputTokens: 3, outputTokens: 5 });
  });

  it('providerId 접두사를 떼고 모델 ID 만 보낸다', async () => {
    // 저장 규약은 providerId/modelId 인데 공급자는 modelId 만 안다.
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.model).toBe('gpt-4o');
  });

  it('접두사가 없는 모델 문자열은 그대로 보낸다(throw 하지 않는다)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.model).toBe('gpt-4o');
  });

  it('시스템/사용자 메시지를 messages 배열로 보낸다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('시스템 프롬프트', '사용자 텍스트');

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.messages).toEqual([
      { role: 'system', content: '시스템 프롬프트' },
      { role: 'user', content: '사용자 텍스트' },
    ]);
  });

  it('systemPromptMode=append-to-preset 이어도 replace 와 동일하게 처리한다(claude_code 프리셋 없음)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('시스템', '사용자', { systemPromptMode: 'append-to-preset' });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.messages[0]).toEqual({ role: 'system', content: '시스템' });
  });

  it('usage 가 없는 응답은 undefined 로 둔다(best-effort)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '결과' } }] }));
    const r = await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    expect(r.text).toBe('결과');
    expect(r.usage).toBeUndefined();
  });

  it('HTTP 오류 응답이면 상태코드를 담아 실패한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: 'bad key' }, false, 401));
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'bad-key',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    await expect(p.complete('s', 'u')).rejects.toThrow(/status=401/);
  });

  // 보안 리뷰 Fix2(advisor 지적) — undici/브라우저 fetch 는 기본으로 리다이렉트를 따라간다
  // (redirect: 'follow'). 공인 호스트가 302 로 169.254.169.254 같은 내부 주소로 돌려보내면
  // ssrf-guard.ts 는 최초 baseUrl 만 검사했으므로 리다이렉트 대상은 무검증으로 요청이 나간다 —
  // 자바 OpencodeProbeService 가 리다이렉트를 아예 추적하지 않는 것과 같은 이유로 여기서도
  // 명시적으로 막아야 한다.
  it('fetch 에 redirect: error 를 지정해 리다이렉트를 따라가지 않는다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    expect((fetchMock.mock.calls[0][1] as RequestInit).redirect).toBe('error');
  });

  it('fetch 가 redirect: error 로 인해 리다이렉트에서 거부되면 그 실패를 그대로 전파한다', async () => {
    // 실제 undici 는 redirect:'error' 모드에서 리다이렉트를 만나면 TypeError 로 reject 한다.
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('unexpected redirect'));
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    await expect(p.complete('s', 'u')).rejects.toThrow('unexpected redirect');
  });

  // 보안 리뷰 Fix2(advisor 지적) — 2xx 인데 JSON 이 아닌 본문(공격자가 통제하는 호스트가 임의
  // 텍스트로 응답)이면 V8 의 SyntaxError 가 본문 일부를 메시지에 그대로 인용한다("Unexpected
  // token … is not valid JSON" 류). status!=2xx 분기의 본문 유출을 막아 놓고 이 분기를 놓치면
  // 같은 유출이 형태만 바꿔 재발한다.
  it('2xx 응답이 JSON 이 아니면 원문을 호출자에게 흘리지 않고 일반화된 메시지로 실패한다', async () => {
    const upstreamMarker = 'ami-1234567890abcdef';
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => {
        throw new SyntaxError(`Unexpected token 'a', "${upstreamMarker}..." is not valid JSON`);
      },
      text: async () => upstreamMarker,
    } as unknown as Response);
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    let thrown: Error | undefined;
    try {
      await p.complete('s', 'u');
    } catch (err) {
      thrown = err as Error;
    }

    expect(thrown).toBeDefined();
    expect(thrown!.message).not.toContain(upstreamMarker);
  });

  // Fix round 2 (리뷰 지적 #3): 응답 본문(상류 원문)은 진단을 위해 남기되 200자로 자른다 —
  // baseUrl 은 테넌트가 고르므로 그 호스트가 보내는 본문은 신뢰할 수 없는(공격자 영향) 내용이고,
  // classification-service.ts 가 이 에러 메시지를 그대로 전파한다.
  // 보안 리뷰 Fix2 — 상류 응답 본문은 더는 호출자에게 돌아오는 에러 메시지에 실리지 않는다(전에는
  // 200자로 잘라서라도 실었다). baseUrl 은 테넌트가 고르는 임의의 호스트라 그 응답 본문은
  // 신뢰할 수 없고, classification-service.ts 가 이 메시지를 파이프라인 호출자에게 그대로
  // 전파하므로 조금이라도 실리면 "임의 호스트가 통제하는 내용을 대신 전달해 주는" 통로가 된다.
  it('HTTP 오류 응답 본문은 호출자에게 돌아오는 에러에 전혀 담기지 않는다', async () => {
    const hugeBody = 'x'.repeat(1000);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: hugeBody }, false, 500));
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    let thrown: Error | undefined;
    try {
      await p.complete('s', 'u');
    } catch (err) {
      thrown = err as Error;
    }

    expect(thrown).toBeDefined();
    // 상태 코드만 담은 고정 메시지와 바이트 단위로 같아야 한다 — 본문의 일부(잘린 조각)라도
    // 붙으면 이 단언이 깨진다.
    expect(thrown!.message).toBe('[completion] OpenAI 호환 요청 실패 (status=500)');
    expect(thrown!.message).not.toContain(hugeBody.slice(0, 50));
  });

  /**
   * 위 테스트는 "호출자에게 안 새는지"만 본다 — 이 테스트는 "그래도 진단 정보 자체가
   * 사라지지는 않았는지"(로그로는 남는지)를 확인한다. 둘 다 있어야 "본문을 숨겼다"가 "본문을
   * 버렸다"와 구분된다.
   */
  it('HTTP 오류 응답 본문은 호출자 대신 서버 로그(console.error)에 200자로 잘려 남는다', async () => {
    const hugeBody = 'y'.repeat(1000);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: hugeBody }, false, 500));
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const p = new OpenAICompatCompletionProvider(
        'https://203.0.113.10/v1',
        'k',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
      );
      await expect(p.complete('s', 'u')).rejects.toThrow();

      const loggedCalls = consoleErrorSpy.mock.calls.map((args) => args.join(' '));
      const diagnosticCall = loggedCalls.find((line) => line.includes('status=500'));
      expect(diagnosticCall).toBeDefined();
      // 실제 본문(JSON.stringify({error: hugeBody}))은 `{"error":"` 접두사 10자 + y 990자다.
      // 200자로 자르면 y 는 190개만 남는다 — 그보다 적게라도 실제로 남았는지 확인한다.
      expect(diagnosticCall).toContain('y'.repeat(100));
      // 본문 전체(990개 y)는 로그에도 안 남는다 — 200자로 자른다.
      expect(diagnosticCall).not.toContain('y'.repeat(300));
    } finally {
      consoleErrorSpy.mockRestore();
    }
  });

  // Fix round 2 (리뷰 지적 #2): 브리프 Step 3 이 명시한 max_tokens 필드가 요청 바디에
  // 실제로 실리는지 검증한다 — classification-service.ts 는 CLASSIFY_MAX_OUTPUT_TOKENS=16_384
  // 를 넘겨 응답이 잘리지 않게 하는데, 이 필드가 빠지면 조용히 무시되고 배치가 잘려 실패한다.
  it('maxOutputTokens 를 max_tokens 로 요청 바디에 싣는다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u', { maxOutputTokens: 16_384 });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.max_tokens).toBe(16_384);
  });

  it('maxOutputTokens 를 지정하지 않으면 max_tokens 를 보내지 않는다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body).not.toHaveProperty('max_tokens');
  });

  it('timeoutMs 를 넘기면 abort 한다', async () => {
    const fetchMock = vi.fn(
      (_url: string, opts: RequestInit) =>
        new Promise((_resolve, reject) => {
          opts.signal?.addEventListener('abort', () => {
            const err = new Error('The operation was aborted');
            err.name = 'AbortError';
            reject(err);
          });
        }),
    );
    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    await expect(p.complete('s', 'u', { timeoutMs: 5 })).rejects.toThrow(/5ms 내에 끝나지 않아/);
  });

  it('이미 중단된 abortSignal이면 fetch 를 호출하지 않고 즉시 실패한다', async () => {
    const controller = new AbortController();
    controller.abort();
    const fetchMock = vi.fn();

    const p = new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    );

    await expect(
      p.complete('s', 'u', { abortSignal: controller.signal }),
    ).rejects.toThrow(/이미 중단된/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('baseUrl 끝에 슬래시가 있어도 이중 슬래시 없이 호출한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
    await new OpenAICompatCompletionProvider(
      'https://203.0.113.10/v1/',
      'k',
      'openai/gpt-4o',
      fetchMock as unknown as typeof fetch,
    ).complete('s', 'u');

    expect(fetchMock.mock.calls[0][0]).toBe('https://203.0.113.10/v1/chat/completions');
  });

  // 설계서 "테스트로 고정할 것" 2번 — 테넌트 자격증명(opencode apiKey)이 있을 때 ambient 키가
  // 결코 쓰이지 않는다. 여기서는 apiKey 가 빈 문자열일 때도 컨테이너의 ambient
  // ANTHROPIC_API_KEY 로 조용히 메우지 않는지 provider 계층에서 직접 고정한다 — 6b1c6383 이
  // 재발하는 지점은 라우트가 아니라 바로 이 Authorization 헤더 조립부다.
  describe('ambient ANTHROPIC_API_KEY 미사용', () => {
    const ORIGINAL = process.env.ANTHROPIC_API_KEY;

    beforeEach(() => {
      process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    });

    afterEach(() => {
      if (ORIGINAL === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = ORIGINAL;
    });

    it('apiKey 가 빈 문자열이어도 ambient ANTHROPIC_API_KEY 로 메우지 않는다', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(jsonResponse({ choices: [{ message: { content: '' } }] }));
      await new OpenAICompatCompletionProvider(
        'https://203.0.113.10/v1',
        '',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
      ).complete('s', 'u');

      const headers = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<
        string,
        string
      >;
      expect(headers.Authorization).toBe('Bearer ');
      expect(headers.Authorization).not.toContain('ambient-must-not-leak');
    });
  });

  // 보안 리뷰 Fix2 — SSRF 가드가 호출부에 실제로 배선돼 있는지. 가드 자체(차단 대역 판정)의
  // 정확성은 ssrf-guard.test.ts 가 전수로 검증한다 — 여기서는 "complete() 가 그 가드를 실제로
  // 부르고, 가드가 막으면 fetch 가 아예 안 나가는지"만 본다.
  describe('SSRF 가드 배선', () => {
    it('targetGuard 가 거부하면 fetch 를 호출하지 않고 그 거부를 그대로 전파한다', async () => {
      const fetchMock = vi.fn();
      const rejectingGuard = vi.fn().mockRejectedValue(new Error('[completion] 허용되지 않은 대상 주소입니다.'));
      const p = new OpenAICompatCompletionProvider(
        'https://169.254.169.254/v1', // 값 자체는 가짜 가드가 무시한다 — 배선만 본다
        'k',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
        rejectingGuard,
      );

      await expect(p.complete('s', 'u')).rejects.toThrow('허용되지 않은 대상 주소');
      expect(rejectingGuard).toHaveBeenCalledWith('https://169.254.169.254/v1');
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('targetGuard 가 통과시키면 평소대로 fetch 를 호출한다', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(jsonResponse({ choices: [{ message: { content: 'ok' } }] }));
      const allowingGuard = vi.fn().mockResolvedValue(undefined);
      const p = new OpenAICompatCompletionProvider(
        'https://203.0.113.10/v1',
        'k',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
        allowingGuard,
      );

      const r = await p.complete('s', 'u');

      expect(allowingGuard).toHaveBeenCalledWith('https://203.0.113.10/v1');
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(r.text).toBe('ok');
    });

    // 기본값(targetGuard 를 안 넘김) 경로 — 실제 assertSafeCompletionTarget 이 쓰인다. IP
    // 리터럴(사설 대역)이라 DNS 없이 즉시 차단된다.
    it('targetGuard 를 넘기지 않으면 실제 가드가 기본으로 동작해 사설 대역 baseUrl을 거부한다', async () => {
      const fetchMock = vi.fn();
      const p = new OpenAICompatCompletionProvider(
        'https://10.0.0.5/v1',
        'k',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
      );

      await expect(p.complete('s', 'u')).rejects.toThrow(/허용되지 않은 대상 주소|https 만 허용/);
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('targetGuard 를 넘기지 않으면 실제 가드가 http 스킴을 거부한다', async () => {
      const fetchMock = vi.fn();
      const p = new OpenAICompatCompletionProvider(
        'http://203.0.113.10/v1',
        'k',
        'openai/gpt-4o',
        fetchMock as unknown as typeof fetch,
      );

      await expect(p.complete('s', 'u')).rejects.toThrow(/https 만 허용/);
      expect(fetchMock).not.toHaveBeenCalled();
    });
  });
});
