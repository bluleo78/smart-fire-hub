/**
 * useSubmitGuard / guardFormSubmit / useGuardedSubmit 단위 테스트 (WD-683).
 *
 * 제출 버튼을 aria-disabled 로만 잠그므로(PendingSubmitButton) 진행 중 재제출은 폼 onSubmit 의 잠금이 막아야 한다.
 * 잠금이 「진행 중 재제출 무시·무시한 제출도 기본 동작 차단·끝나면 다시 허용·여러 경로가 같은 잠금 공유」를 지키는지 본다.
 * 무시한 제출의 Promise 는 진행 중 제출이 끝날 때 끝난다(WD-678 충돌 창 잠금과 정렬) — 그 전에 끝나면 창 잠금이 먼저 풀린다.
 * 결과로 무시 여부를 가른다(WD-688) — 무시한 호출은 { ignored: true }, 실제 제출은 { ignored: false }.
 *
 * 시간에 기대지 않는다: 잠금은 진행 중이면 첫 await 전에(동기로) preventDefault 하고 돌아오므로, 두 번째 호출 직후 동기로 센다.
 * 잠금이 없다면 그 시점에 이미 제출 함수가 한 번 더 불려 있어 기다릴 것 없이 실패한다.
 */
import { act, renderHook } from '@testing-library/react';
import type { FormEvent } from 'react';
import { useForm } from 'react-hook-form';
import { describe, expect, it, vi } from 'vitest';

import { guardFormSubmit, useGuardedSubmit, useSubmitGuard } from './useSubmitGuard';

type FormValues = { name: string };

/** 폼 제출 이벤트 흉내 — preventDefault 호출 여부만 본다 */
function fakeSubmitEvent() {
  const preventDefault = vi.fn();
  return { event: { preventDefault, persist: vi.fn() } as unknown as FormEvent<HTMLFormElement>, preventDefault };
}

/**
 * 테스트가 직접 끝내는 제출 함수와, 그것을 돌려주는 가짜 handleSubmit.
 * submitted: handleSubmit 이 돌려준 함수(실제 제출) — 잠금이 이것을 부르는지 센다.
 * finish(): 붙잡힌 제출을 모두 끝낸다(응답 도착). fail(): 붙잡힌 제출을 모두 실패시킨다.
 */
function heldForm() {
  const pending: { resolve: () => void; reject: (error: Error) => void }[] = [];
  const submitted = vi.fn(
    () =>
      new Promise<void>((resolve, reject) => {
        pending.push({ resolve, reject });
      }),
  );
  const handleSubmit = vi.fn(() => submitted);
  return {
    handleSubmit,
    submitted,
    finish: () => pending.splice(0).forEach((p) => p.resolve()),
    fail: (error: Error) => pending.splice(0).forEach((p) => p.reject(error)),
  };
}

/** Promise 가 끝났는지 기록한다 — 끝나기를 기다리지 않고(시간에 기대지 않고) 아직 진행 중인지 보려고 */
function track(promise: Promise<unknown>) {
  const state = { settled: false };
  promise.then(
    () => (state.settled = true),
    () => (state.settled = true),
  );
  return state;
}

/** 이미 끝난 Promise 의 후속 처리(then)가 돌 만큼 마이크로태스크를 비운다 */
const flushMicrotasks = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe('useSubmitGuard', () => {
  it('진행 중 재제출은 제출 함수를 부르지 않고 기본 동작을 막으며, 진행 중 제출이 끝날 때 함께 끝나고, 그 뒤에는 다시 허용한다', async () => {
    const { result } = renderHook(() => useSubmitGuard());
    const { submitted, finish } = heldForm();
    const submit = result.current(submitted);

    const first = submit(fakeSubmitEvent().event);
    expect(submitted).toHaveBeenCalledTimes(1);

    const second = fakeSubmitEvent();
    const secondDone = submit(second.event);
    expect(second.preventDefault).toHaveBeenCalledTimes(1);
    expect(submitted).toHaveBeenCalledTimes(1);
    // 무시한 제출은 붙잡힌 첫 제출이 끝나기 전에는 끝나지 않는다 — 이 Promise 로 잠그는 호출자(충돌 창)가 먼저 풀리지 않게
    const secondState = track(secondDone);
    await flushMicrotasks();
    expect(secondState.settled).toBe(false);

    finish();
    // 실제로 제출한 첫 호출과 무시된 두 번째 호출을 결과로 가를 수 있다(WD-688)
    await expect(first).resolves.toEqual({ ignored: false });
    await expect(secondDone).resolves.toEqual({ ignored: true });
    const third = submit(fakeSubmitEvent().event);
    expect(submitted).toHaveBeenCalledTimes(2);
    finish();
    // 잠금이 풀린 뒤의 제출은 다시 실제 제출이다
    await expect(third).resolves.toEqual({ ignored: false });
  });

  it('폼 이벤트 없이(버튼 onClick 등) 불러도 같은 잠금이 걸린다', async () => {
    const { result } = renderHook(() => useSubmitGuard());
    const { submitted, finish } = heldForm();
    const submit = result.current(submitted);

    const first = submit();
    const ignored = submit();
    expect(submitted).toHaveBeenCalledTimes(1);
    finish();
    await expect(first).resolves.toEqual({ ignored: false });
    await expect(ignored).resolves.toEqual({ ignored: true });
  });
});

describe('useSubmitGuard — 실패한 제출', () => {
  it('진행 중 제출이 실패하면 무시한 제출은 오류 없이 「무시됨」으로 끝나고(오류는 첫 호출자만 받음) 잠금도 풀린다', async () => {
    const { result } = renderHook(() => useSubmitGuard());
    const { submitted, fail, finish } = heldForm();
    const submit = result.current(submitted);

    const first = submit(fakeSubmitEvent().event);
    const ignored = submit(fakeSubmitEvent().event);
    fail(new Error('서버 오류'));
    await expect(first).rejects.toThrow('서버 오류');
    await expect(ignored).resolves.toEqual({ ignored: true });

    const retry = submit(fakeSubmitEvent().event);
    expect(submitted).toHaveBeenCalledTimes(2);
    finish();
    await retry;
  });

  it('제출 함수가 첫 await 전에 동기로 throw 해도 잠금이 남지 않는다', async () => {
    const { result } = renderHook(() => useSubmitGuard());
    const throwing = vi.fn(() => {
      throw new Error('동기 오류');
    });
    const submit = result.current(throwing as unknown as () => Promise<void>);

    await expect(submit(fakeSubmitEvent().event)).rejects.toThrow('동기 오류');
    await expect(submit(fakeSubmitEvent().event)).rejects.toThrow('동기 오류');
    // 잠금이 남았다면 두 번째 호출은 무시돼 1회였을 것이다
    expect(throwing).toHaveBeenCalledTimes(2);
  });
});

describe('guardFormSubmit', () => {
  it('같은 잠금을 넘긴 두 제출 경로(저장·덮어쓰기)는 한쪽이 진행 중이면 다른 쪽을 무시한다', async () => {
    const { result } = renderHook(() => useSubmitGuard());
    const save = heldForm();
    const overwrite = heldForm();

    const saveSubmit = guardFormSubmit(result.current, save.handleSubmit, vi.fn());
    const overwriteSubmit = guardFormSubmit(result.current, overwrite.handleSubmit, vi.fn());

    const saving = saveSubmit(fakeSubmitEvent().event);
    const ignored = fakeSubmitEvent();
    const ignoredDone = overwriteSubmit(ignored.event);
    expect(overwrite.submitted).not.toHaveBeenCalled();
    expect(ignored.preventDefault).toHaveBeenCalledTimes(1);
    // 무시된 덮어쓰기는 저장이 끝날 때까지 끝나지 않는다
    const ignoredState = track(ignoredDone);
    await flushMicrotasks();
    expect(ignoredState.settled).toBe(false);

    save.finish();
    // 결과로 「이 덮어쓰기는 무시됐고 저장이 끝났다」를 가를 수 있다(WD-688)
    await expect(saving).resolves.toEqual({ ignored: false });
    await expect(ignoredDone).resolves.toEqual({ ignored: true });
    const overwriting = overwriteSubmit(fakeSubmitEvent().event);
    expect(overwrite.submitted).toHaveBeenCalledTimes(1);
    overwrite.finish();
    await expect(overwriting).resolves.toEqual({ ignored: false });
  });
});

describe('useGuardedSubmit', () => {
  it('onValid 에는 값만 넘긴다 — 제출 이벤트가 두 번째 인자로 새지 않는다', async () => {
    const onValid = vi.fn();
    const { result } = renderHook(() => {
      const form = useForm<FormValues>({ defaultValues: { name: '값' } });
      return useGuardedSubmit(form.handleSubmit, onValid);
    });

    await act(async () => {
      await result.current(fakeSubmitEvent().event);
    });
    expect(onValid).toHaveBeenCalledTimes(1);
    expect(onValid.mock.calls[0]).toEqual([{ name: '값' }]);
  });

  it('검증 실패로 onValid 가 불리지 않아도 잠금이 풀려 고친 뒤 다시 제출할 수 있다', async () => {
    const onValid = vi.fn();
    const { result } = renderHook(() => {
      const form = useForm<FormValues>({ defaultValues: { name: '' } });
      // 이름이 비면 검증 실패 — register 의 required 규칙
      form.register('name', { required: true });
      return { form, submit: useGuardedSubmit(form.handleSubmit, onValid) };
    });

    let outcome: unknown;
    await act(async () => {
      outcome = await result.current.submit(fakeSubmitEvent().event);
    });
    expect(onValid).not.toHaveBeenCalled();
    // 검증 실패도 이 호출이 실제로 처리한 제출이다 — 「무시됨」이 아니다(WD-688)
    expect(outcome).toEqual({ ignored: false });

    act(() => {
      result.current.form.setValue('name', '고친 값');
    });
    await act(async () => {
      await result.current.submit(fakeSubmitEvent().event);
    });
    expect(onValid).toHaveBeenCalledTimes(1);
  });
});
