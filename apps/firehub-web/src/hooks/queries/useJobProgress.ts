import { useEffect, useRef,useState } from 'react';

import { getAccessToken } from '../../api/client';
import { jobsApi } from '../../api/jobs';
import type { JobProgress } from '../../types/job';

const TERMINAL_STAGES = new Set(['COMPLETED', 'FAILED']);

/**
 * SSE 파서 상태 — 이벤트 블록을 누적하는 가변 객체.
 * parseSseLine()이 호출될 때마다 이 상태를 업데이트한다.
 */
export interface SseParserState {
  /** 현재 이벤트 블록의 event: 타입. 빈 줄로 초기화된다. */
  currentEventType: string;
}

/**
 * SSE 라인 하나를 파싱하여 파서 상태를 업데이트하고,
 * data: 라인인 경우 파싱된 JobProgress 객체를 반환한다.
 *
 * 표준 SSE 파싱 규칙:
 *  - "event:" 라인: 현재 이벤트 타입 설정
 *  - "data:" 라인: JSON 파싱 후 반환
 *  - 빈 줄: 이벤트 블록 구분자 → 이벤트 타입 초기화('message')
 *
 * @returns data: 라인에서 파싱된 JobProgress, 또는 null(다른 라인)
 */
export function parseSseLine(
  line: string,
  state: SseParserState,
): JobProgress | null {
  if (line.startsWith('event:')) {
    // event: 타입 저장 — data: 라인 처리 후 초기화하지 않고 블록 종료(빈 줄) 시 초기화
    state.currentEventType = line.slice('event:'.length).trim();
    return null;
  } else if (line.startsWith('data:')) {
    const jsonStr = line.slice('data:'.length).trimStart();
    if (!jsonStr) return null;
    try {
      return JSON.parse(jsonStr) as JobProgress;
    } catch {
      // 잘못된 JSON은 무시
      return null;
    }
  } else if (line === '') {
    // 빈 줄 = SSE 이벤트 블록 구분자: 다음 블록을 위해 이벤트 타입 초기화
    state.currentEventType = 'message';
  }
  return null;
}
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
        // SSE 파서 상태 — 이벤트 블록 경계(빈 줄)에서 초기화됨
        const sseState: SseParserState = { currentEventType: 'message' };

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            // 스트림이 정상 종료(done=true)됐지만 terminal 이벤트(COMPLETED/FAILED/complete/error)를
            // 아직 못 받은 경우 — 백엔드 SseEmitter가 타임아웃(5분)으로 만료된 것으로 간주한다.
            // 재연결해도 서버 emitter가 다시 5분 뒤 끊길 뿐이므로, DB 상태를 직접 조회하는
            // REST 폴백으로 전환해 최종 결과(완료/실패)를 확실히 받는다.
            // 단, signal.aborted(unmount 등 의도적 취소)인 경우엔 폴백하지 않는다(이중 안전장치).
            if (!isTerminalRef.current && !controller.signal.aborted) {
              startRestPolling();
            }
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() ?? '';

          for (const line of lines) {
            const parsed = parseSseLine(line, sseState);
            if (parsed !== null) {
              applyProgress(parsed);
              if (
                TERMINAL_STAGES.has(parsed.stage) ||
                sseState.currentEventType === 'complete' ||
                sseState.currentEventType === 'error'
              ) {
                isTerminalRef.current = true;
                reader.cancel();
                return;
              }
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
