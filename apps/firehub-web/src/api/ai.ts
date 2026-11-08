import { pickBestMessage } from '../lib/api-error';
import type { AIMessage, AISession, AIStreamEvent } from '../types/ai';
import type { ErrorResponse } from '../types/auth';
import { client, getAccessToken } from './client';

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
  const token = getAccessToken();

  fetch('/api/v1/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: JSON.stringify({
      message: message || undefined,
      sessionId,
      fileIds: fileIds?.length ? fileIds : undefined,
      ...(navigationContext ? { navigationContext } : {}),
      ...(screenContext ? { screenContext } : {}),
    }),
    signal: controller.signal,
    credentials: 'include',
  }).then(async (response) => {
    // 거절 응답은 본문을 버리지 않고 서버 문구를 실어 던진다(WD-550)
    if (!response.ok) throw new AIChatHttpError(response.status, await readServerErrorMessage(response));
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
  }).catch((error) => {
    if (error.name !== 'AbortError') {
      onError(error);
    }
  });

  return controller;
}
