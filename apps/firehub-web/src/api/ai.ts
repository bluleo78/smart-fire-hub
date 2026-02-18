import { client, getAccessToken } from './client';
import type { AISession, AIMessage, AIStreamEvent } from '../types/ai';

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
};

export function streamAIChat(
  message: string,
  sessionId: string | null,
  onEvent: (event: AIStreamEvent) => void,
  onError: (error: Error) => void,
  onComplete: () => void
): AbortController {
  const controller = new AbortController();
  const token = getAccessToken();

  fetch('/api/v1/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: JSON.stringify({ message, sessionId }),
    signal: controller.signal,
    credentials: 'include',
  }).then(async (response) => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
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
