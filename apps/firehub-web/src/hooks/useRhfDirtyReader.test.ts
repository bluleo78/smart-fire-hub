/**
 * useRhfDirtyReader 단위 테스트 (WD-662, WD-676).
 *
 * 저장 직후 resetToSaved(data) 를 부르고 같은 틱에 상세로 이동하면, 가드는 다시 그리기 전에 dirty 를 묻는다.
 * 그때 판정이 옛 기준선과 비교하면 dirty 로 보고 확인창이 떠 버린다 — 저장 즉시 새 기준을 따라야 한다.
 * (WD-676) 기준선은 훅이 직접 갖는다 — RHF 내부 필드(control._defaultValues)에 기대지 않는다.
 */
import { act, renderHook } from '@testing-library/react';
import { useForm } from 'react-hook-form';
import { describe, expect, it } from 'vitest';

import { useRhfDirtyReader } from './useRhfDirtyReader';

type FormValues = { name: string; count?: number };

const INITIAL: FormValues = { name: '', count: undefined };

/** 폼과 판정 함수를 함께 만든다 — 실제 페이지처럼 기본값을 준 form 객체를 훅에 넘긴다(기준선은 그 defaultValues). */
function useFormWithReader() {
  const form = useForm<FormValues>({ defaultValues: INITIAL });
  const { readIsDirty, resetToSaved } = useRhfDirtyReader(form);
  return { form, readDirty: readIsDirty, resetToSaved };
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

  it('resetToSaved(data) 직후 다시 그리기 전에도 판정이 false 다 (WD-662)', () => {
    const { result } = renderHook(() => useFormWithReader());
    act(() => {
      result.current.form.setValue('name', '저장할 이름');
    });
    expect(result.current.readDirty()).toBe(true);

    let dirtyRightAfterReset: boolean | undefined;
    act(() => {
      // act 콜백 안에서는 렌더가 콜백이 끝난 뒤로 미뤄진다 — 저장 직후 같은 틱에 이동을 시도하는 순간과 같다.
      result.current.resetToSaved({ name: '저장할 이름', count: undefined });
      dirtyRightAfterReset = result.current.readDirty();
    });
    expect(dirtyRightAfterReset).toBe(false);
    // 다시 그린 뒤에도 그대로 false
    expect(result.current.readDirty()).toBe(false);
  });

  it('RHF 내부 control._defaultValues 를 읽지 않는다 — 내부 필드가 없어져도 판정이 동작한다 (WD-676)', () => {
    const { result } = renderHook(() => useFormWithReader());
    // 내부 필드를 읽으면 바로 실패하게 만든다 — 판정·저장 초기화가 이 필드에 기대면 테스트가 깨진다.
    Object.defineProperty(result.current.form.control, '_defaultValues', {
      configurable: true,
      get() {
        throw new Error('control._defaultValues 를 읽었다');
      },
      set() {
        // reset 이 내부적으로 쓰는 것은 허용한다(읽기만 막는다).
      },
    });
    expect(result.current.readDirty()).toBe(false);
    act(() => {
      result.current.form.setValue('name', '입력');
    });
    expect(result.current.readDirty()).toBe(true);
  });

  it('resetToSaved 뒤 새 기준에서 값이 바뀌면 다시 dirty 다', () => {
    const { result } = renderHook(() => useFormWithReader());
    let dirtyAfterEdit: boolean | undefined;
    act(() => {
      result.current.resetToSaved({ name: '기준', count: 3 });
      result.current.form.setValue('count', 4);
      dirtyAfterEdit = result.current.readDirty();
    });
    expect(dirtyAfterEdit).toBe(true);
  });
});
