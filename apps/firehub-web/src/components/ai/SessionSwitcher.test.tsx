/**
 * SessionSwitcher 단위 테스트 — 대화 삭제 뒤 오류 로그의 문구 (WD-1013)
 *
 * 기대 동작:
 * - DELETE 가 실패하면 'Failed to delete AI session:' 만 남고 onSessionDeleted 는 불리지 않는다.
 * - 삭제가 성공한 뒤 onSessionDeleted 가 던지면 'Failed after AI session delete:' 가 한 번만 남는다.
 * - 줄이 사라지는 순간 초점이 본문 main 이면(건너뛰기 뒤) 다음 줄 제목으로 옮긴다 (WD-980, WD-1039).
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mutateAsync = vi.fn();
let mockSessions: { id: number; sessionId: string; title: string }[] = [];
const onSessionDeleted = vi.fn();

// 쿼리 의존성은 모킹한다 — 관심사는 삭제 확인 뒤 promise 처리뿐이다.
vi.mock('../../hooks/queries/useAIChat', () => ({
  // 참조가 안정적이어야 [sessions] 효과가 렌더마다 돌지 않는다 — 시험이 mockSessions 를 바꿔 목록 갱신을 흉내 낸다
  useAISessions: () => ({ data: mockSessions }),
  useDeleteAISession: () => ({ mutateAsync }),
  usePendingSessionDeletes: () => new Set<number>(),
}));

import { SessionSwitcher } from './SessionSwitcher';

/** console.error 호출 중 첫 인자가 prefix 인 것의 수 */
function countLogs(spy: ReturnType<typeof vi.spyOn>, prefix: string) {
  return spy.mock.calls.filter((c: unknown[]) => c[0] === prefix).length;
}

/** 목록을 열고 휴지통 → 확인 창의 「삭제」 까지 눌러 삭제를 실행한다 */
function openAndDelete() {
  render(
    <SessionSwitcher
      currentSessionId={null}
      onNewSession={vi.fn()}
      onSessionDeleted={onSessionDeleted}
    />,
  );
  fireEvent.click(screen.getByRole('button', { expanded: false }));
  fireEvent.click(screen.getByRole('button', { name: '시험 대화 삭제' }));
  fireEvent.click(screen.getByRole('button', { name: '삭제' }));
}

let errorSpy: ReturnType<typeof vi.spyOn>;
const unhandled: unknown[] = [];
const onUnhandled = (reason: unknown) => unhandled.push(reason);

beforeEach(() => {
  mockSessions = [{ id: 7, sessionId: 'sess-7', title: '시험 대화' }];
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

describe('SessionSwitcher — 삭제 뒤 오류 로그 (WD-1013)', () => {
  it('DELETE 가 실패하면 Failed to delete 만 남고 Failed after 는 없다', async () => {
    mutateAsync.mockRejectedValue(new Error('boom'));
    openAndDelete();

    await waitFor(() => expect(countLogs(errorSpy, 'Failed to delete AI session:')).toBe(1));
    expect(countLogs(errorSpy, 'Failed after AI session delete:')).toBe(0);
    expect(onSessionDeleted).not.toHaveBeenCalled();
  });

  it('onSessionDeleted 가 던지면 Failed after 가 한 번만 남고 처리되지 않은 거절은 없다', async () => {
    mutateAsync.mockResolvedValue(undefined);
    onSessionDeleted.mockImplementation(() => {
      throw new Error('after boom');
    });
    openAndDelete();

    await waitFor(() => expect(countLogs(errorSpy, 'Failed after AI session delete:')).toBe(1));
    expect(countLogs(errorSpy, 'Failed to delete AI session:')).toBe(0);
    await new Promise((r) => setTimeout(r, 0));
    expect(unhandled).toEqual([]);
  });
});

describe('SessionSwitcher — 줄이 사라질 때 초점이 본문 main 인 경우 (WD-980, WD-1039)', () => {
  it('줄이 사라질 때 초점이 본문 main 이면(건너뛰기 뒤) 다음 줄 제목으로 옮긴다 (WD-980, WD-1039)', () => {
    // 삭제 응답은 붙잡아 둔다(영영 풀리지 않는 promise) — 목록 갱신은 시험이 직접 한다
    mutateAsync.mockReturnValue(new Promise(() => {}));
    const sessions = [
      { id: 1, sessionId: 'sw-1', title: '첫째 대화' },
      { id: 2, sessionId: 'sw-2', title: '둘째 대화' },
      { id: 3, sessionId: 'sw-3', title: '셋째 대화' },
    ];
    mockSessions = sessions;
    // 건너뛰기 뒤 tabindex=-1 이 남은 본문 main — 앱 셸 대신 시험이 문서에 붙인다
    const main = document.createElement('main');
    main.id = 'main-content';
    main.setAttribute('tabindex', '-1');

    // 같은 엘리먼트 참조로 rerender 하면 React 가 건너뛰므로 매번 새로 만든다
    const ui = () => (
      <SessionSwitcher currentSessionId={null} onNewSession={vi.fn()} onSessionDeleted={onSessionDeleted} />
    );
    const { rerender } = render(ui());
    fireEvent.click(screen.getByRole('button', { expanded: false }));
    fireEvent.click(screen.getByRole('button', { name: '둘째 대화 삭제' }));
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    expect(mutateAsync).toHaveBeenCalledWith(2);

    // 초점을 받은 적이 있어야 효과가 보정한다(hadFocus) — 줄 제목에 초점을 준다
    screen.getByRole('button', { name: '둘째 대화' }).focus();
    const next = screen.getByRole('button', { name: '셋째 대화' });
    const focusSpy = vi.spyOn(next, 'focus');

    // 모달 목록이 열린 동안엔 FocusScope 가 main 초점을 되돌리므로, 효과가 보는 activeElement 만 main 으로 고정한다
    // main 을 붙이는 것부터 try 안에 둔다 — 중간에 던져도 finally 가 항상 치운다.
    const activeSpy = vi.spyOn(document, 'activeElement', 'get').mockReturnValue(main);
    try {
      document.body.appendChild(main);
      mockSessions = sessions.filter((s) => s.id !== 2);
      rerender(ui());
    } finally {
      activeSpy.mockRestore();
      main.remove();
    }
    expect(focusSpy).toHaveBeenCalledTimes(1);
    expect(next).toHaveFocus();
  });
});
