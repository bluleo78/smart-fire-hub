/**
 * streamAIChat 의 스트림 열기 전 오류 매핑 단위 테스트 (WD-631)
 *
 * 401 갱신·1회 재시도 자체는 authedFetch(client.refresh.test.ts)가 검증한다. 여기서는 authedFetch 결과를 목으로 바꿔
 * 채팅이 각 경우를 어떤 오류로 끝내는지 본다 — 갱신 실패(세션 종료 중, 화면이 /login 으로 리로드돼 E2E 로 붙잡기 어렵다)·
 * 테넌트 강등·재시도 후 401·네트워크 실패.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AIChatHttpError, SESSION_EXPIRED_MESSAGE, streamAIChat } from './ai';
import { authedFetch, TokenRefreshError } from './client';

vi.mock('./client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./client')>()),
  authedFetch: vi.fn(),
}));

/** 본문 읽기(text)를 추적할 수 있는 401 응답 */
function unauthorized(message = '인증이 필요합니다.') {
  const text = vi.fn(async () => JSON.stringify({ status: 401, message }));
  return { response: { ok: false, status: 401, text } as unknown as Response, text };
}

/** streamAIChat 을 부르고 onError/onComplete 중 하나가 불릴 때까지 기다린다 */
function run(): Promise<{ error: Error | null; events: unknown[] }> {
  return new Promise((resolve) => {
    const events: unknown[] = [];
    streamAIChat(
      '질문',
      null,
      null,
      (e) => events.push(e),
      (error) => resolve({ error, events }),
      () => resolve({ error: null, events }),
      undefined,
      'x'.repeat(200_000),
    );
  });
}

describe('streamAIChat 스트림 열기 전 오류 (WD-631)', () => {
  afterEach(() => {
    vi.mocked(authedFetch).mockReset();
  });

  it('정상 응답이면 스트림을 읽고, 화면 컨텍스트는 상한(100,000자)으로 잘라 보낸다', async () => {
    vi.mocked(authedFetch).mockResolvedValue({
      response: new Response('data: {"type":"done"}\n\n', { status: 200 }),
      demoted: false,
    });

    const { error, events } = await run();

    expect(error).toBeNull();
    expect(events).toEqual([{ type: 'done' }]);
    const init = vi.mocked(authedFetch).mock.calls[0][1]!;
    expect(JSON.parse(init.body as string).screenContext).toHaveLength(100_000);
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('갱신 실패(TokenRefreshError)면 네트워크 오류가 아니라 로그인 만료 401 로 끝난다', async () => {
    vi.mocked(authedFetch).mockRejectedValue(new TokenRefreshError(new Error('Too many queued requests')));

    const { error } = await run();

    expect(error).toBeInstanceOf(AIChatHttpError);
    expect((error as AIChatHttpError).status).toBe(401);
    expect((error as AIChatHttpError).serverMessage).toBe(SESSION_EXPIRED_MESSAGE);
  });

  it('네트워크 실패는 로그인 만료로 바꾸지 않고 그대로 올린다', async () => {
    const networkError = new TypeError('Failed to fetch');
    vi.mocked(authedFetch).mockRejectedValue(networkError);

    const { error } = await run();

    expect(error).toBe(networkError);
  });

  it('테넌트 강등이면 그때 첫 401 본문을 읽어 그 서버 문구로 끝낸다', async () => {
    const first = unauthorized('워크스페이스를 다시 선택하세요.');
    vi.mocked(authedFetch).mockResolvedValue({ response: first.response, demoted: true });

    const { error } = await run();

    expect((error as AIChatHttpError).status).toBe(401);
    expect((error as AIChatHttpError).serverMessage).toBe('워크스페이스를 다시 선택하세요.');
    expect(first.text).toHaveBeenCalledTimes(1);
  });

  it('갱신한 토큰으로도 401 이면 본문을 읽지 않고 로그인 만료로 끝낸다', async () => {
    const second = unauthorized();
    vi.mocked(authedFetch).mockResolvedValue({ response: second.response, demoted: false });

    const { error } = await run();

    expect((error as AIChatHttpError).serverMessage).toBe(SESSION_EXPIRED_MESSAGE);
    expect(second.text).not.toHaveBeenCalled();
  });
});
