import {
  MAX_NAVIGATION_CONTEXT_LENGTH,
  MAX_SCREEN_CONTEXT_LENGTH,
  truncateChatContext,
} from '../lib/ai-chat-limits';
import { pickBestMessage } from '../lib/api-error';
import type { AIMessage, AISession, AIStreamEvent } from '../types/ai';
import type { ErrorResponse } from '../types/auth';
import { authedFetch, client, TokenRefreshError } from './client';

/**
 * AI 채팅 요청이 스트림을 열기 전에 HTTP 오류(4xx/5xx)로 거절된 경우 (WD-550).
 *
 * 네트워크 실패(fetch 자체 실패)와 구분해야 채팅 UI 가 "네트워크 연결에 실패했습니다" 대신 서버가 준 문구
 * (예: "메시지는 50,000자 이하로 입력하세요.")를 보여 줄 수 있다.
 * serverMessage 는 응답 본문(ErrorResponse)에서 고른 사람이 읽는 문구이며, 본문이 없거나 JSON 이 아니면 null.
 */
export class AIChatHttpError extends Error {
  readonly status: number;
  readonly serverMessage: string | null;

  constructor(status: number, serverMessage: string | null) {
    super(serverMessage ?? `HTTP ${status}`);
    this.name = 'AIChatHttpError';
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

/**
 * 거절 응답 본문에서 사용자에게 보여 줄 문구를 고른다 — 필드 오류(errors) → message 순(axios 경로와 같은 규칙).
 * 본문이 JSON 객체가 아니면(프록시 HTML 502 등) null 을 돌려 호출부가 상태 코드 폴백을 쓰게 한다.
 */
async function readServerErrorMessage(response: Response): Promise<string | null> {
  try {
    const parsed: unknown = JSON.parse(await response.text());
    if (typeof parsed !== 'object' || parsed === null) return null;
    return pickBestMessage(parsed as ErrorResponse, '') || null;
  } catch {
    return null;
  }
}

/** 토큰 갱신이 실패했거나 갱신한 토큰으로도 401 일 때 채팅에 보일 문구 (WD-631) */
export const SESSION_EXPIRED_MESSAGE = '로그인이 만료되었습니다. 다시 로그인해 주세요.';

export const aiApi = {
  getSessions: (params?: { page?: number; size?: number }) =>
    client.get<AISession[]>('/ai/sessions', { params }),
  createSession: (data: { sessionId: string; title?: string }) =>
    client.post<AISession>('/ai/sessions', data),
  updateSession: (id: number, data: { title: string }) =>
    client.put<AISession>(`/ai/sessions/${id}`, data),
  deleteSession: (id: number) =>
    client.delete(`/ai/sessions/${id}`),
  getSessionMessages: (sessionId: string) =>
    client.get<AIMessage[]>(`/ai/sessions/${sessionId}/messages`),
  /** 진행 중인 응답을 서버에서도 멈춘다(WD-57) — fetch abort 만으로는 서버 작업이 계속된다. */
  cancelChat: (sessionId: string) =>
    client.post<void>(`/ai/chat/${encodeURIComponent(sessionId)}/cancel`),
};

/**
 * 채팅 요청을 보내고 스트림을 열 응답을 돌려준다 — 401 갱신·1회 재시도는 authedFetch 가 한다(WD-631).
 * 스트림 읽기와 다른 함수로 둔 이유: 직렬화한 본문(최대 수백 KB)이 스트림을 읽는 내내 클로저에 붙잡히지 않게 하려는 것 —
 * 본문은 이 함수와 authedFetch 안에서만 쓰이고, 응답이 나오면 놓인다.
 */
async function openChatStream(
  message: string,
  sessionId: string | null,
  fileIds: number[] | null,
  navigationContext: string | undefined,
  screenContext: string | undefined,
  signal: AbortSignal,
): Promise<Response> {
  // 화면/이동 컨텍스트는 부가 정보라 서버 상한을 넘으면 잘라 보낸다 — 넘는다고 사용자 메시지 전송이 막히면 안 된다(WD-631).
  const navContext = truncateChatContext(navigationContext, MAX_NAVIGATION_CONTEXT_LENGTH);
  const screen = truncateChatContext(screenContext, MAX_SCREEN_CONTEXT_LENGTH);
  const body = JSON.stringify({
    message: message || undefined,
    sessionId,
    fileIds: fileIds?.length ? fileIds : undefined,
    ...(navContext ? { navigationContext: navContext } : {}),
    ...(screen ? { screenContext: screen } : {}),
  });

  let opened: { response: Response; demoted: boolean };
  try {
    opened = await authedFetch('/api/v1/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      signal,
      credentials: 'include',
    });
  } catch (error) {
    // 갱신 실패(세션 종료 중)·대기 초과는 네트워크 실패가 아니라 로그인 만료로 안내한다. 네트워크 실패·중단은 그대로 올린다.
    if (error instanceof TokenRefreshError) throw new AIChatHttpError(401, SESSION_EXPIRED_MESSAGE);
    throw error;
  }
  const { response, demoted } = opened;
  if (response.status === 401) {
    // 강등이면 첫 401 의 서버 문구(본문은 던질 때만 읽는다), 재시도 후에도 401 이면 로그인 만료 안내
    throw new AIChatHttpError(401, demoted ? await readServerErrorMessage(response) : SESSION_EXPIRED_MESSAGE);
  }
  // 거절 응답은 본문을 버리지 않고 서버 문구를 실어 던진다(WD-550).
  if (!response.ok) throw new AIChatHttpError(response.status, await readServerErrorMessage(response));
  return response;
}

/**
 * AI 채팅을 SSE 로 보낸다. 스트림이 열리기 전 401 은 axios 인터셉터와 같은 갱신 경로로 한 번 갱신해 한 번만 다시 보낸다
 * (WD-631 — 예전에는 액세스 토큰이 만료되면 채팅이 HTTP 401 오류로 끝났다). 스트림이 열린 뒤(200)의 오류는 재시도하지 않는다 —
 * 이미 일부 이벤트를 받았을 수 있어 다시 보내면 같은 질문이 두 번 실행된다. 재시도에도 같은 중단 신호를 건다.
 */
export function streamAIChat(
  message: string,
  sessionId: string | null,
  fileIds: number[] | null,
  onEvent: (event: AIStreamEvent) => void,
  onError: (error: Error) => void,
  onComplete: () => void,
  navigationContext?: string,
  screenContext?: string,
): AbortController {
  const controller = new AbortController();
  // 여기서 바로 시작해 아래 읽기 루프가 요청 인자·본문을 클로저로 붙잡지 않게 한다
  const opening = openChatStream(message, sessionId, fileIds, navigationContext, screenContext, controller.signal);

  (async () => {
    const response = await opening;
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) { onComplete(); break; }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const jsonStr = line.slice(line.indexOf(':') + 1).trimStart();
            const event = JSON.parse(jsonStr) as AIStreamEvent;
            onEvent(event);
          } catch {
            // skip malformed events
          }
        }
      }
    }
  })().catch((error) => {
    if (error.name !== 'AbortError') {
      onError(error);
    }
  });

  return controller;
}
