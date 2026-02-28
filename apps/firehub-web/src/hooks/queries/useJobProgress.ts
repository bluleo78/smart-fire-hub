import { useEffect, useRef,useState } from 'react';

import { getAccessToken } from '../../api/client';
import { jobsApi } from '../../api/jobs';
import type { JobProgress } from '../../types/job';

const TERMINAL_STAGES = new Set(['COMPLETED', 'FAILED']);
const MAX_RETRIES = 5;
const BASE_DELAY_MS = 3000;
const REST_POLL_INTERVAL_MS = 3000;

export function useJobProgress(jobId: string | null): JobProgress | null {
  // Store [forJobId, progress] together so progress resets when jobId changes
  const [state, setState] = useState<{ forJobId: string; progress: JobProgress } | null>(null);

  const jobIdRef = useRef(jobId);
  const retryCountRef = useRef(0);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const isTerminalRef = useRef(false);
  // Forward ref so retry closures always call the latest connect
  const connectRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    jobIdRef.current = jobId;
  });

  useEffect(() => {
    if (!jobId) {
      isTerminalRef.current = false;
      retryCountRef.current = 0;
      return;
    }

    isTerminalRef.current = false;
    retryCountRef.current = 0;

    function clearRetryTimer() {
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current);
        retryTimerRef.current = null;
      }
    }

    function clearPollInterval() {
      if (pollIntervalRef.current !== null) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    }

    function applyProgress(jp: JobProgress) {
      setState({ forJobId: jp.jobId, progress: jp });
    }

    function startRestPolling() {
      const currentJobId = jobIdRef.current;
      if (!currentJobId || isTerminalRef.current) return;
      clearPollInterval();
      pollIntervalRef.current = setInterval(async () => {
        if (isTerminalRef.current) {
          clearPollInterval();
          return;
        }
        try {
          const res = await jobsApi.getJobStatus(currentJobId);
          const status = res.data;
          applyProgress({
            jobId: status.jobId,
            jobType: status.jobType,
            stage: status.stage,
            progress: status.progress,
            message: status.message,
            metadata: status.metadata,
            errorMessage: status.errorMessage,
          });
          if (TERMINAL_STAGES.has(status.stage)) {
            isTerminalRef.current = true;
            clearPollInterval();
          }
        } catch {
          // keep polling on error
        }
      }, REST_POLL_INTERVAL_MS);
    }

    function connect() {
      const currentJobId = jobIdRef.current;
      if (!currentJobId || isTerminalRef.current) return;

      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }

      const controller = new AbortController();
      abortControllerRef.current = controller;
      const token = getAccessToken();

      fetch(`/api/v1/jobs/${currentJobId}/progress`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Accept': 'text/event-stream',
        },
        signal: controller.signal,
        credentials: 'include',
      }).then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        retryCountRef.current = 0;

        if (!response.body) {
          throw new Error('Response body is null');
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEventType = 'message';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() ?? '';

          for (const line of lines) {
            if (line.startsWith('event:')) {
              currentEventType = line.slice('event:'.length).trim();
            } else if (line.startsWith('data:')) {
              const jsonStr = line.slice('data:'.length).trimStart();
              if (!jsonStr) continue;
              try {
                const parsed = JSON.parse(jsonStr) as JobProgress;
                applyProgress(parsed);
                if (
                  TERMINAL_STAGES.has(parsed.stage) ||
                  currentEventType === 'complete' ||
                  currentEventType === 'error'
                ) {
                  isTerminalRef.current = true;
                  reader.cancel();
                  return;
                }
              } catch {
                // skip malformed events
              }
              currentEventType = 'message';
            }
          }
        }
      }).catch((error: unknown) => {
        if (error instanceof Error && error.name === 'AbortError') return;
        if (isTerminalRef.current) return;

        const retryCount = retryCountRef.current;
        if (retryCount < MAX_RETRIES) {
          retryCountRef.current += 1;
          const delay = BASE_DELAY_MS * Math.pow(2, retryCount);
          retryTimerRef.current = setTimeout(() => {
            connectRef.current?.();
          }, delay);
        } else {
          startRestPolling();
        }
      });
    }

    connectRef.current = connect;
    connect();

    return () => {
      isTerminalRef.current = true;
      clearRetryTimer();
      clearPollInterval();
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
        abortControllerRef.current = null;
      }
      connectRef.current = null;
    };
  }, [jobId]);

  // Return progress only if it belongs to the current jobId
  if (!jobId || state?.forJobId !== jobId) return null;
  return state.progress;
}
