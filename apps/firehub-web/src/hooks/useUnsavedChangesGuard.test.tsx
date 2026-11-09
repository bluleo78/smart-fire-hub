/**
 * useUnsavedChangesGuard 단위 테스트
 *
 * (WD-651) 「이탈」 확정 즉시 가드(레지스트리 isDirty·click 캡처·beforeunload)가 꺼지고, 이 페이지로 다시 정착하거나
 * (커밋된 location == window.location) 폼이 깨끗해지면 다시 켜지는지 확인한다. 「취소」는 가드를 끄지 않는다.
 * 배경 설명은 훅의 leavingRef 주석 참고.
 *
 * (WD-662) 선택 옵션 onStay 와 isDialogOpen.
 * - onStay 는 사용자가 확인창에서 남기(「취소」·Esc)를 고른 때 정확히 한 번 불려야 한다.
 *   Radix 는 「취소」 클릭 때 onClick 과 onOpenChange(false) 로 handleCancel 을 두 번 부르고,
 *   「이탈」 클릭 뒤에도 onOpenChange(false) 가 이어지므로 중복·오호출을 거르는지 본다.
 * - 옵션 없이 쓰는 기존 화면(약 20곳)은 그대로 동작해야 한다.
 *
 * (WD-673) 커밋된 location 과 window.location 의 표기(한글·공백 퍼센트 인코딩)만 다를 때도 같은 위치로 보고 재무장하는지,
 * popstate 경로 「이탈」 뒤 history.back() 이 아무 일도 하지 않으면 제한 시간 뒤 가드를 다시 켜고 우회 표시를 거두는지 본다.
 *
 * 레지스트리 모듈 모킹은 파일 전체에 걸리지만, WD-662 묶음은 requestNavigate 경로만 쓰므로 실제 레지스트리가 필요 없다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { type ReactNode, useEffect } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BACK_NOOP_REARM_MS, useUnsavedChangesGuard } from './useUnsavedChangesGuard';

// 레지스트리에 등록되는 가드 진입점을 가로채 isDirty() 를 직접 조회한다.
// (WD-673) onIntercepted 로 popstate 가로채기 다이얼로그를 열고, bypassNextPopstateGuard 가 돌려주는 취소 함수 호출을 센다.
const registered = vi.hoisted(() => ({
  isDirty: null as null | (() => boolean),
  onIntercepted: null as null | (() => void),
  cancelBypass: null as null | ReturnType<typeof vi.fn>,
}));
vi.mock('../lib/unsaved-changes-guard-registry', () => ({
  registerActiveGuard: (entry: { isDirty: () => boolean; onIntercepted: () => void }) => {
    registered.isDirty = entry.isDirty;
    registered.onIntercepted = entry.onIntercepted;
    return () => {};
  },
  bypassNextPopstateGuard: vi.fn(() => registered.cancelBypass),
}));

/**
 * 훅을 쓰는 최소 페이지 — 다른 경로로 가는 링크로 다이얼로그를 연다.
 * 「form2 로」「form3 로」 버튼은 같은 컴포넌트가 마운트된 채 라우터 location 만 바뀌는 경우(새 id 등)를 흉내 낸다.
 */
function GuardedPage({ dirty }: { dirty: boolean }) {
  const { dialog } = useUnsavedChangesGuard(dirty);
  const navigate = useNavigate();
  return (
    <>
      <a href="/elsewhere">다른 곳</a>
      <button type="button" onClick={() => navigate('/form2')}>
        form2 로
      </button>
      <button type="button" onClick={() => navigate('/form3')}>
        form3 로
      </button>
      {dialog}
    </>
  );
}

function wrap(node: ReactNode, entry = '/form') {
  return <MemoryRouter initialEntries={[entry]}>{node}</MemoryRouter>;
}

/** 취소 가능한 beforeunload 를 발생시켜 가드가 preventDefault 했는지 돌려준다. */
function beforeUnloadPrevented() {
  const ev = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(ev);
  return ev.defaultPrevented;
}

/** 더티 상태로 렌더 → 링크 클릭 → 「이탈」 확정까지 진행하고, 확정 즉시 가드가 꺼졌는지 확인한다. */
function renderAndConfirmLeave() {
  const utils = render(wrap(<GuardedPage dirty />));
  fireEvent.click(screen.getByText('다른 곳'));
  fireEvent.click(screen.getByRole('button', { name: '이탈' }));
  expect(registered.isDirty?.()).toBe(false);
  return utils;
}

describe('useUnsavedChangesGuard — 이탈 확정 뒤 가드 해제 (WD-651)', () => {
  // 테스트가 jsdom 주소(window.location)를 바꾸므로 각 테스트 뒤 원래 주소로 되돌린다.
  // 훅은 커밋된 location 과 window.location 을 비교하므로, 시작 주소를 라우터 경로(/form)와 맞춘다.
  let savedUrl = '';
  beforeEach(() => {
    registered.isDirty = null;
    savedUrl = window.location.href;
    window.history.replaceState(null, '', '/form');
  });
  afterEach(() => {
    window.history.replaceState(null, '', savedUrl);
  });

  it('「이탈」 확정 즉시 레지스트리 isDirty·beforeunload 가드가 꺼진다', () => {
    // 확정 전에는 켜져 있다(대조) — 확정 전 상태를 봐야 하므로 renderAndConfirmLeave 를 쓰지 않는다
    render(wrap(<GuardedPage dirty />));
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);

    fireEvent.click(screen.getByText('다른 곳'));
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    expect(registered.isDirty?.()).toBe(false);
    expect(beforeUnloadPrevented()).toBe(false);
  });

  it('「취소」는 가드를 끄지 않는다', () => {
    render(wrap(<GuardedPage dirty />));
    fireEvent.click(screen.getByText('다른 곳'));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);
  });

  it('확정 뒤 popstate 로 이 페이지 경로에 되돌아오면(재마운트 없이 dirty 유지) 가드가 다시 켜진다', () => {
    renderAndConfirmLeave();

    // 다른 경로로의 popstate 는 재무장하지 않는다
    window.history.replaceState(null, '', '/elsewhere');
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(registered.isDirty?.()).toBe(false);

    // 다음 화면이 뜨기 전 뒤로 가기로 /form 에 되돌아온 경우
    window.history.replaceState(null, '', '/form');
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);
  });

  it('확정 뒤 커밋된 location 이 바뀌어도 window.location 과 다르면(떠나는 중) 꺼진 채, 같아지면 다시 켜진다', () => {
    renderAndConfirmLeave();

    // 위험 구간: 실제 주소는 이미 다른 곳인데 이 컴포넌트의 커밋된 location 만 /form2 로 바뀐 경우 — 재무장하지 않는다
    window.history.replaceState(null, '', '/other');
    fireEvent.click(screen.getByRole('button', { name: 'form2 로' }));
    expect(registered.isDirty?.()).toBe(false);
    expect(beforeUnloadPrevented()).toBe(false);

    // 탐색이 이 컴포넌트로 정착: 커밋된 location(/form3) == window.location(/form3) → 가드가 다시 켜진다
    window.history.replaceState(null, '', '/form3');
    fireEvent.click(screen.getByRole('button', { name: 'form3 로' }));
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);
    fireEvent.click(screen.getByText('다른 곳'));
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });

  it('확정 뒤 isDirty 가 false → true 로 다시 바뀌면 가드가 다시 켜진다', () => {
    const { rerender } = renderAndConfirmLeave();

    // 같은 컴포넌트가 마운트된 채 폼이 초기화됐다가(새 대상) 다시 편집된 경우
    act(() => rerender(wrap(<GuardedPage dirty={false} />)));
    expect(registered.isDirty?.()).toBe(false);
    act(() => rerender(wrap(<GuardedPage dirty />)));
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);

    // click 캡처도 다시 동작해 다이얼로그가 뜬다
    fireEvent.click(screen.getByText('다른 곳'));
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
  });
});

/** 훅 반환값을 테스트에서 읽기 위한 보관소 */
const exposed: { isDialogOpen?: () => boolean } = {};

/** (WD-662) 늘 dirty 인 화면 — 「이동」 버튼은 requestNavigate 로 확인창을 띄운다. */
function StayGuardedPage({ onStay, withOptions }: { onStay?: () => void; withOptions: boolean }) {
  // withOptions=false 는 기존 화면처럼 두 번째 인자를 아예 넘기지 않는 경우다
  const guard = useUnsavedChangesGuard(true, withOptions ? { onStay } : undefined);
  // 렌더 중 바깥 값을 바꾸지 않도록(react-hooks 규칙) effect 에서 넘긴다
  const { isDialogOpen } = guard;
  useEffect(() => {
    exposed.isDialogOpen = isDialogOpen;
  }, [isDialogOpen]);
  return (
    <div>
      <button type="button" onClick={() => guard.requestNavigate('/other')}>
        이동
      </button>
      {guard.dialog}
    </div>
  );
}

function renderStayPage(props: { onStay?: () => void; withOptions?: boolean }) {
  return render(
    <MemoryRouter initialEntries={['/start']}>
      <Routes>
        <Route path="/start" element={<StayGuardedPage withOptions={props.withOptions ?? true} onStay={props.onStay} />} />
        <Route path="/other" element={<div>다른 화면</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('useUnsavedChangesGuard — onStay·isDialogOpen (WD-662)', () => {
  // 앞 테스트가 남긴 함수를 읽지 않도록 매번 비운다
  beforeEach(() => {
    exposed.isDialogOpen = undefined;
    registered.isDirty = null;
  });

  it('「취소」를 누르면 onStay 가 한 번만 불리고 isDialogOpen 이 열림 여부를 동기로 알려 준다', () => {
    const onStay = vi.fn();
    renderStayPage({ onStay });
    expect(exposed.isDialogOpen?.()).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '이동' }));
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(exposed.isDialogOpen?.()).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onStay).toHaveBeenCalledTimes(1);
    expect(exposed.isDialogOpen?.()).toBe(false);
    // 남았으므로 이동하지 않는다
    expect(screen.queryByText('다른 화면')).not.toBeInTheDocument();
  });

  it('Esc 로 닫아도 onStay 가 한 번 불린다', () => {
    const onStay = vi.fn();
    renderStayPage({ onStay });
    fireEvent.click(screen.getByRole('button', { name: '이동' }));
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' });
    expect(onStay).toHaveBeenCalledTimes(1);
  });

  it('「이탈」을 고르면 onStay 는 불리지 않고 가려던 곳으로 간다', async () => {
    vi.useFakeTimers();
    try {
      const onStay = vi.fn();
      renderStayPage({ onStay });
      fireEvent.click(screen.getByRole('button', { name: '이동' }));
      fireEvent.click(screen.getByRole('button', { name: '이탈' }));
      await act(async () => {
        vi.runAllTimers();
      });
      expect(onStay).not.toHaveBeenCalled();
      expect(screen.getByText('다른 화면')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('옵션 없이 쓰는 화면은 이전처럼 「취소」에 머무른다', () => {
    renderStayPage({ withOptions: false });
    fireEvent.click(screen.getByRole('button', { name: '이동' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.queryByText('다른 화면')).not.toBeInTheDocument();
  });
});

describe('useUnsavedChangesGuard — 한글·공백 경로에서 이탈 확정 뒤 재무장 (WD-673)', () => {
  let savedUrl = '';
  beforeEach(() => {
    registered.isDirty = null;
    savedUrl = window.location.href;
  });
  afterEach(() => {
    window.history.replaceState(null, '', savedUrl);
  });

  it('커밋된 location 이 원문 한글·공백이고 window.location 이 퍼센트 인코딩이어도 같은 위치면 다시 켜진다', () => {
    // MemoryRouter 는 원문 그대로 들고 있고, jsdom 주소는 한글·공백을 퍼센트 인코딩한다 — 문자열로는 늘 다르다.
    const raw = '/form/검색 화면?q=한글 값#요약 1';
    window.history.replaceState(null, '', raw);
    expect(window.location.search).not.toContain('한글');
    render(wrap(<GuardedPage dirty />, raw));
    fireEvent.click(screen.getByText('다른 곳'));
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    expect(registered.isDirty?.()).toBe(false);

    // 다음 화면이 뜨기 전 뒤로 가기로 같은 위치에 되돌아온 경우 — 표기만 다르고 같은 위치이므로 재무장해야 한다
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);
  });
});

describe('useUnsavedChangesGuard — popstate 경로 이탈의 뒤로 가기가 아무 일도 안 할 때 (WD-673)', () => {
  let savedUrl = '';
  let backSpy: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    vi.useFakeTimers();
    registered.isDirty = null;
    registered.cancelBypass = vi.fn();
    savedUrl = window.location.href;
    window.history.replaceState(null, '', '/form');
    // jsdom 의 history.back() 대신 아무 일도 하지 않는 뒤로 가기(되돌아갈 기록이 없는 경우)를 흉내 낸다
    backSpy = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  });
  afterEach(() => {
    backSpy.mockRestore();
    vi.useRealTimers();
    window.history.replaceState(null, '', savedUrl);
  });

  /** 더티 상태로 렌더 → 브라우저 뒤로 가기 가로채기(onIntercepted)로 다이얼로그 → 「이탈」 → history.back() 실행까지. */
  function confirmLeaveViaPopstate() {
    render(wrap(<GuardedPage dirty />));
    act(() => registered.onIntercepted?.());
    fireEvent.click(screen.getByRole('button', { name: '이탈' }));
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(backSpy).toHaveBeenCalledTimes(1);
    expect(registered.isDirty?.()).toBe(false);
  }

  it('제한 시간 안에 popstate 가 없으면 가드를 다시 켜고 쓰이지 않은 우회 표시를 거둔다', () => {
    confirmLeaveViaPopstate();
    act(() => {
      vi.advanceTimersByTime(BACK_NOOP_REARM_MS - 1);
    });
    expect(registered.isDirty?.()).toBe(false);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(registered.isDirty?.()).toBe(true);
    expect(beforeUnloadPrevented()).toBe(true);
    expect(registered.cancelBypass).toHaveBeenCalledTimes(1);
  });

  it('제한 시간 안에 popstate 가 오면(실제로 이동) 꺼진 채 두고 우회 표시도 건드리지 않는다', () => {
    confirmLeaveViaPopstate();
    window.history.replaceState(null, '', '/elsewhere');
    act(() => {
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    act(() => {
      vi.advanceTimersByTime(BACK_NOOP_REARM_MS * 2);
    });
    expect(registered.isDirty?.()).toBe(false);
    expect(registered.cancelBypass).not.toHaveBeenCalled();
  });
});
