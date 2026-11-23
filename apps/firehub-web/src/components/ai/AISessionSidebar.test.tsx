/**
 * AISessionSidebar 단위 테스트 — 대화 삭제 뒤 오류 로그의 문구 (WD-1013)
 *
 * 수정 전 결함: then(성공, 거절).catch(...) 끝의 catch 가 거절 처리기 안의 오류까지
 * 「삭제 성공 뒤」(Failed after AI session delete) 로 남겼다.
 * 기대 동작:
 * - DELETE 가 실패하면 'Failed to delete AI session:' 만 남고 onSessionDeleted 는 불리지 않는다.
 * - 삭제가 성공한 뒤 onSessionDeleted 가 던지면 'Failed after AI session delete:' 가 한 번만 남는다.
 * - 거절 처리기가 던져도 「Failed after」 로 둔갑하지 않는다.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mutateAsync = vi.fn();
const onSessionDeleted = vi.fn();

// 쿼리·공급자 의존성은 모킹한다 — 관심사는 삭제 확인 뒤 promise 처리뿐이다.
vi.mock('../../hooks/queries/useAIChat', () => ({
  useAISessions: () => ({ data: [{ id: 7, sessionId: 'sess-7', title: '시험 대화' }] }),
  useDeleteAISession: () => ({ mutateAsync }),
  usePendingSessionDeletes: () => new Set<number>(),
}));
vi.mock('./AIProvider', () => ({
  useAI: () => ({
    currentSessionId: null,
    startNewSession: vi.fn(),
    onSessionDeleted,
    loadSession: vi.fn(),
  }),
}));

import { AISessionSidebar } from './AISessionSidebar';

/** 휴지통 → 확인 창의 「삭제」 까지 눌러 삭제를 실행한다 */
function confirmDelete() {
  fireEvent.click(screen.getByRole('button', { name: '시험 대화 삭제' }));
  fireEvent.click(screen.getByRole('button', { name: '삭제' }));
}

/** console.error 호출 중 첫 인자가 prefix 인 것의 수 */
function countLogs(spy: ReturnType<typeof vi.spyOn>, prefix: string) {
  return spy.mock.calls.filter((c: unknown[]) => c[0] === prefix).length;
}

let errorSpy: ReturnType<typeof vi.spyOn>;
const unhandled: unknown[] = [];
const onUnhandled = (reason: unknown) => unhandled.push(reason);

beforeEach(() => {
  mutateAsync.mockReset();
  onSessionDeleted.mockReset();
  unhandled.length = 0;
  errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  process.on('unhandledRejection', onUnhandled);
});

afterEach(() => {
  process.off('unhandledRejection', onUnhandled);
  errorSpy.mockRestore();
});

describe('AISessionSidebar — 삭제 뒤 오류 로그 (WD-1013)', () => {
  it('DELETE 가 실패하면 Failed to delete 만 남고 Failed after 는 없다', async () => {
    mutateAsync.mockRejectedValue(new Error('boom'));
    render(<AISessionSidebar />);
    confirmDelete();

    await waitFor(() => expect(countLogs(errorSpy, 'Failed to delete AI session:')).toBe(1));
    expect(countLogs(errorSpy, 'Failed after AI session delete:')).toBe(0);
    expect(onSessionDeleted).not.toHaveBeenCalled();
  });

  it('onSessionDeleted 가 던지면 Failed after 가 한 번만 남고 처리되지 않은 거절은 없다', async () => {
    mutateAsync.mockResolvedValue(undefined);
    onSessionDeleted.mockImplementation(() => {
      throw new Error('after boom');
    });
    render(<AISessionSidebar />);
    confirmDelete();

    await waitFor(() => expect(countLogs(errorSpy, 'Failed after AI session delete:')).toBe(1));
    expect(countLogs(errorSpy, 'Failed to delete AI session:')).toBe(0);
    // 이벤트 루프를 한 바퀴 돌려 늦게 오는 unhandledRejection 도 잡는다
    await new Promise((r) => setTimeout(r, 0));
    expect(unhandled).toEqual([]);
  });

  it('거절 처리기 안의 오류는 Failed after 로 둔갑하지 않는다', async () => {
    mutateAsync.mockRejectedValue(new Error('boom'));
    // 거절 처리기의 console.error 자체를 던지게 해 처리기 안의 오류를 만든다
    errorSpy.mockImplementation((msg: unknown) => {
      if (msg === 'Failed to delete AI session:') throw new Error('handler boom');
    });
    render(<AISessionSidebar />);
    confirmDelete();

    await waitFor(() => expect(unhandled.length).toBe(1));
    expect(countLogs(errorSpy, 'Failed after AI session delete:')).toBe(0);
  });
});
