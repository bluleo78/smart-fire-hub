/**
 * useUnsavedChangesGuard 단위 테스트 (WD-651)
 *
 * 「이탈」 확정 즉시 가드(레지스트리 isDirty·click 캡처·beforeunload)가 꺼지고, 이 페이지로 다시 정착하거나
 * (커밋된 location == window.location) 폼이 깨끗해지면 다시 켜지는지 확인한다. 「취소」는 가드를 끄지 않는다.
 * 배경 설명은 훅의 leavingRef 주석 참고.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useUnsavedChangesGuard } from './useUnsavedChangesGuard';

// 레지스트리에 등록되는 가드 진입점을 가로채 isDirty() 를 직접 조회한다.
const registered = vi.hoisted(() => ({ isDirty: null as null | (() => boolean) }));
vi.mock('../lib/unsaved-changes-guard-registry', () => ({
  registerActiveGuard: (entry: { isDirty: () => boolean }) => {
    registered.isDirty = entry.isDirty;
    return () => {};
  },
  bypassNextPopstateGuard: vi.fn(),
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

function wrap(node: ReactNode) {
  return <MemoryRouter initialEntries={['/form']}>{node}</MemoryRouter>;
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
