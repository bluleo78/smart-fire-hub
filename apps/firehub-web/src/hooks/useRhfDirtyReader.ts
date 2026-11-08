import { useCallback } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';

import { isSameFormValue } from '../lib/form-equality';

/**
 * useRhfDirtyReader (WD-642)
 * - RHF 폼에 미저장 입력이 있는지 「물을 때마다」 판정하는 함수를 돌려준다 — useUnsavedChangesGuard 에 그대로 넘긴다.
 * - formState.isDirty 는 setValue 에 shouldDirty 를 빠뜨리면 갱신되지 않아 가드가 조용히 꺼질 수 있다.
 *   대신 현재 값(getValues)을 기본값과 직접 비교한다.
 * - 매 렌더·키 입력마다 비교하지 않고, 가드가 물을 때(이동 시도·뒤로가기·beforeunload)만 비교한다.
 * - (WD-662) 기본값은 내부 필드 control._defaultValues 에서 읽는다 — formState.defaultValues 는 렌더 스냅숏이라 reset 직후
 *   다시 그리기 전까지 옛 값이다(RHF 7.71). 공개 API 가 생기면 바꾼다. 이 의존은 단위 테스트가 고정한다.
 */
export function useRhfDirtyReader<T extends FieldValues>(form: UseFormReturn<T>): () => boolean {
  return useCallback(() => !isSameFormValue(form.getValues(), form.control._defaultValues), [form]);
}
