import type { CompletionOptions, CompletionProvider, CompletionResult } from './types.js';
import { assertSafeCompletionTarget } from './ssrf-guard.js';
import { splitOpencodeModelOrNull } from './opencode-model.js';

/**
 * 호출 상한 기본값(ms). claude-sdk-completion-provider.ts 의 DEFAULT_TIMEOUT_MS 와 동일하게
 * 맞춘다 — 분류/GraphRAG 호출자는 provider 종류(Claude SDK vs OpenAI 호환)를 모르므로 두
 * provider 의 기본 타임아웃이 다르면 같은 timeoutMs 미지정 호출이 provider 에 따라 다르게
 * 동작한다.
 */
const DEFAULT_TIMEOUT_MS = 90_000;

/** 테스트에서 실제 네트워크 호출 없이 검증할 수 있도록 fetch 를 주입 가능하게 한다. */
export type FetchLike = typeof fetch;

/**
 * OpenAI 호환(chat/completions) 단발 completion 프로바이더.
 *
 * opencode 자격증명(providerId/baseURL/apiKey)은 OpenAI 호환 스펙을 따르는 임의의 엔드포인트를
 * 가리킨다. Claude Agent SDK 는 이 스펙을 모르므로 여기서는 SDK 를 쓰지 않고 `POST
 * {baseUrl}/chat/completions` 한 번을 직접 호출한다 — opencode CLI 를 다시 스폰할 필요는 없다.
 * 분류·GraphRAG 추출은 세션·도구 없는 단발 호출이라 CLI 의 세션 기계가 필요 없기 때문이다
 * (설계서 "completion 경로" 절).
 *
 * <b>baseUrl 은 신뢰하지 않는다(보안 리뷰 Fix2).</b> 테넌트가 고르는 임의의 값이라, 매 호출마다
 * `ssrf-guard.ts` 의 스킴·포트·주소 가드를 다시 통과해야 실제 fetch 가 나간다 — 저장 시점
 * 검증(자바 `OpencodeProbeService`)이 이미 있어도 그 검증은 저장하던 순간의 DNS 응답을 본 것이라,
 * 이 프로바이더가 호출 직전에 다시 검사한다. <b>다만 TOCTOU 를 닫지는 못한다(재검토 N2)</b> —
 * 가드가 해석한 IP 로 접속하는 게 아니라 `fetch` 가 스스로 DNS 를 다시 해석하므로, rebinding 창이
 * 저장~호출 간격에서 가드~fetch 간격(수백 ms)으로 <b>좁아질</b> 뿐이다(ssrf-guard.ts 클래스
 * javadoc 참고). 상류 응답이 실패해도 본문을 호출자에게 돌려주지 않는다 — baseUrl 이
 * 가리키는 호스트가 곧 응답 내용을 통제할 수 있는 신뢰할 수 없는 상대이기 때문이다.
 */
export class OpenAICompatCompletionProvider implements CompletionProvider {
  readonly name = 'openai-compat';

  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string,
    private readonly model: string,
    private readonly fetchImpl: FetchLike = fetch,
    // 보안 리뷰 Fix2 — 저장 시점 검증(자바)에 기대지 않고 호출마다 SSRF 가드를 다시 돈다
    // (ssrf-guard.ts 클래스 javadoc "왜 필요한가" 참고). 테스트가 실제 DNS 없이 이 지점을
    // 검증할 수 있도록 주입 가능하게 둔다 — 기본값은 실제 가드다.
    private readonly targetGuard: (baseUrl: string) => Promise<void> = assertSafeCompletionTarget,
  ) {}

  async complete(
    systemPrompt: string,
    userText: string,
    options?: CompletionOptions,
  ): Promise<CompletionResult> {
    // baseUrl 자체의 안전성(스킴/주소)을 매 호출 검사한다 — 네트워크 왕복(fetch) 전에 실패시켜
    // 어떤 요청도 차단 대상으로 나가지 않게 한다.
    await this.targetGuard(this.baseUrl);

    // 저장 규약은 `providerId/modelId` 형식이지만(예: "openai/gpt-4o") OpenAI 호환 엔드포인트는
    // modelId 만 안다. 맨 앞 세그먼트(providerId)만 제거한다 — providerId 자체엔 '/' 가 없다는
    // 저장 계약을 전제한다(그 파싱 규칙은 opencode-model.ts 한 곳에 있다). '/' 가 없는 모델
    // 문자열은 접두사가 이미 없는 것이므로 그대로 보낸다(throw 하지 않는다 — 방어적으로 막을
    // 이유가 없다. agent-opencode.ts 의 래퍼는 같은 파서 위에서 반대 정책을 쓴다).
    const modelId = splitOpencodeModelOrNull(this.model)?.modelID ?? this.model;

    const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;

    if (options?.abortSignal?.aborted) {
      throw new Error('[completion] 호출 전에 이미 중단된 요청입니다.');
    }

    const abortController = new AbortController();
    const onExternalAbort = () => abortController.abort();
    options?.abortSignal?.addEventListener('abort', onExternalAbort, { once: true });
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      abortController.abort();
    }, timeoutMs);

    // baseUrl 끝에 슬래시가 붙어 오는 입력(관리자 오타)을 방어해 이중 슬래시를 막는다.
    const baseUrl = this.baseUrl.replace(/\/+$/, '');

    try {
      const response = await this.fetchImpl(`${baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.apiKey}`,
        },
        // systemPromptMode('append-to-preset')는 claude_code 프리셋을 전제하는데 OpenAI 호환
        // 경로엔 그런 프리셋이 없다 — 여기서는 이 옵션을 무시하고 항상 replace 와 동일하게
        // system 메시지 하나만 그대로 보낸다.
        body: JSON.stringify({
          model: modelId,
          messages: [
            { role: 'system', content: systemPrompt },
            { role: 'user', content: userText },
          ],
          ...(options?.maxOutputTokens !== undefined
            ? { max_tokens: options.maxOutputTokens }
            : {}),
        }),
        signal: abortController.signal,
        // 보안 리뷰 Fix2 — undici/브라우저 fetch 의 기본값(`redirect: 'follow'`)은 302 를 그대로
        // 따라간다. 공인 호스트가 302 로 169.254.169.254(클라우드 메타데이터) 같은 내부 주소로
        // 돌려보내면, ssrf-guard.ts 는 baseUrl 자체만 검사했으므로 리다이렉트 대상은 그 검사를
        // 거치지 않고 그대로 요청이 나간다 — 자바 OpencodeProbeService 가 이미 리다이렉트를
        // 아예 추적하지 않는 것(REDIRECT_BLOCKED)과 같은 이유로 여기서도 따라가지 않는다.
        redirect: 'error',
      });

      if (!response.ok) {
        // 보안 리뷰 Fix2 — 상류 응답 본문을 호출자에게 돌려주는 에러 메시지에 더는 싣지 않는다.
        // baseUrl 은 테넌트가 고르는 임의의 호스트이고(공격자가 그 호스트를 직접 통제하면 응답
        // 본문도 통제한다), classification-service.ts 는 이 에러 메시지를 파이프라인 호출자에게
        // 그대로 전파한다 — 본문을 실으면 그게 곧 "임의 호스트가 통제하는 내용을 우리 시스템이
        // 대신 전달해 주는" 읽기 프리미티브가 된다(Fix1 이 막는 SSRF 로 도달한 내부 서비스의
        // 응답을 이 경로로 되읽어 나가는 것도 이 프리미티브의 한 형태였다). 진단이 필요하면
        // 서버 로그에만 남긴다 — 호출자에게는 상태 코드만 전달한다.
        const bodyText = await response.text().catch(() => '');
        console.error(
          `[completion] OpenAI 호환 요청 실패 (status=${response.status}), 상류 응답 본문(진단용, 200자 절단): ${bodyText.slice(0, 200)}`,
        );
        throw new Error(`[completion] OpenAI 호환 요청 실패 (status=${response.status})`);
      }

      type CompletionResponseBody = {
        choices?: Array<{ message?: { content?: string } }>;
        usage?: { prompt_tokens?: number; completion_tokens?: number };
      };
      let data: CompletionResponseBody;
      try {
        data = (await response.json()) as CompletionResponseBody;
      } catch (parseErr) {
        // 보안 리뷰 Fix2 — 2xx 인데 JSON 이 아닌 본문(예: 공격자가 통제하는 호스트가 임의
        // 텍스트로 응답)이면 V8 의 SyntaxError 가 본문 일부를 메시지에 그대로 인용한다
        // ("Unexpected token … is not valid JSON" 류) — 그 예외를 그대로 던지면 위 상태코드
        // 오류와 같은 유출이 여기서도 재발한다. 원인은 로그로만 남기고 호출자에게는 일반화된
        // 메시지만 준다.
        console.error(
          `[completion] OpenAI 호환 응답을 JSON 으로 해석할 수 없습니다: ${String(parseErr).slice(0, 200)}`,
        );
        throw new Error('[completion] OpenAI 호환 응답을 해석할 수 없습니다.');
      }

      const text = data.choices?.[0]?.message?.content ?? '';
      // usage 는 best-effort — 응답에 없으면 undefined 로 둔다(types.ts CompletionResult 계약).
      const usage = data.usage
        ? {
            inputTokens: data.usage.prompt_tokens ?? 0,
            outputTokens: data.usage.completion_tokens ?? 0,
          }
        : undefined;

      return { text, usage };
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        throw new Error(
          timedOut
            ? `[completion] 호출이 ${timeoutMs}ms 내에 끝나지 않아 중단했습니다.`
            : '[completion] 호출이 중단되었습니다.',
        );
      }
      throw err;
    } finally {
      clearTimeout(timer);
      options?.abortSignal?.removeEventListener('abort', onExternalAbort);
    }
  }
}
