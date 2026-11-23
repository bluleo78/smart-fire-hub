import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { type SettleStatus, useLoadMoreLimit } from './useLoadMoreLimit';

type Status = SettleStatus;
const OK: Status = { isError: false, isSuccess: true };
const FAIL: Status = { isError: true, isSuccess: false };
const PENDING: Status = { isError: false, isSuccess: false };

/**
 * 화면처럼 매 렌더에 「지금 limit 의 조회 상태」를 settle 로 넘기는 훅.
 * statuses 는 limit 별 조회 결과이며 없으면 조회 중으로 본다(limit 이 바뀌면 상태도 함께 바뀌는 실제 흐름과 같다).
 */
function setup(statuses: Record<number, Status>) {
  return renderHook(() => {
    const api = useLoadMoreLimit();
    api.settle(statuses[api.limit] ?? PENDING);
    return api;
  });
}

describe('useLoadMoreLimit', () => {
  it('초기 limit 은 20 이고 실패 상태가 아니다', () => {
    const { result } = setup({ 20: OK });
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(false);
  });

  it('새 limit 이 낡은 캐시(isSuccess)로 재조회 중이면 성공으로 세지 않고 재조회 실패는 failed 로 알린다 (WD-1019)', () => {
    const statuses: Record<number, Status> = { 20: OK, 40: { isError: false, isSuccess: true, refetchPending: true } };
    const { result, rerender } = setup(statuses);
    act(() => result.current.loadMore());
    // 재조회 중: 낡은 캐시를 성공으로 세지 않아 limit 이 유지된다
    expect(result.current.limit).toBe(40);
    expect(result.current.failed).toBe(false);
    // 재조회 실패: 되돌리고 failed
    statuses[40] = FAIL;
    rerender();
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(true);
    // 「다시 시도」: 오류 캐시를 다시 열어 isError·refetchPending 이 함께 오면 되돌리지 않고 재조회를 기다린다 (WD-1019 리뷰)
    statuses[40] = { isError: true, isSuccess: false, refetchPending: true };
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(40);
    expect(result.current.pending).toBe(true);
  });

  it('「더 보기」 조회가 실패하면 마지막 성공 limit 으로 되돌리고 failed 로 둔다', () => {
    const { result } = setup({ 20: OK, 40: FAIL });
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(true);
  });

  it('failed 상태에서 되돌린 limit 의 성공 보고가 와도 failed 를 유지한다', () => {
    const { result, rerender } = setup({ 20: OK, 40: FAIL });
    act(() => result.current.loadMore());
    rerender();
    expect(result.current.failed).toBe(true);
    expect(result.current.limit).toBe(20);
  });

  it('실패 뒤 loadMore 는 failed 를 유지한 채 limit 만 늘리고 재시도 조회 중에는 pending 이다 (WD-956 리뷰 후속)', () => {
    // 40 은 처음엔 실패, retry 뒤에는 조회 중(PENDING)인 상황
    const statuses: Record<number, Status> = { 20: OK, 40: FAIL };
    const { result } = setup(statuses);
    act(() => result.current.loadMore());
    expect(result.current.failed).toBe(true);
    expect(result.current.pending).toBe(false);
    statuses[40] = PENDING;
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(40);
    expect(result.current.failed).toBe(true);
    expect(result.current.pending).toBe(true);
  });

  it('다시 시도 조회가 성공하면 failed 가 지워지고 pending 이 꺼진다', () => {
    const statuses: Record<number, Status> = { 20: OK, 40: FAIL };
    const { result } = setup(statuses);
    act(() => result.current.loadMore());
    expect(result.current.failed).toBe(true);
    statuses[40] = OK;
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(40);
    expect(result.current.failed).toBe(false);
    expect(result.current.pending).toBe(false);
  });

  it('다시 시도 조회가 다시 실패하면 마지막 성공 limit 으로 되돌리고 failed 를 유지한다', () => {
    const { result } = setup({ 20: OK, 40: FAIL });
    act(() => result.current.loadMore());
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(true);
    expect(result.current.pending).toBe(false);
  });

  it('성공한 limit 을 기록해 그 뒤 실패는 그 값으로 되돌린다', () => {
    const { result } = setup({ 20: OK, 40: OK, 60: FAIL });
    act(() => result.current.loadMore());
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(40);
    expect(result.current.failed).toBe(true);
  });

  it('첫 조회 실패는 되돌리지 않는다', () => {
    const { result } = setup({ 20: FAIL });
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(false);
  });
  it('자리표시 데이터의 성공은 새 limit 의 성공으로 세지 않아 그 뒤 실패는 이전 성공 limit 으로 되돌린다', () => {
    const PLACEHOLDER: Status = { isError: false, isSuccess: true, isPlaceholderData: true };
    const { result } = setup({ 20: OK, 40: PLACEHOLDER, 60: FAIL });
    act(() => result.current.loadMore());
    act(() => result.current.loadMore());
    expect(result.current.limit).toBe(20);
    expect(result.current.failed).toBe(true);
  });
});
