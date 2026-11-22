import { useEffect, useRef } from 'react';

/**
 * 유효성(valid)이 바뀔 때만 콜백으로 부모에 알리는 훅 (WD-906 후속).
 *
 * - 마지막으로 알린 값을 ref 로 들고 있어 같은 값은 반복 호출하지 않는다(처음 유효는 알리지 않는다).
 * - 콜백은 ref 로 읽어 콜백 정체성이 바뀌어도 알림이 다시 나가지 않는다.
 * - 사라질 때(언마운트) 무효였다면 true 로 되돌려, 보이지 않는 칸 때문에 저장이 영영 막히지 않게 한다.
 */
export function useReportValidity(valid: boolean, onChange?: (valid: boolean) => void) {
  const cbRef = useRef(onChange);
  const reportedRef = useRef(true);
  useEffect(() => {
    cbRef.current = onChange;
  }, [onChange]);
  useEffect(() => {
    if (reportedRef.current !== valid) {
      reportedRef.current = valid;
      cbRef.current?.(valid);
    }
  }, [valid]);
  useEffect(
    () => () => {
      if (!reportedRef.current) cbRef.current?.(true);
    },
    [],
  );
}
