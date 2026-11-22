import { useCallback, useState } from 'react';

import { useReportValidity } from './useReportValidity';

/**
 * 여러 IntegerTextInput 의 유효성을 모아 「하나라도 무효인가」를 상위에 알리는 훅 (WD-906 후속).
 *
 * 칸마다 `onValidityChange={(v) => setFieldValid('칸 id', v)}` 로 연결한다. 모음이 바뀔 때만 `onChange(allValid)` 를 부르고,
 * 훅을 쓰는 컴포넌트가 사라질 때 무효였다면 true 로 되돌린다(useReportValidity).
 */
export function useIntegerFieldsValidity(onChange?: (valid: boolean) => void) {
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(new Set());
  const setFieldValid = useCallback((id: string, valid: boolean) => {
    setInvalid((prev) => {
      // 이미 그 상태면(무효인데 또 무효 / 유효인데 이미 빠져 있음) 그대로 둔다
      if (valid !== prev.has(id)) return prev;
      const next = new Set(prev);
      if (valid) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  useReportValidity(invalid.size === 0, onChange);
  return setFieldValid;
}
