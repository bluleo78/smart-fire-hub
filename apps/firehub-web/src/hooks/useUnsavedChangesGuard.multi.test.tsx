/**
 * useUnsavedChangesGuard 여러 가드 단위 테스트 (WD-1160)
 *
 * 같은 화면에 가드가 둘 마운트돼(바깥 Page·안쪽 Editor) 모두 dirty 여도 이동 한 번에 확인은 한 번, 나중에 등록된 dirty 가드가
 * 묻는지 본다. 끝의 requestNavigate 묶음은 내 폼이 깨끗해도 다른 가드가 dirty 이면 그 가드가 묻는지 본다(WD-1160). 실제 레지스트리·전역 popstate 인터셉터를 쓰므로 레지스트리를 모킹하는 useUnsavedChangesGuard.test.tsx 와 파일을 나눴다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  hasActiveLeaveGuard,
  installPopstateGuardInterceptor,
} from '../lib/unsaved-changes-guard-registry';
import { BACK_NOOP_REARM_MS, useUnsavedChangesGuard } from './useUnsavedChangesGuard';

let stayed: string[] = [];

/** 훅을 쓰는 가드 컴포넌트 — 다이얼로그 앞에 어느 쪽인지 식별하는 표식을 둔다. */
function Guarded({ name, dirty }: { name: string; dirty: boolean }) {
  // 「취소」를 누르면 확인 창을 연 쪽의 onStay 만 불리므로, 어느 가드가 물었는지 stayed 에 남는다.
  const { dialog } = useUnsavedChangesGuard(dirty, { onStay: () => stayed.push(name) });
  return (
    <div data-testid={`host-${name}`}>
      <span>{name}</span>
      {dialog}
    </div>
  );
}

/** Editor 를 Page 안에 둔 중첩 구성 — 같은 커밋에 마운트된다. */
function NestedPage() {
  const { dialog } = useUnsavedChangesGuard(true, { onStay: () => stayed.push('Page') });
  return (
    <div>
      {dialog}
      <Guarded name="Editor" dirty />
    </div>
  );
}

function Screen({ pageDirty, editorDirty }: { pageDirty: boolean; editorDirty: boolean }) {
  return (
    <MemoryRouter initialEntries={['/form']}>
      <a href="/other">다른 곳</a>
      <Guarded name="Page" dirty={pageDirty} />
      <Guarded name="Editor" dirty={editorDirty} />
    </MemoryRouter>
  );
}

/** Editor 를 조건부로 마운트하는 화면 — 확인한 가드(Editor)만 사라지는 경우를 만든다. */
function ToggleScreen({ showEditor, pageDirty = true }: { showEditor: boolean; pageDirty?: boolean }) {
  return (
    <MemoryRouter initialEntries={['/form']}>
      <a href="/other">다른 곳</a>
      <a href="/another">또 다른 곳</a>
      <Guarded name="Page" dirty={pageDirty} />
      {showEditor && <Guarded name="Editor" dirty />}
    </MemoryRouter>
  );
}

/** 열린 확인 창 목록. */
function dialogs() {
  return screen.queryAllByRole('alertdialog');
}

describe('useUnsavedChangesGuard — 여러 가드 (WD-1160)', () => {
  let savedUrl = '';
  beforeAll(() => installPopstateGuardInterceptor());
  beforeEach(() => {
    savedUrl = window.location.href;
    stayed = [];
    window.history.replaceState(null, '', '/form');
  });
  afterEach(() => {
    window.history.replaceState(null, '', savedUrl);
  });

  it('둘 다 dirty 인 링크 클릭 — 확인 창은 하나', () => {
    render(<Screen pageDirty editorDirty />);
    fireEvent.click(screen.getByText('다른 곳'));
    expect(dialogs()).toHaveLength(1);
  });

  it('popstate — 확인 창 하나, 「이탈」 뒤 history.back 이 실행되고 다른 가드가 다시 가로채지 않는다', () => {
    vi.useFakeTimers();
    try {
      const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
      render(<Screen pageDirty editorDirty />);
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      expect(dialogs()).toHaveLength(1);
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      expect(backSpy).toHaveBeenCalledTimes(1);
      // 이탈 확정이 건 우회 표시로 이어 오는 popstate 는 어느 가드도 가로채지 않는다
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      expect(dialogs()).toHaveLength(0);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it('나중 마운트 가드(Editor)가 묻는다 — 클릭·popstate 모두, 리렌더 뒤에도', () => {
    const { rerender } = render(<Screen pageDirty editorDirty />);
    fireEvent.click(screen.getByText('다른 곳'));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(stayed).toEqual(['Editor']);
    // 훅 의존이 바뀌는 리렌더(dirty 토글) 뒤에도 순서가 흔들리지 않는다
    rerender(<Screen pageDirty={false} editorDirty />);
    rerender(<Screen pageDirty editorDirty />);
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(dialogs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(stayed).toEqual(['Editor', 'Editor']);
  });

  it('나중 가드(Editor)만 clean 이면 앞 가드(Page)가 묻는다', () => {
    render(<Screen pageDirty editorDirty={false} />);
    fireEvent.click(screen.getByText('다른 곳'));
    expect(dialogs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(stayed).toEqual(['Page']);
  });

  it('같은 커밋에 중첩 마운트(Editor 가 Page 안) — 이펙트는 자식 먼저라 부모(Page)가 하나만 묻는다', () => {
    render(
      <MemoryRouter initialEntries={['/form']}>
        <a href="/other">다른 곳</a>
        <NestedPage />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByText('다른 곳'));
    expect(dialogs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(stayed).toEqual(['Page']);
  });

  it('「이탈」 확정 뒤 다른 dirty 가드도 떠남 처리 — 이동 중 창에 두 번째 확인·가드가 남지 않는다 (WD-651 창)', () => {
    vi.useFakeTimers();
    try {
      const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
      render(<Screen pageDirty editorDirty />);
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      expect(dialogs()).toHaveLength(1);
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      expect(backSpy).toHaveBeenCalledTimes(1);
      // 다음 화면 로딩 중: 주소는 이미 새 경로인데 이 페이지는 옛 location 으로 남은 상태
      window.history.replaceState(null, '', '/next');
      // 우회 표시를 쓰는 이어 오는 popstate 한 번
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      // 그 뒤의 popstate(Back 연타)에는 어느 가드도 창을 열지 않는다
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      expect(dialogs()).toHaveLength(0);
      // 어느 가드도 떠나기를 막지 않는다(beforeunload·client.ts 판단)
      expect(hasActiveLeaveGuard()).toBe(false);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it('popstate 「이탈」 뒤 뒤로 가기가 no-op 이고 제한 시간 안에 popstate 가 없으면 다른 가드도 다시 묻는다 (WD-673)', () => {
    vi.useFakeTimers();
    try {
      const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
      render(<Screen pageDirty editorDirty />);
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      // 대기 중에는 모든 가드가 떠나는 중이라 링크 클릭도 가로채지 않는다
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(0);
      act(() => {
        vi.advanceTimersByTime(BACK_NOOP_REARM_MS);
      });
      // 시간이 지나도 popstate 가 없었다 — 모든 가드가 다시 켜져 확인을 다시 묻는다
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(1);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it('확인한 가드(Editor)가 no-op 타이머 전에 언마운트돼도 남은 가드(Page)는 다시 켜진다 (WD-1160)', () => {
    vi.useFakeTimers();
    try {
      const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
      const { rerender } = render(<ToggleScreen showEditor />);
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      // 뒤로 가기는 일어나지 않았는데(popstate 없음) 확인한 가드가 먼저 사라진다
      rerender(<ToggleScreen showEditor={false} />);
      // 대기(타이머)는 언마운트로 치워지지 않고 살아 있다 — 아직은 떠나는 중이라 가로채지 않는다
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(0);
      // 제한 시간 안에 popstate 가 없었으므로 타이머가 남은 가드(Page)를 다시 켠다
      act(() => {
        vi.advanceTimersByTime(BACK_NOOP_REARM_MS);
      });
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(1);
      expect(hasActiveLeaveGuard()).toBe(true);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });

  it('오래된 대기의 재무장이 그 뒤 다른 가드가 확정한 떠남을 되돌리지 않는다 (WD-1160)', () => {
    vi.useFakeTimers();
    try {
      const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
      const { rerender } = render(<ToggleScreen showEditor />);
      // Editor 가 popstate 경로로 확정(no-op 뒤로 가기) → Editor 언마운트, 대기(1초 타이머)는 살아 있다
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      rerender(<ToggleScreen showEditor={false} />);
      // 제한 시간 안에 Page 폼이 초기화됐다 다시 편집돼(깨끗해지면 떠남 표시가 풀린다) 링크 이동을 확정한다
      rerender(<ToggleScreen showEditor={false} pageDirty={false} />);
      rerender(<ToggleScreen showEditor={false} />);
      act(() => {
        vi.advanceTimersByTime(BACK_NOOP_REARM_MS / 2);
      });
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(1);
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      expect(hasActiveLeaveGuard()).toBe(false);
      // 이제 Editor 의 오래된 타이머가 돈다 — Page 의 새 떠남 표시를 되돌리면 안 된다
      act(() => {
        vi.advanceTimersByTime(BACK_NOOP_REARM_MS);
      });
      expect(hasActiveLeaveGuard()).toBe(false);
      fireEvent.click(screen.getByText('또 다른 곳'));
      expect(dialogs()).toHaveLength(0);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });
});

/** requestNavigate 를 노출하는 가드 화면 — 확인 창 문구는 같아 name 으로 구분한다. onStay·isDialogOpen 을 보관소에 남긴다. */
const reqLog: {
  stayed: string[];
  left: string[];
  isOpen: Record<string, () => boolean>;
  results: boolean[];
} = {
  stayed: [],
  left: [],
  isOpen: {},
  results: [],
};
function RequestForm({ name, dirty }: { name: string; dirty: boolean }) {
  const { dialog, requestNavigate, isDialogOpen } = useUnsavedChangesGuard(dirty, {
    onStay: () => reqLog.stayed.push(name),
    // (WD-1166) 「이탈」 확정 때 불리는 콜백 — 호출 횟수를 본다
    onLeave: () => reqLog.left.push(name),
  });
  useEffect(() => {
    reqLog.isOpen[name] = isDialogOpen;
  }, [name, isDialogOpen]);
  return (
    <section aria-label={name}>
      <button type="button" onClick={() => reqLog.results.push(requestNavigate('/other'))}>
        {name} 이동
      </button>
      {/* (WD-1166) 대상이 다른 두 번째 요청 — 열린 창의 대상을 바꾸지 않는지 본다 */}
      <button type="button" onClick={() => reqLog.results.push(requestNavigate('/dash'))}>
        {name} 대시보드
      </button>
      {dialog}
    </section>
  );
}

function Where() {
  return <span data-testid="where">{useLocation().pathname}</span>;
}

function TwoScreen({ aDirty, bDirty }: { aDirty: boolean; bDirty: boolean }) {
  return (
    <MemoryRouter initialEntries={['/start']}>
      <Where />
      <a href="/other">링크 이동</a>
      <Routes>
        <Route
          path="/start"
          element={
            <>
              <RequestForm name="A" dirty={aDirty} />
              <RequestForm name="B" dirty={bDirty} />
            </>
          }
        />
        <Route path="/other" element={<div>다른 화면</div>} />
        <Route path="/dash" element={<div>대시보드 화면</div>} />
      </Routes>
    </MemoryRouter>
  );
}

/** (WD-1166 리뷰 후속) 가드가 Routes 밖(레이아웃)에 있어 이동해도 남는 화면 */
function LayoutScreen({ dirty }: { dirty: boolean }) {
  return (
    <MemoryRouter initialEntries={['/start']}>
      <Where />
      <RequestForm name="L" dirty={dirty} />
      <Routes>
        <Route path="/start" element={<div>시작 화면</div>} />
        <Route path="/other" element={<div>다른 화면</div>} />
        <Route path="/dash" element={<div>대시보드 화면</div>} />
      </Routes>
    </MemoryRouter>
  );
}

function renderTwo(aDirty: boolean, bDirty: boolean) {
  return render(<TwoScreen aDirty={aDirty} bDirty={bDirty} />);
}

async function flushTimers() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 20));
  });
}

describe('requestNavigate — 다른 가드가 dirty 일 때 (WD-1160)', () => {
  beforeEach(() => {
    reqLog.stayed = [];
    reqLog.left = [];
    reqLog.isOpen = {};
    reqLog.results = [];
  });

  it('내 폼(A)이 깨끗해도 다른 가드(B)가 dirty 이면 A 자신의 확인 창이 열린다 — 취소하면 A 의 onStay 만 불리고 이동하지 않는다', async () => {
    renderTwo(false, true);
    expect(reqLog.isOpen.A()).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동' }));
    // 확인 창은 하나, 호출한 A 가 열림을 안다
    expect(dialogs()).toHaveLength(1);
    expect(reqLog.isOpen.A()).toBe(true);
    expect(reqLog.isOpen.B()).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    await flushTimers();
    expect(reqLog.stayed).toEqual(['A']);
    expect(screen.getByTestId('where')).toHaveTextContent('/start');
  });

  it('확정하면 한 번 이동하고 B 도 떠나는 중으로 돌아간다', async () => {
    renderTwo(false, true);
    expect(hasActiveLeaveGuard()).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동' }));
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/start');
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
    expect(hasActiveLeaveGuard()).toBe(false);
  });

  it('모두 깨끗하면 바로 이동한다', async () => {
    renderTwo(false, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동' }));
    await flushTimers();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('내 폼이 dirty 이면 내 확인 창으로 묻고 확정하면 이동한다(기존 동작)', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동' }));
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/start');
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });
});

describe('requestNavigate 결과 — 가로챔 여부·열린 대상 유지·onLeave (WD-1166)', () => {
  let savedUrl = '';
  beforeAll(() => installPopstateGuardInterceptor());
  beforeEach(() => {
    savedUrl = window.location.href;
    reqLog.stayed = [];
    reqLog.left = [];
    reqLog.isOpen = {};
    reqLog.results = [];
    window.history.replaceState(null, '', '/start');
  });
  afterEach(() => {
    window.history.replaceState(null, '', savedUrl);
  });

  it('모두 깨끗하면 이동하고 false', async () => {
    renderTwo(false, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    await flushTimers();
    expect(reqLog.results).toEqual([false]);
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('dirty 면 확인 창을 열고 true — 이동하지 않는다', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    await flushTimers();
    expect(reqLog.results).toEqual([true]);
    expect(dialogs()).toHaveLength(1);
    expect(screen.getByTestId('where')).toHaveTextContent('/start');
  });

  it('「이탈」 확정 직후 창에서는 묻지 않고 이동하고 false (WD-1166 ①)', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    // 확정 → navigate 는 다음 tick 이라 아직 /start. 이 사이에 다시 요청한다.
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    expect(reqLog.results).toEqual([true, false]);
    expect(dialogs()).toHaveLength(0);
    await flushTimers();
    expect(reqLog.stayed).toEqual([]);
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('popstate 로 열린 창에서는 대상을 바꾸지 않고 true — 「이탈」은 뒤로 가기 (WD-1166 ③)', () => {
    vi.useFakeTimers();
    // (WD-1166 리뷰 후속) 목·타이머 정리를 finally 에서 한다 — 단언이 실패해도 뒤 시험이 가짜 history.back·타이머를 물려받지 않게
    const backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
    try {
      renderTwo(true, false);
      act(() => {
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      expect(dialogs()).toHaveLength(1);
      fireEvent.click(screen.getByRole('button', { name: 'A 대시보드', hidden: true }));
      expect(reqLog.results).toEqual([true]);
      expect(dialogs()).toHaveLength(1);
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      act(() => {
        vi.advanceTimersByTime(0);
      });
      expect(backSpy).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId('where')).toHaveTextContent('/start');
    } finally {
      try {
        // 뒤로 가기 대기(타이머·popstate 리스너)와 우회 표시를 끝내 다음 시험에 전역 상태를 남기지 않는다
        act(() => {
          vi.advanceTimersByTime(BACK_NOOP_REARM_MS);
        });
      } finally {
        backSpy.mockRestore();
        vi.useRealTimers();
      }
    }
  });

  it('창이 열린 채 폼이 깨끗해지면 이동하며 창을 닫는다 — 이동 뒤에도 남는 가드 (WD-1166 리뷰 후속)', async () => {
    // 레이아웃처럼 Routes 밖에 걸린 가드라 이동해도 언마운트되지 않는다 — 창을 닫지 않으면 이동 뒤에도 남는다
    const { rerender } = render(<LayoutScreen dirty />);
    fireEvent.click(screen.getByRole('button', { name: 'L 이동', hidden: true }));
    expect(dialogs()).toHaveLength(1);
    rerender(<LayoutScreen dirty={false} />);
    fireEvent.click(screen.getByRole('button', { name: 'L 대시보드', hidden: true }));
    expect(reqLog.results).toEqual([true, false]);
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/dash');
    expect(dialogs()).toHaveLength(0);
    expect(reqLog.isOpen.L()).toBe(false);
    // 사용자가 고른 것이 아니므로 onStay·onLeave 는 불리지 않는다
    expect(reqLog.stayed).toEqual([]);
    expect(reqLog.left).toEqual([]);
  });

  it('링크 클릭으로 열린 창에서도 대상을 바꾸지 않고 true — 「이탈」은 링크 대상으로 이동 (WD-1166)', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('link', { name: '링크 이동' }));
    expect(dialogs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'A 대시보드', hidden: true }));
    expect(reqLog.results).toEqual([true]);
    expect(dialogs()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('popstate 로 열린 창에서 요청 뒤 「취소」면 onStay 1회', () => {
    renderTwo(true, false);
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    fireEvent.click(screen.getByRole('button', { name: 'A 대시보드', hidden: true }));
    expect(reqLog.results).toEqual([true]);
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(reqLog.stayed).toEqual(['A']);
    expect(dialogs()).toHaveLength(0);
  });

  it('requestNavigate 로 열린 창에 두 번째 요청이 와도 첫 대상으로 이동', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    fireEvent.click(screen.getByRole('button', { name: 'A 대시보드', hidden: true }));
    expect(reqLog.results).toEqual([true, true]);
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/other');
  });

  it('창이 열린 중 폼이 깨끗해지면 막을 가드가 없으니 이동하고 false', async () => {
    const { rerender } = renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    expect(dialogs()).toHaveLength(1);
    rerender(<TwoScreen aDirty={false} bDirty={false} />);
    fireEvent.click(screen.getByRole('button', { name: 'A 대시보드', hidden: true }));
    expect(reqLog.results).toEqual([true, false]);
    await flushTimers();
    expect(screen.getByTestId('where')).toHaveTextContent('/dash');
  });

  it('「이탈」 확정이면 onLeave 1회·onStay 0회', async () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    await flushTimers();
    expect(reqLog.left).toEqual(['A']);
    expect(reqLog.stayed).toEqual([]);
  });

  it('「취소」면 onLeave 0회', () => {
    renderTwo(true, false);
    fireEvent.click(screen.getByRole('button', { name: 'A 이동', hidden: true }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(reqLog.left).toEqual([]);
    expect(reqLog.stayed).toEqual(['A']);
  });
});
