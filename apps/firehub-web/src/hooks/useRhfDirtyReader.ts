import { useCallback, useRef } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';

import { isSameFormValue } from '../lib/form-equality';

/**
 * useRhfDirtyReader (WD-642, WD-676)
 * - RHF 폼에 미저장 입력이 있는지 「물을 때마다」 판정하는 함수(readIsDirty)를 돌려준다 — useUnsavedChangesGuard 에 그대로 넘긴다.
 * - formState.isDirty 는 setValue 에 shouldDirty 를 빠뜨리면 갱신되지 않아 가드가 조용히 꺼질 수 있다.
 *   대신 현재 값(getValues)을 「저장된 값」 기준선과 직접 비교한다. 비교는 가드가 물을 때(이동 시도·뒤로가기·beforeunload)만 한다.
 * - 기준선은 이 훅이 ref 로 갖는다(WD-676). 첫 값은 마운트 때 폼의 defaultValues 스냅숏이다 — RHF 내부 필드
 *   control._defaultValues 에 기대지 않는다(RHF 를 올리면 사라지거나 바뀔 수 있다).
 *
 * resetToSaved(values): 저장 뒤 폼을 다시 맞출 때는 form.reset 대신 반드시 이것을 부른다. 기준선을 먼저 바꾸고 form.reset 한다 —
 * form.reset 만 부르면 기준선이 옛 값으로 남아 깨끗한 폼을 dirty 로 본다.
 *
 * @param form 판정할 RHF 폼(defaultValues 를 값으로 준 폼 — 비동기 defaultValues 는 지원하지 않는다)
 */
export function useRhfDirtyReader<T extends FieldValues>(
  form: UseFormReturn<T>,
): { readIsDirty: () => boolean; resetToSaved: (values: T) => void } {
  // 저장된 값 기준선 — 렌더와 무관하게 resetToSaved 즉시 바뀐다(저장 직후 같은 틱에 이동해도 맞게 판정).
  const baselineRef = useRef<T | undefined>(undefined);
  if (baselineRef.current === undefined) {
    baselineRef.current = form.formState.defaultValues as T | undefined;
  }

  const readIsDirty = useCallback(() => !isSameFormValue(form.getValues(), baselineRef.current), [form]);

  const resetToSaved = useCallback(
    (values: T) => {
      // 기준선을 먼저 바꾼다 — reset 이 동기로 구독자를 깨워 판정이 불려도 새 기준을 보게.
      baselineRef.current = values;
      form.reset(values);
    },
    [form],
  );

  return { readIsDirty, resetToSaved };
}
