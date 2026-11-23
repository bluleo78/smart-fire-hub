import { onlineManager } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { offlineListMessage, useOfflinePaused } from './useOnlineStatus';

// 목록 오프라인 멈춤 공용 판정 훅 시험 — 데이터 없음(WD-971)과 쪽 이동 placeholder(WD-982)를 한 규칙으로 다루는지 확인한다
describe('useOfflinePaused', () => {
  afterEach(() => {
    onlineManager.setOnline(true);
  });

  it('데이터 없음·paused·오프라인이면 offline (조건 변경·첫 조회, WD-971)', () => {
    onlineManager.setOnline(false);
    const { result } = renderHook(() => useOfflinePaused({ data: undefined, isPaused: true }));
    expect(result.current).toEqual({ offline: true, waiting: false });
  });

  it('데이터 없음·paused·온라인(탭 숨김)이면 waiting — 스켈레톤', () => {
    onlineManager.setOnline(true);
    const { result } = renderHook(() => useOfflinePaused({ data: undefined, isPaused: true }));
    expect(result.current).toEqual({ offline: false, waiting: true });
  });

  it('placeholder·paused·오프라인이면 offline (쪽 이동, WD-982)', () => {
    onlineManager.setOnline(false);
    const { result } = renderHook(() => useOfflinePaused({ data: {}, isPaused: true, isPlaceholderData: true }));
    expect(result.current).toEqual({ offline: true, waiting: false });
  });

  it('placeholder·paused·온라인이면 둘 다 false — 흐린 이전 행을 그대로 둔다', () => {
    onlineManager.setOnline(true);
    const { result } = renderHook(() => useOfflinePaused({ data: {}, isPaused: true, isPlaceholderData: true }));
    expect(result.current).toEqual({ offline: false, waiting: false });
  });

  it('지금 조건의 데이터가 있으면 오프라인·paused 여도 둘 다 false — 목록 유지', () => {
    onlineManager.setOnline(false);
    const { result } = renderHook(() => useOfflinePaused({ data: {}, isPaused: true }));
    expect(result.current).toEqual({ offline: false, waiting: false });
  });

  it('paused 가 아니면 둘 다 false', () => {
    onlineManager.setOnline(false);
    const { result } = renderHook(() => useOfflinePaused({ data: undefined, isPaused: false, isPlaceholderData: true }));
    expect(result.current).toEqual({ offline: false, waiting: false });
  });

  it('오프라인에서 온라인으로 바뀌면 offline 이 풀린다', () => {
    onlineManager.setOnline(false);
    const { result } = renderHook(() => useOfflinePaused({ data: undefined, isPaused: true }));
    expect(result.current.offline).toBe(true);
    act(() => {
      onlineManager.setOnline(true);
    });
    expect(result.current).toEqual({ offline: false, waiting: true });
  });
});

describe('offlineListMessage', () => {
  it('목록 이름을 넣은 연결 대기 안내', () => {
    expect(offlineListMessage('데이터셋')).toBe(
      '인터넷 연결이 없어 데이터셋 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.',
    );
  });
});
