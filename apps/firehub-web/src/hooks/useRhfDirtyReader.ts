import { useCallback } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';

import { isSameFormValue } from '../lib/form-equality';

/**
 * useRhfDirtyReader (WD-642)
 * - RHF 폼에 미저장 입력이 있는지 「물을 때마다」 판정하는 함수를 돌려준다 — useUnsavedChangesGuard 에 그대로 넘긴다.
 * - formState.isDirty 는 setValue 에 shouldDirty 를 빠뜨리면 갱신되지 않아 가드가 조용히 꺼질 수 있다.
 *   대신 현재 값(getValues)을 기본값(formState.defaultValues)과 직접 비교한다.
 * - 매 렌더·키 입력마다 비교하지 않고, 가드가 물을 때(이동 시도·뒤로가기·beforeunload)만 비교한다.
 * - 기본값은 호출 시점의 formState.defaultValues 를 읽어, form.reset(data) 뒤 바뀐 기준을 따른다.
 */
export function useRhfDirtyReader<T extends FieldValues>(form: UseFormReturn<T>): () => boolean {
  return useCallback(() => !isSameFormValue(form.getValues(), form.formState.defaultValues), [form]);
}
