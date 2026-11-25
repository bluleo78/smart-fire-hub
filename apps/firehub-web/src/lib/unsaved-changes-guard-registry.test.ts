/**
 * unsaved-changes-guard-registry 단위 테스트 (WD-673)
 *
 * 「이탈」 확정 뒤 history.back() 이 아무 일도 하지 않으면 우회 표시(bypassNextPopstateGuard)가 남아, 나중의 진짜 뒤로 가기가
 * 확인 없이 통과됐다. 돌려받은 취소 함수가 자기 표시만, 아직 쓰이지 않았을 때만 거두는지 실제 모듈로 확인한다.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  bypassNextPopstateGuard,
  hasActiveLeaveGuard,
  installPopstateGuardInterceptor,
  registerActiveGuard,
  registerLeaveBlocker,
} from './unsaved-changes-guard-registry';

describe('bypassNextPopstateGuard 취소 함수 (WD-673)', () => {
  const onIntercepted = vi.fn();
  let unregister: () => void = () => {};
  let savedUrl = '';

  beforeAll(() => installPopstateGuardInterceptor());
  beforeEach(() => {
    savedUrl = window.location.href;
    onIntercepted.mockClear();
    // 늘 dirty 인 가드 — 우회 표시가 없으면 popstate 를 가로챈다
    unregister = registerActiveGuard({
      isDirty: () => true,
      restorePath: () => '/form',
      onIntercepted,
    });
  });
  afterEach(() => {
    unregister();
    window.history.replaceState(null, '', savedUrl);
  });

  /** 브라우저 뒤로 가기에 해당하는 popstate 를 한 번 보낸다. */
  function pop() {
    window.dispatchEvent(new PopStateEvent('popstate'));
  }

  it('쓰이기 전에 취소하면 다음 popstate 를 다시 가로챈다', () => {
    const cancel = bypassNextPopstateGuard();
    cancel();
    pop();
    expect(onIntercepted).toHaveBeenCalledTimes(1);
  });

  it('옛 취소 함수는 그 뒤에 건 새 우회 표시를 지우지 않는다', () => {
    const staleCancel = bypassNextPopstateGuard();
    bypassNextPopstateGuard();
    staleCancel();
    pop();
    expect(onIntercepted).not.toHaveBeenCalled();
  });

  it('이미 쓰인 뒤의 취소는 아무것도 바꾸지 않는다(다음 popstate 는 가로챈다)', () => {
    const cancel = bypassNextPopstateGuard();
    pop();
    expect(onIntercepted).not.toHaveBeenCalled();
    cancel();
    pop();
    expect(onIntercepted).toHaveBeenCalledTimes(1);
  });
});

describe('hasActiveLeaveGuard — 페이지 dirty 가드 (WD-1122)', () => {
  it('등록이 없으면 false, dirty 여부를 따르고, 해제 뒤 false', () => {
    expect(hasActiveLeaveGuard()).toBe(false);
    let dirty = false;
    const unregister = registerActiveGuard({ isDirty: () => dirty, restorePath: () => '/', onIntercepted: vi.fn() });
    expect(hasActiveLeaveGuard()).toBe(false);
    dirty = true;
    expect(hasActiveLeaveGuard()).toBe(true);
    unregister();
    expect(hasActiveLeaveGuard()).toBe(false);
  });
});

describe('registerLeaveBlocker · hasActiveLeaveGuard (WD-1122)', () => {
  it('차단 조건이 여럿이어도 하나라도 참이면 true, 해제하면 빠진다', () => {
    expect(hasActiveLeaveGuard()).toBe(false);
    let a = false;
    let b = false;
    const offA = registerLeaveBlocker(() => a);
    const offB = registerLeaveBlocker(() => b);
    expect(hasActiveLeaveGuard()).toBe(false);
    b = true;
    expect(hasActiveLeaveGuard()).toBe(true);
    b = false;
    a = true;
    expect(hasActiveLeaveGuard()).toBe(true);
    offA();
    expect(hasActiveLeaveGuard()).toBe(false);
    b = true;
    expect(hasActiveLeaveGuard()).toBe(true);
    offB();
    expect(hasActiveLeaveGuard()).toBe(false);
  });

  it('페이지 dirty 가드도 포함하고, 차단 조건이 페이지 가드를 덮어쓰지 않는다', () => {
    const offGuard = registerActiveGuard({ isDirty: () => true, restorePath: () => '/', onIntercepted: () => {} });
    const offBlocker = registerLeaveBlocker(() => false);
    expect(hasActiveLeaveGuard()).toBe(true);
    offBlocker();
    offGuard();
    expect(hasActiveLeaveGuard()).toBe(false);
  });
});
