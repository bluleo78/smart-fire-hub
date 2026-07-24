/**
 * useJobProgress SSE 파서 단위 테스트
 *
 * parseSseLine()이 표준 SSE 스펙에 따라 이벤트 타입을 올바르게 추적하는지 검증한다.
 * 핵심 회귀 케이스: 동일 청크 내 여러 이벤트 블록을 전달할 때
 * currentEventType이 data: 처리 직후 초기화되지 않고 빈 줄에서 초기화되어야 한다.
 */
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { jobsApi } from '../../api/jobs';
import type { SseParserState } from './useJobProgress';
import { parseSseLine, useJobProgress } from './useJobProgress';

// jobsApi.getJobStatus 를 모킹하여 REST 폴백(startRestPolling) 호출 여부를 관찰한다
vi.mock('../../api/jobs', () => ({
  jobsApi: {
    getJobStatus: vi.fn(),
  },
}));

function makeState(): SseParserState {
  return { currentEventType: 'message' };
}

describe('parseSseLine — SSE 이벤트 타입 추적', () => {
  it('event: 라인이 currentEventType을 업데이트한다', () => {
    const state = makeState();
    parseSseLine('event: complete', state);
    expect(state.currentEventType).toBe('complete');
  });

  it('data: 라인은 이벤트 타입을 변경하지 않는다 (핵심 회귀 방지)', () => {
    const state = makeState();
    parseSseLine('event: complete', state);
    parseSseLine('data: {"jobId":"1","jobType":"IMPORT","stage":"COMPLETED","progress":100,"message":""}', state);
    // data: 처리 후에도 currentEventType이 여전히 'complete'여야 한다
    expect(state.currentEventType).toBe('complete');
  });

  it('빈 줄이 currentEventType을 message로 초기화한다', () => {
    const state = makeState();
    parseSseLine('event: complete', state);
    parseSseLine('data: {"jobId":"1","jobType":"IMPORT","stage":"COMPLETED","progress":100,"message":""}', state);
    parseSseLine('', state);
    expect(state.currentEventType).toBe('message');
  });

  it('동일 청크 내 progress → complete 이벤트 블록: 두 번째 이벤트 타입이 올바르게 적용됨 (회귀 시나리오)', () => {
    const state = makeState();
    // 첫 번째 이벤트 블록
    parseSseLine('event: progress', state);
    parseSseLine('data: {"jobId":"1","jobType":"IMPORT","stage":"INSERTING","progress":50,"message":""}', state);
    expect(state.currentEventType).toBe('progress'); // data: 이후에도 'progress' 유지

    parseSseLine('', state); // 블록 구분자
    expect(state.currentEventType).toBe('message'); // 빈 줄에서 초기화

    // 두 번째 이벤트 블록
    parseSseLine('event: complete', state);
    expect(state.currentEventType).toBe('complete');

    const result = parseSseLine(
      'data: {"jobId":"1","jobType":"IMPORT","stage":"COMPLETED","progress":100,"message":""}',
      state,
    );
    // data: 처리 후에도 'complete' 유지 — 이 값으로 터미널 조건이 올바르게 동작함
    expect(state.currentEventType).toBe('complete');
    expect(result).not.toBeNull();
    expect(result?.stage).toBe('COMPLETED');
  });

  it('data: 라인은 파싱된 JobProgress를 반환한다', () => {
    const state = makeState();
    const result = parseSseLine(
      'data: {"jobId":"abc","jobType":"EXPORT","stage":"RUNNING","progress":30,"message":"처리 중"}',
      state,
    );
    expect(result).toMatchObject({
      jobId: 'abc',
      jobType: 'EXPORT',
      stage: 'RUNNING',
      progress: 30,
      message: '처리 중',
    });
  });

  it('event:/data: 이외 라인은 null 반환 및 상태 변경 없음', () => {
    const state = makeState();
    const result = parseSseLine('id: 42', state);
    expect(result).toBeNull();
    expect(state.currentEventType).toBe('message');
  });

  it('잘못된 JSON data: 라인은 null 반환 (예외 미발생)', () => {
    const state = makeState();
    const result = parseSseLine('data: not-json', state);
    expect(result).toBeNull();
  });

  it('비어있는 data: 라인은 null 반환', () => {
    const state = makeState();
    const result = parseSseLine('data: ', state);
    expect(result).toBeNull();
  });
});

/**
 * SSE 스트림이 done=true 로 정상 종료됐을 때(=서버 SseEmitter 5분 타임아웃 만료로
 * 클라이언트엔 정상 종료로 보이는 상황) REST 폴백(jobsApi.getJobStatus 폴링)으로
 * 전환되는지 검증한다. (근본원인 회귀 방지)
 */
describe('useJobProgress — done=true 종료 시 REST 폴백 전환', () => {
  const encoder = new TextEncoder();

  /**
   * 주어진 SSE 텍스트 청크들을 순서대로 반환하고 마지막에 done:true를 내려주는
   * ReadableStreamDefaultReader 목(mock)을 만든다.
   */
  function createMockReader(chunks: string[]) {
    let i = 0;
    return {
      read: vi.fn(async () => {
        if (i < chunks.length) {
          const value = encoder.encode(chunks[i]);
          i += 1;
          return { done: false, value };
        }
        return { done: true, value: undefined };
      }),
      cancel: vi.fn(),
    };
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(jobsApi.getJobStatus).mockReset();
    vi.mocked(jobsApi.getJobStatus).mockResolvedValue({
      data: {
        jobId: '1',
        jobType: 'IMPORT',
        stage: 'INSERTING',
        progress: 80,
        message: '',
        createdAt: '',
        updatedAt: '',
      },
      // axios AxiosResponse 나머지 필드는 테스트에서 불필요
    } as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('TC1: terminal 이벤트 없이 done=true 로 끊기면 REST 폴백(getJobStatus)이 호출된다', async () => {
    const reader = createMockReader([
      'event: progress\ndata: {"jobId":"1","jobType":"IMPORT","stage":"INSERTING","progress":79,"message":""}\n\n',
    ]);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader },
    });
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useJobProgress('job-1'));

    // 스트림 완전 소비(진행 이벤트 1건 + done=true) 대기
    await vi.waitFor(() => expect(reader.read).toHaveBeenCalledTimes(2));

    // REST 폴백은 setInterval 기반이므로 폴링 주기만큼 시간을 흘려보낸다
    await vi.advanceTimersByTimeAsync(3000);

    expect(jobsApi.getJobStatus).toHaveBeenCalledWith('job-1');
  });

  it('TC2: COMPLETED terminal 이벤트 수신 후 done=true 이면 REST 폴백이 호출되지 않는다', async () => {
    const reader = createMockReader([
      'event: complete\ndata: {"jobId":"1","jobType":"IMPORT","stage":"COMPLETED","progress":100,"message":""}\n\n',
    ]);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader },
    });
    vi.stubGlobal('fetch', fetchMock);

    renderHook(() => useJobProgress('job-2'));

    // terminal 이벤트 처리 시 reader.cancel() 후 즉시 return 하므로 read()는 1회만 호출된다
    await vi.waitFor(() => expect(reader.cancel).toHaveBeenCalledTimes(1));

    await vi.advanceTimersByTimeAsync(5000);

    expect(jobsApi.getJobStatus).not.toHaveBeenCalled();
  });

  it('TC3: AbortError(unmount)로 종료되면 REST 폴백이 호출되지 않는다', async () => {
    // fetch가 signal abort 시에만 AbortError로 reject되는 "대기 중" 요청을 흉내낸다
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          const err = new Error('The operation was aborted');
          err.name = 'AbortError';
          reject(err);
        });
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const { unmount } = renderHook(() => useJobProgress('job-3'));

    // 언마운트 시 훅 내부적으로 AbortController.abort() 호출 → fetch가 AbortError로 reject
    unmount();

    // 마이크로태스크/폴링 주기까지 흘려보내도 폴백이 없어야 함
    await vi.advanceTimersByTimeAsync(10000);

    expect(jobsApi.getJobStatus).not.toHaveBeenCalled();
  });
});
