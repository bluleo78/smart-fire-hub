/**
 * useUnsavedChangesGuard 여러 가드 단위 테스트 (WD-1160)
 *
 * 같은 화면에 가드가 둘 마운트돼(바깥 Page·안쪽 Editor) 모두 dirty 여도 이동 한 번에 확인은 한 번, 나중에 등록된 dirty 가드가
 * 묻는지 본다. 실제 레지스트리·전역 popstate 인터셉터를 쓰므로 레지스트리를 모킹하는 useUnsavedChangesGuard.test.tsx 와 파일을 나눴다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
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
function ToggleScreen({ showEditor }: { showEditor: boolean }) {
  return (
    <MemoryRouter initialEntries={['/form']}>
      <a href="/other">다른 곳</a>
      <Guarded name="Page" dirty />
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
      fireEvent.click(screen.getByText('다른 곳'));
      expect(dialogs()).toHaveLength(1);
      expect(hasActiveLeaveGuard()).toBe(true);
      backSpy.mockRestore();
    } finally {
      vi.useRealTimers();
    }
  });
});
