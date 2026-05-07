/**
 * useJobProgress SSE 파서 단위 테스트
 *
 * parseSseLine()이 표준 SSE 스펙에 따라 이벤트 타입을 올바르게 추적하는지 검증한다.
 * 핵심 회귀 케이스: 동일 청크 내 여러 이벤트 블록을 전달할 때
 * currentEventType이 data: 처리 직후 초기화되지 않고 빈 줄에서 초기화되어야 한다.
 */
import { describe, expect, it } from 'vitest';

import type { SseParserState } from './useJobProgress';
import { parseSseLine } from './useJobProgress';

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
