import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { toast } from 'sonner';

import { getAccessToken } from '../api/client';

interface NotificationEvent {
  id: string;
  eventType: string;
  severity: 'CRITICAL' | 'WARNING' | 'INFO';
  title: string;
  description: string;
  entityType: string;
  entityId: number;
  metadata: Record<string, unknown>;
  occurredAt: string;
}

/**
 * 알림 SSE 스트림 구독 훅.
 *
 * 보안 주의: 표준 EventSource는 커스텀 헤더를 지원하지 않아 과거에는 JWT를
 * 쿼리 파라미터(`?token=...`)로 전달했으나, 토큰이 액세스 로그/프록시 로그/Referer/
 * 브라우저 히스토리에 노출되는 위험이 있었다. 본 훅은 fetch + ReadableStream으로
 * SSE를 수신하면서 Authorization 헤더로 토큰을 전송한다.
 */
export function useNotificationStream() {
  const queryClient = useQueryClient();

  useEffect(() => {
    let cancelled = false;
    let attempt = 0;
    let controller: AbortController | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const scheduleReconnect = () => {
      if (cancelled) return;
      const delay =
        Math.min(1000 * Math.pow(2, attempt), 60_000) + Math.random() * 1000;
      attempt++;
      reconnectTimer = setTimeout(connect, delay);
    };

    const connect = async () => {
      if (cancelled) return;
      const token = getAccessToken();
      if (!token) return;

      controller = new AbortController();
      try {
        const response = await fetch('/api/v1/notifications/stream', {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'text/event-stream',
          },
          signal: controller.signal,
          credentials: 'include',
        });

        if (!response.ok || !response.body) {
          throw new Error(`HTTP ${response.status}`);
        }

        // 정상 연결되면 재시도 카운터 리셋
        attempt = 0;

        // SSE 라인 파싱: 빈 줄(\n\n)로 이벤트 경계 구분, 각 라인은 `field:value`.
        // 본 엔드포인트가 사용하는 필드는 `event:` (이벤트 이름)와 `data:` (페이로드)뿐이다.
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEvent = 'message';
        let currentData = '';

        const dispatch = () => {
          if (currentEvent === 'notification' && currentData) {
            try {
              const notification: NotificationEvent = JSON.parse(currentData);
              handleNotification(notification, queryClient);
            } catch {
              // 잘못된 SSE 데이터 무시
            }
          }
          currentEvent = 'message';
          currentData = '';
        };

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          let newlineIdx: number;
          while ((newlineIdx = buffer.indexOf('\n')) !== -1) {
            const line = buffer.slice(0, newlineIdx).replace(/\r$/, '');
            buffer = buffer.slice(newlineIdx + 1);

            if (line === '') {
              dispatch();
              continue;
            }
            if (line.startsWith(':')) continue; // 코멘트/keep-alive
            const colonIdx = line.indexOf(':');
            const field = colonIdx === -1 ? line : line.slice(0, colonIdx);
            const valueRaw = colonIdx === -1 ? '' : line.slice(colonIdx + 1);
            const fieldValue = valueRaw.startsWith(' ')
              ? valueRaw.slice(1)
              : valueRaw;

            if (field === 'event') {
              currentEvent = fieldValue;
            } else if (field === 'data') {
              currentData = currentData
                ? `${currentData}\n${fieldValue}`
                : fieldValue;
            }
            // id/retry 필드는 사용하지 않음
          }
        }

        // 스트림이 정상 종료된 경우(서버 측 close 등)에도 재연결 시도
        if (!cancelled) scheduleReconnect();
      } catch (error) {
        if ((error as Error).name === 'AbortError' || cancelled) return;
        scheduleReconnect();
      }
    };

    connect();

    return () => {
      cancelled = true;
      controller?.abort();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, [queryClient]);
}

function handleNotification(
  event: NotificationEvent,
  queryClient: QueryClient
) {
  switch (event.eventType) {
    case 'PIPELINE_COMPLETED':
    case 'PIPELINE_FAILED':
    case 'IMPORT_COMPLETED':
    case 'IMPORT_FAILED':
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'stats'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'health'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'attention'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'activity'] });
      break;
    case 'DATASET_CHANGED':
      queryClient.invalidateQueries({ queryKey: ['analytics', 'charts'] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      break;
    case 'PROACTIVE_MESSAGE':
      queryClient.invalidateQueries({ queryKey: ['proactive', 'messages'] });
      queryClient.invalidateQueries({ queryKey: ['proactive', 'unread-count'] });
      // PROACTIVE_MESSAGE는 severity와 무관하게 info 토스트 하나만 표시.
      // severity 블록이 추가로 실행되지 않도록 여기서 함수를 종료한다.
      toast.info(event.title, { description: event.description });
      return;
  }

  // PROACTIVE_MESSAGE 외 이벤트에 대해 severity 기반 토스트 표시
  if (event.severity === 'CRITICAL') {
    toast.error(event.title, { description: event.description });
  } else if (event.severity === 'WARNING') {
    toast.warning(event.title, { description: event.description });
  }
}
