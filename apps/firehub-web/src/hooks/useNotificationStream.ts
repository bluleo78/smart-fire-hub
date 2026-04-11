import { type QueryClient,useQueryClient } from '@tanstack/react-query';
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

export function useNotificationStream() {
  const queryClient = useQueryClient();

  useEffect(() => {
    let es: EventSource | null = null;
    let cancelled = false;
    let attempt = 0;

    const connect = () => {
      if (cancelled) return;

      const token = getAccessToken();
      if (!token) return;

      es = new EventSource(`/api/v1/notifications/stream?token=${token}`);

      es.addEventListener('notification', (event) => {
        attempt = 0;
        try {
          const notification: NotificationEvent = JSON.parse(
            (event as MessageEvent).data
          );
          handleNotification(notification, queryClient);
        } catch {
          // Ignore malformed SSE data
        }
      });

      es.onerror = () => {
        es?.close();
        if (!cancelled) {
          const delay =
            Math.min(1000 * Math.pow(2, attempt), 60_000) +
            Math.random() * 1000;
          attempt++;
          setTimeout(connect, delay);
        }
      };
    };

    connect();
    return () => {
      cancelled = true;
      es?.close();
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
      toast.info(event.title, { description: event.description });
      break;
  }

  if (event.severity === 'CRITICAL') {
    toast.error(event.title, { description: event.description });
  } else if (event.severity === 'WARNING') {
    toast.warning(event.title, { description: event.description });
  }
}
