/**
 * useRhfDirtyReader 단위 테스트 (WD-662).
 *
 * 저장 직후 form.reset(data) 를 부르고 같은 틱에 상세로 이동하면, 가드는 다시 그리기 전에 dirty 를 묻는다.
 * 그때 판정이 옛 기본값(렌더 스냅숏)과 비교하면 dirty 로 보고 확인창이 떠 버린다 — reset 즉시 새 기준을 따라야 한다.
 */
import { act, renderHook } from '@testing-library/react';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';

import { useRhfDirtyReader } from './useRhfDirtyReader';

type FormValues = { name: string; count?: number };

/** 폼과 판정 함수를 함께 만든다 — 실제 페이지처럼 같은 form 객체를 판정 함수에 넘긴다. */
function useFormWithReader() {
  const form = useForm<FormValues>({ defaultValues: { name: '', count: undefined } });
  const readDirty = useRhfDirtyReader(form);
  return { form, readDirty };
}

describe('useRhfDirtyReader', () => {
  it('기본값 그대로면 dirty 가 아니고, shouldDirty 없이 setValue 해도 dirty 로 본다', () => {
    const { result } = renderHook(() => useFormWithReader());
    expect(result.current.readDirty()).toBe(false);
    act(() => {
      result.current.form.setValue('name', '입력');
    });
    expect(result.current.readDirty()).toBe(true);
  });

  it('reset(data) 직후 다시 그리기 전에도 판정이 false 다 (WD-662)', () => {
    const { result } = renderHook(() => useFormWithReader());
    act(() => {
      result.current.form.setValue('name', '저장할 이름');
    });
    expect(result.current.readDirty()).toBe(true);

    let dirtyRightAfterReset: boolean | undefined;
    act(() => {
      // act 콜백 안에서는 렌더가 콜백이 끝난 뒤로 미뤄진다 — 저장 직후 같은 틱에 이동을 시도하는 순간과 같다.
      result.current.form.reset({ name: '저장할 이름', count: undefined });
      dirtyRightAfterReset = result.current.readDirty();
    });
    expect(dirtyRightAfterReset).toBe(false);
    // 다시 그린 뒤에도 그대로 false
    expect(result.current.readDirty()).toBe(false);
  });

  it('RHF 내부 control._defaultValues 가 reset 직후 새 값이다 — 판정이 기대는 내부 동작을 고정한다 (WD-662)', () => {
    // useRhfDirtyReader 는 공개되지 않은 control._defaultValues 에 기댄다. RHF 를 올려 이 필드가 사라지거나
    // reset 에서 동기로 바뀌지 않게 되면 이 테스트가 먼저 깨져 알린다(버전은 고정하지 않는다).
    const { result } = renderHook(() => useFormWithReader());
    let seen: unknown;
    act(() => {
      result.current.form.reset({ name: '새 기준', count: 7 });
      seen = result.current.form.control._defaultValues;
    });
    expect(seen).toEqual({ name: '새 기준', count: 7 });
  });

  it('reset 뒤 새 기준에서 값이 바뀌면 다시 dirty 다', () => {
    const { result } = renderHook(() => useFormWithReader());
    let dirtyAfterEdit: boolean | undefined;
    act(() => {
      result.current.form.reset({ name: '기준', count: 3 });
      result.current.form.setValue('count', 4);
      dirtyAfterEdit = result.current.readDirty();
    });
    expect(dirtyAfterEdit).toBe(true);
  });
});
